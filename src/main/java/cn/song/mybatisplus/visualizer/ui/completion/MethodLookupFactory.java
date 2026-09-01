package cn.song.mybatisplus.visualizer.ui.completion;

import cn.song.mybatisplus.visualizer.model.WrapperType;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper 方法名补全项工厂（F-5，阶段 A3）。
 *
 * <p>根据 {@link ChainStateDetector.ChainState} 生成上下文感知的方法名补全项。
 * 补全项带括号尾巴 + 插入处理器（光标定位到括号内），减少用户输入。
 *
 * <p>方法分组（对齐 MP 官方 API 语义）：
 * <ul>
 *   <li>条件方法：eq/ne/gt/ge/lt/le/like/notLike/in/notIn/isNull/isNotNull/between/notBetween</li>
 *   <li>逻辑连接：and/or</li>
 *   <li>聚合/分组：groupBy/having</li>
 *   <li>排序：orderBy/orderByAsc/orderByDesc</li>
 *   <li>字段选择：select/selectAs/selectIgnore</li>
 *   <li>UPDATE 专用：set/setSql/setIncrBy/setDecrBy</li>
 *   <li>终结点：list/one/count/exists/page/getOne/getMap</li>
 *   <li>MPJ 联表：leftJoin/rightJoin/innerJoin/fullJoin/selectAll</li>
 * </ul>
 */
public final class MethodLookupFactory {

    /** 方法名 → 展示描述（灰色 tailText） */
    private static final Map<String, String> METHOD_DESCRIPTIONS = new LinkedHashMap<>();

    static {
        METHOD_DESCRIPTIONS.put("eq", "=");
        METHOD_DESCRIPTIONS.put("ne", "<>");
        METHOD_DESCRIPTIONS.put("gt", ">");
        METHOD_DESCRIPTIONS.put("ge", ">=");
        METHOD_DESCRIPTIONS.put("lt", "<");
        METHOD_DESCRIPTIONS.put("le", "<=");
        METHOD_DESCRIPTIONS.put("like", "LIKE");
        METHOD_DESCRIPTIONS.put("notLike", "NOT LIKE");
        METHOD_DESCRIPTIONS.put("in", "IN");
        METHOD_DESCRIPTIONS.put("notIn", "NOT IN");
        METHOD_DESCRIPTIONS.put("isNull", "IS NULL");
        METHOD_DESCRIPTIONS.put("isNotNull", "IS NOT NULL");
        METHOD_DESCRIPTIONS.put("between", "BETWEEN");
        METHOD_DESCRIPTIONS.put("notBetween", "NOT BETWEEN");
        METHOD_DESCRIPTIONS.put("and", "AND");
        METHOD_DESCRIPTIONS.put("or", "OR");
        METHOD_DESCRIPTIONS.put("orderBy", "ORDER BY");
        METHOD_DESCRIPTIONS.put("orderByAsc", "ASC");
        METHOD_DESCRIPTIONS.put("orderByDesc", "DESC");
        METHOD_DESCRIPTIONS.put("groupBy", "GROUP BY");
        METHOD_DESCRIPTIONS.put("having", "HAVING");
        METHOD_DESCRIPTIONS.put("select", "SELECT");
        METHOD_DESCRIPTIONS.put("set", "SET");
        METHOD_DESCRIPTIONS.put("list", "查询列表");
        METHOD_DESCRIPTIONS.put("one", "查询单条");
        METHOD_DESCRIPTIONS.put("count", "计数");
        METHOD_DESCRIPTIONS.put("exists", "存在判断");
        METHOD_DESCRIPTIONS.put("page", "分页");
        METHOD_DESCRIPTIONS.put("leftJoin", "左联");
        METHOD_DESCRIPTIONS.put("rightJoin", "右联");
        METHOD_DESCRIPTIONS.put("innerJoin", "内联");
        METHOD_DESCRIPTIONS.put("fullJoin", "全联");
        METHOD_DESCRIPTIONS.put("selectAll", "全字段");
    }

    // ------------------------------------------------------------------
    // 方法分组常量
    // ------------------------------------------------------------------

    /** 条件方法（WHERE 子句谓词）。 */
    private static final List<String> CONDITION_METHODS = List.of(
            "eq", "ne", "gt", "ge", "lt", "le",
            "like", "notLike", "in", "notIn",
            "isNull", "isNotNull", "between", "notBetween"
    );

    /** 链首默认方法（查询类 wrapper 初始可用）。 */
    private static final List<String> QUERY_HEAD_METHODS = List.of(
            "eq", "ne", "gt", "ge", "lt", "le",
            "like", "notLike", "in", "notIn",
            "isNull", "isNotNull", "between", "notBetween",
            "orderBy", "groupBy", "having", "select"
    );

    /** 条件方法之后可接的方法。 */
    private static final List<String> AFTER_CONDITION_METHODS = List.of(
            "and", "or",
            "eq", "ne", "gt", "ge", "lt", "le",
            "like", "in", "orderBy", "groupBy",
            "limit", "last", "exists"
    );

    /** select 之后可接的方法。 */
    private static final List<String> AFTER_SELECT_METHODS = List.of(
            "selectAs", "selectIgnore", "selectCount",
            "groupBy", "having", "orderBy", "limit"
    );

