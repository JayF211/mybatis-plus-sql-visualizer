package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渲染上下文（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.scripting.xmltags.DynamicContext。
 *
 * - bindings：变量上下文（初始 _parameter / _databaseId；bind / foreach 变量写入）
 * - appendSql：段间以单个空格分隔（对齐 3.5.13+ 的 StringJoiner(" ") 行为），最终 getSql() 去首尾空白
 * - conditionOverrides：条件开关覆盖（预览面板直接决定 if/when 真假，id = test 表达式原文）
 * - protectedBindNames：bind 覆盖名（面板输入的覆盖值优先于表达式求值）
 *
 * 预览引擎专用扩展（conditionOverrides / protectedBindNames / errors）不影响运行时复刻语义。
 */
public class DynamicContext {

    /** _parameter 键。 */
    public static final String PARAMETER_OBJECT_KEY = "_parameter";

    /** _databaseId 键。 */
    public static final String DATABASE_ID_KEY = "_databaseId";

    private final Map<String, Boolean> conditionOverrides;
    private final Set<String> protectedBindNames;
    private final Map<String, Object> staticFieldValues;

    /**
     * 变量上下文（_parameter / _databaseId / bind 变量 / foreach item·index / 模拟值）。
     *
     * 注意：用属性初始化器而非 init 块填充——子类（Trim/Foreach 的过滤上下文）覆写本属性
     * 转发 delegate，父类 init 块中的调用会虚分派到未初始化的子类字段（NPE）。
     */
    private Map<String, Object> bindings;

    /** 渲染期错误提示（如 foreach collection 为 null 且 nullable=false、表达式解析失败）。 */
    private final List<String> errors = new ArrayList<>();

    /**
     * ${} 直替计数（T-DollarInjectMark）：TextSqlNode 每求值一个 ${} token 经
     * {@link #countDollarSubstitution()} 自增——引擎忠实口径（MyBatis 对字符串字面量内的 ${}
     * 同样直替，计数不排除）；转义 \${ 不经求值不计。渲染结束随
     * RenderedXmlSql.dollarSubstitutions 出栈，预览横幅消费。
     */
    private int dollarSubstitutions = 0;

    private final StringBuilder sqlBuilder = new StringBuilder();
    private int uniqueNumber = 0;

    public DynamicContext(Map<String, Object> initialBindings) {
        this(initialBindings, Map.of(), Set.of(), Map.of());
    }

    public DynamicContext(
            Map<String, Object> initialBindings,
            Map<String, Boolean> conditionOverrides,
            Set<String> protectedBindNames,
            Map<String, Object> staticFieldValues
    ) {
        this.conditionOverrides = conditionOverrides;
        this.protectedBindNames = protectedBindNames;
        this.staticFieldValues = staticFieldValues;
        this.bindings = new HashMap<>(initialBindings);
    }

    /** 变量上下文。 */
    public Map<String, Object> getBindings() {
        return bindings;
    }

    /** 渲染期错误列表。 */
    public List<String> getErrors() {
        return errors;
    }

    /** ${} 直替计数（T-DollarInjectMark，Kotlin {@code protected set}：外部只读，经计数入口自增）。 */
    public int getDollarSubstitutions() {
        return dollarSubstitutions;
    }

    /** 直替计数入口：子类过滤上下文（Foreach 的 Prefixed/Filtered）转发到 delegate，计数落根上下文。 */
    public void countDollarSubstitution() {
        dollarSubstitutions++;
    }

    /** 追加 SQL 段（段间单个空格；空段不产生分隔）。 */
    public void appendSql(String sql) {
        if (sql.isEmpty()) {
            return;
        }
        if (sqlBuilder.length() > 0) {
            sqlBuilder.append(' ');
        }
        sqlBuilder.append(sql);
    }

    /** 当前累积 SQL（首尾空白已去除）。 */
    public String getSql() {
        return sqlBuilder.toString().trim();
    }

    /** 绑定变量（bind / foreach item·index）。 */
    public void bind(String name, Object value) {
        getBindings().put(name, value);
    }

    /** 每次调用自增的唯一编号（foreach 的 __frch_<名>_<n> 用）。 */
    public int getUniqueNumber() {
        return uniqueNumber++;
    }

    /** 条件开关覆盖：test 表达式原文 → 强制真值；null 表示无覆盖（走表达式求值）。 */
    public Boolean conditionResult(String test) {
        return conditionOverrides.get(test);
    }

    /** bind 名是否受保护（面板覆盖值优先，VarDeclSqlNode 跳过求值）。 */
    public boolean isProtected(String name) {
        return protectedBindNames.contains(name);
    }

    /** 静态字段值（T-M25.2）：@class@field → 值。 */
    public Map<String, Object> getStaticFieldValues() {
        return staticFieldValues;
    }
}
