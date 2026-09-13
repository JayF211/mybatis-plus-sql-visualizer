package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * xbatis（原 mybatis-mp）QueryChain / UpdateChain / DeleteChain 转换器（XB，M44 其他 ORM 第一批，矩阵 §6）。
 *
 * 结构镜像 [LambdaQueryConverter] → [QueryWrapperParser] 委派：xbatis 条件方法名与 MP lambda
 * **高度重合**（javap cn.xbatis 1.10.8 `db.sql.api.cmd.executor.method.IConditionMethod` 与
 * 旧坐标 cn.mybatis-mp 1.8.1-RC3 实证：eq/ne/gt/lt/between/notBetween/in/notIn/isNull/isNotNull/
 * orderByAsc/orderByDesc/limit 与 MP 同名），差异方法名逐一归一化后委托 MP 解析器：
 *
 * <ul>
 *  <li>`gte` / `lte`（xbatis 无 `ge`/`le` 短名）→ `ge` / `le`；</li>
 *  <li>`like(LikeMode, col, v)`：xbatis 首参为 `db.sql.api.cmd.LikeMode` 枚举（ALL/LEFT/RIGHT）——
 *      映射 like / likeLeft / likeRight；`notLike` 同法；`iLike`/`notILike`（大小写不敏感）近似
 *      like/notLike（MySQL 默认 CI，方言差异注释见矩阵口径）；</li>
 *  <li>`andNested(Consumer)` / `orNested(Consumer)` → `and` / `or`（嵌套 lambda 组）；</li>
 *  <li>`empty/notEmpty`（xbatis 空串语义）→ 暂不建模（静默忽略，不产错误 SQL）；</li>
 *  <li>`where(Getter, Function)` / `and(Getter, Function)` 复杂 Function 条件形态 → 暂不建模。</li>
 * </ul>
 *
 * <p>终结点（get/getOptional/list/cursor/count/exists/paging/execute）由 WrapperLocator
 * chainTerminalMethodNames 剔除，不入链。链家族语义分派：</p>
 * <ul>
 *  <li>UpdateChain：`set(...)` 家族调用（javap IUpdateSetMethod）→ [UpdateStatement]；</li>
 *  <li>DeleteChain：WrapperLocator 置 deleteConsumed → [DeleteStatement]；</li>
 *  <li>QueryChain（默认）→ [QueryWrapperParser] SELECT。</li>
 * </ul>
 *
 * <p>双坐标（cn.xbatis. / cn.mybatis-mp.）识别见 WrapperLocator（FQN 前缀 + 工厂简名双重门控）。</p>
 */
public final class XbatisConverter implements WrapperConverter {

    /** 单例（Kotlin object 转换）。 */
    public static final XbatisConverter INSTANCE = new XbatisConverter();

    /** xbatis LikeMode 枚举引用占位文本特征（`LikeMode.ALL` / `db.sql.api.cmd.LikeMode.LEFT`）。 */
    private static final String LIKE_MODE_MARKER = "LikeMode";

    /** xbatis set 家族方法名（javap IUpdateSetMethod：`set(...)` 全重载）。 */
    private static final Set<String> SET_METHODS = Set.of("set");

    /** xbatis like 族方法（javap IConditionMethod：like/notLike/iLike/notILike，首参 LikeMode）。 */
    private static final Set<String> LIKE_MODE_METHODS = Set.of("like", "notLike", "iLike", "notILike");

    /** 暂不建模的 xbatis 方法（静默忽略——宁可少条件不可出错误 SQL）。 */
    private static final Set<String> UNSUPPORTED_METHODS = Set.of("empty", "notEmpty");