    /** orderBy 之后可接的方法。 */
    private static final List<String> AFTER_ORDER_BY_METHODS = List.of(
            "orderByAsc", "orderByDesc",
            "limit", "page", "last", "exists", "list", "one"
    );

    /** UPDATE 的 set 之后可接的方法。 */
    private static final List<String> AFTER_SET_METHODS = List.of(
            "set", "setSql", "setIncrBy", "setDecrBy",
            "eq", "ne", "where"
    );

    /** 链终结点方法。 */
    private static final List<String> TERMINAL_METHODS = List.of(
            "list", "one", "count", "exists", "page", "getOne", "getMap"
    );

    /** MPJ 联表方法（MPJ wrapper 链首额外提示）。 */
    private static final List<String> MPJ_JOIN_METHODS = List.of(
            "leftJoin", "rightJoin", "innerJoin", "fullJoin",
            "selectAll", "selectAs", "selectIgnore"
    );

    /** MPJ Wrapper 类型集合（需额外提示联表方法）。 */
    private static final Set<WrapperType> MPJ_WRAPPER_TYPES = Set.of(
            WrapperType.MPJ_LAMBDA, WrapperType.MPJ_QUERY, WrapperType.MPJ_LAMBDA_QUERY,
            WrapperType.UPDATE_JOIN, WrapperType.DELETE_JOIN
    );

    private MethodLookupFactory() {
    }

    /**
     * 根据链状态生成方法名补全项。
     *
     * @param state 链状态（{@link ChainStateDetector#detect} 返回值）
     * @return 补全项列表（已按上下文优先级排序）；空列表表示当前上下文无推荐方法
     */
    @NotNull
    public static List<LookupElement> createLookups(@NotNull ChainStateDetector.ChainState state) {
        List<String> methodNames = selectMethods(state);
        List<LookupElement> result = new ArrayList<>();
        for (String m : methodNames) {
            result.add(buildLookupElement(m));
        }
        return result;
    }

    /**
     * 按链状态选择应提示的方法名集合（开发计划 §2.1 上下文感知规则）。
     */
    @NotNull
    private static List<String> selectMethods(@NotNull ChainStateDetector.ChainState state) {
        // 实参内不提示方法名（应提示字段名——由 FieldLookupFactory 负责）
        if (state.isInsideMethodArgs()) {
            return List.of();
        }

        // 终结点之后不提示（链已结束）
        if (state.lastMethod() != null && TERMINAL_METHODS.contains(state.lastMethod())) {
            return List.of();
        }

        if (MPJ_WRAPPER_TYPES.contains(state.wrapperType()) && state.calledMethods().isEmpty()) {
            // MPJ wrapper 链首额外提示联表方法
            List<String> r = new ArrayList<>(MPJ_JOIN_METHODS);
            r.addAll(QUERY_HEAD_METHODS);
            return r;
        }

        // UPDATE 链：上一个方法是 set
        if ("set".equals(state.lastMethod())) {
            return AFTER_SET_METHODS;
        }

        // select 之后
        if ("select".equals(state.lastMethod())) {
            return AFTER_SELECT_METHODS;
        }

        // orderBy 之后
        if ("orderBy".equals(state.lastMethod())) {
            return AFTER_ORDER_BY_METHODS;
        }

        // 条件方法之后
        if (state.lastMethod() != null && CONDITION_METHODS.contains(state.lastMethod())) {
            return AFTER_CONDITION_METHODS;
        }

        // 链首（无已调用方法）或 and/or 之后
        return QUERY_HEAD_METHODS;
    }

    /**
     * 构建单个方法名补全项：
     * <ul>
     *   <li>展示名 = 方法名 + {@code ()} 尾巴（灰色）</li>
     *   <li>插入后光标定位到括号内</li>
     *   <li>带图标（方法节点图标）</li>
     * </ul>
     */
    @NotNull
    private static LookupElement buildLookupElement(@NotNull String methodName) {
        String description = METHOD_DESCRIPTIONS.get(methodName);
        LookupElementBuilder builder = LookupElementBuilder.create(methodName)
                .withIcon(AllIcons.Nodes.Method)
                .withTailText("()", true)
                .withInsertHandler(METHOD_INSERT_HANDLER);
        return description != null ? builder.withTypeText(description) : builder;
    }

    /**
     * 方法名插入处理器：插入 {@code methodName()} 后将光标定位到括号内。
     * 仅当下一字符不是 {@code (} 时插入括号（避免重复）。
     */
    @NotNull
    private static final InsertHandler<LookupElement> METHOD_INSERT_HANDLER = new InsertHandler<LookupElement>() {
        @Override
        public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
            Editor editor = context.getEditor();
            int caretOffset = editor.getCaretModel().getOffset();
            Document doc = editor.getDocument();
            // 仅在下一字符不是 ( 时才插入括号（用户已输入时不重复）
            if (caretOffset >= doc.getTextLength() || doc.getCharsSequence().charAt(caretOffset) != '(') {
                doc.insertString(caretOffset, "()");
                editor.getCaretModel().moveToOffset(caretOffset + 1);
            }
        }
    };
}