    private XbatisConverter() {
    }

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.XBATIS);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table) {
        List<WrapperCall> calls = normalize(chain.getCalls());
        // 1. UpdateChain：set 调用存在 → UPDATE（set 列赋值 + 其余条件 WHERE）
        boolean hasSetCall = false;
        for (WrapperCall call : calls) {
            if (SET_METHODS.contains(call.getMethodName())) {
                hasSetCall = true;
                break;
            }
        }
        if (hasSetCall) {
            return updateStatement(table, calls);
        }
        // 2. DeleteChain：条件链转 DELETE
        if (chain.isDeleteConsumed()) {
            return new DeleteStatement(table, parseConditions(table, calls));
        }
        // 3. QueryChain（默认）：SELECT（方法名归一后与 MP lambda 语义一致，直接委托）
        return new QueryWrapperParser(table).parse(chain.copy(chain.getWrapperType(), chain.getEntityClass(), calls,
                chain.getConstructorAlias(), chain.isDeleteConsumed(), chain.isCountConsumed(),
                chain.isExistsConsumed(), chain.isUpdateEntityConsumed(), chain.getPageLimit()));
    }

    /** 条件子集解析（嵌套链形态，复用 QueryWrapperParser 的条件树）。 */
    private List<Condition> parseConditions(TableInfo table, List<WrapperCall> calls) {
        WrapperChain nested = new WrapperChain(WrapperType.NESTED, table.getEntityClassName(), calls);
        return new QueryWrapperParser(table).parse(nested).getConditions();
    }

    // ------------------------------------------------------------------
    // 方法名归一化（xbatis → MP 解析器语义）
    // ------------------------------------------------------------------

    /**
     * 归一化 xbatis 差异方法名（见类 KDoc）；MP 同名方法原样保留。
     * like 族 LikeMode 首参移除并按模式归位 like/likeLeft/likeRight。
     */
    private List<WrapperCall> normalize(List<WrapperCall> calls) {
        List<WrapperCall> result = new ArrayList<>();
        for (WrapperCall call : calls) {
            String methodName = call.getMethodName();
            if (LIKE_MODE_METHODS.contains(methodName)) {
                result.addAll(normalizeLikeMode(call));
            } else if ("gte".equals(methodName)) {
                result.add(call.copy("ge", call.getArgs()));
            } else if ("lte".equals(methodName)) {
                result.add(call.copy("le", call.getArgs()));
            } else if ("andNested".equals(methodName)) {
                result.add(call.copy("and", call.getArgs()));
            } else if ("orNested".equals(methodName)) {
                result.add(call.copy("or", call.getArgs()));
            } else if (UNSUPPORTED_METHODS.contains(methodName)) {
                // 静默忽略（宁可少条件不可出错误 SQL）
            } else {
                result.add(call);
            }
        }
        return result;
    }

    /**
     * like 族 LikeMode 首参归一化：`like(LikeMode.LEFT, col, v)` → `likeLeft(col, v)`。
     * 形态守卫：3 参且首参为非列引用（LikeMode 枚举引用占位）才按模式形态解析，
     * 其余形态（2 参直接 like）原样降级为 ANYWHERE。
     */
    private List<WrapperCall> normalizeLikeMode(WrapperCall call) {
        List<WrapperArg> args = call.getArgs();
        if (args.size() < 2) {
            return List.of(call);
        }
        String modeText = null;
        if (args.get(0) instanceof WrapperArg.ValueArg first) {
            SqlValue value = first.getValue();
            if (value instanceof SqlValue.Placeholder placeholder) {
                modeText = placeholder.getSource();
            } else if (value instanceof SqlValue.Literal literal) {
                modeText = literal.getUnquoted();
            }
        }
        boolean hasMode = args.size() >= 3 && modeText != null && modeText.contains(LIKE_MODE_MARKER);
        List<WrapperArg> rest = hasMode ? new ArrayList<>(args.subList(1, args.size())) : args;
        LikePosition position = hasMode ? likePositionOf(modeText == null ? "" : modeText) : LikePosition.ANYWHERE;
        String baseName;
        switch (call.getMethodName()) {
            case "iLike" -> baseName = "like";
            case "notILike" -> baseName = "notLike";
            default -> baseName = call.getMethodName();
        }
        String mapped;
        if (position == LikePosition.LEFT) {
            mapped = "like".equals(baseName) ? "likeLeft" : "notLikeLeft";
        } else if (position == LikePosition.RIGHT) {
            mapped = "like".equals(baseName) ? "likeRight" : "notLikeRight";
        } else {
            mapped = baseName;
        }
        return List.of(new WrapperCall(mapped, rest));
    }

    /** LikeMode 枚举文本（ALL/LEFT/RIGHT，末段大写）→ LikePosition；未知值降级 ANYWHERE。 */
    private LikePosition likePositionOf(String modeText) {
        int dotIndex = modeText.lastIndexOf('.');
        String tail = dotIndex >= 0 ? modeText.substring(dotIndex + 1) : modeText;
        String upper = tail.toUpperCase(Locale.ROOT);
        if ("LEFT".equals(upper)) {
            return LikePosition.LEFT;
        }
        if ("RIGHT".equals(upper)) {
            return LikePosition.RIGHT;
        }
        return LikePosition.ANYWHERE;
    }

    // ------------------------------------------------------------------
    // UPDATE / DELETE 语句构建
    // ------------------------------------------------------------------

    /** UpdateChain set 调用 → SET 子句：`set(User::getName, "x")` → `SET user_name = 'x'`。 */
    private SqlStatement updateStatement(TableInfo table, List<WrapperCall> calls) {
        List<SetClause> sets = new ArrayList<>();
        List<WrapperCall> conditionCalls = new ArrayList<>();
        for (WrapperCall call : calls) {
            if (SET_METHODS.contains(call.getMethodName())) {
                ColumnRef column = !call.getArgs().isEmpty() ? WrapperConverterKt.asColumn(call.getArgs().get(0)) : null;
                SqlValue value = call.getArgs().size() > 1 ? WrapperConverterKt.asValue(call.getArgs().get(1)) : null;
                if (column != null && value != null) {
                    sets.add(new SetClause(new ColumnRef(column.getColumnName()), value));
                }
            } else {
                conditionCalls.add(call);
            }
        }
        List<Condition> conditions = parseConditions(table, conditionCalls);
        return new UpdateStatement(table, sets, conditions);
    }
}
