package cn.song.mybatisplus.visualizer.xml;

import java.util.AbstractList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 渲染上下文（T-M24-01）：复刻 MyBatis 3.5.19 org.apache.ibatis.scripting.xmltags.DynamicContext。
 *
 * - bindings：变量上下文（初始 _parameter / _databaseId；bind / foreach 变量写入）
 * - appendSql：段间以单个空格分隔（对齐 3.5.13+ 的 StringJoiner(" ") 行为），最终 getSql() 去首尾空白
 * - conditionOverrides：条件开关覆盖（预览面板直接决定 if/when 真假，id = test 表达式原文）
 * - protectedBindNames：bind 覆盖名（面板输入的覆盖值优先于表达式求值）
 * - errors：渲染期错误（判重表，嵌套子上下文须转发 delegate，否则内层错误静默）
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

    /**
     * 渲染期错误提示（如 foreach collection 为 null 且 nullable=false、表达式解析失败）。
     *
     * <p>T-FS-07：①判重表——{@code <foreach>} 逐迭代会把同一表达式同一原因重复登记 N 次，
     * 用户看到的 {@code ⚠} 警示行（{@code errors.joinToString("；")}）不该被同一句话刷屏，故同文案只留首现一行
     * （判重键 = 完整错误文案：9 个写入点里 8 个的文案内嵌表达式原文 + 原因，天然等价于「同表达式同原因」；
     * 例外是 {@link IfSqlNode} 的「bind 表达式被 {@code ||} 包裹」提示，文案固定不含表达式名——同一语句里两条不同的
     * {@code ||X||} bind 会并成一行，只丢「条数」不丢可区分信息，属可接受边界）；
     * ②子上下文（Foreach 的 Prefixed/Filtered、Trim 的 filtered）覆写 {@link #getErrors()} 转发 delegate——
     * 此前各自持有独立列表，内层 {@code <if>} 求值失败被丢弃（静默，与 ${}/bind/顶层 if 形态不一致）。
     */
    private final List<String> errors = new DedupErrorList();

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

    /**
     * 渲染期错误列表（判重表，T-FS-07）。
     *
     * <p>可覆写：嵌套子上下文（Foreach 的 Prefixed/Filtered、Trim 的 filtered）必须转发到 delegate
     * 根上下文，否则内层求值失败写进本层临时列表后被丢弃。
     */
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

    /**
     * 判重错误表（T-FS-07）：LinkedHashSet 语义的 {@link List}——保首现顺序、同文案只留一行。
     *
     * <p>只用于 {@link DynamicContext#getErrors()}（MyBatis 无此概念，属预览引擎专用扩展）；保留 List 形态
     * 使既有写入点（{@code context.errors += msg}）与既有测试无需改动。{@code add(index, element)} 忽略索引——
     * 判重集合无插入位置语义，仓内既有写入只有 {@code +=} / {@code add(msg)} 两种形态。
     *
     * <p><b>只支持「追加 + 按值删」语义，不支持位置型写入</b>（T-FS-07 独立复核注记）：{@code add(index, element)}
     * 忽略索引、{@code set(index, element)} 把新值挪到末尾（且新值已存在时会缩容）。当前仓内对 {@code .errors}
     * 无任何位置型调用点（{@code listIterator().set/add}、{@code sort}、{@code subList} 写入、{@code addAll(index, …)} 全仓零处），
     * 故无实际后果；日后若有人用位置型操作，行为会静默偏离 List 契约（modCount 也不自增，CME 保护失效）
     * ——需要位置语义时应改用普通 ArrayList，而不是改本类。
     */
    private static final class DedupErrorList extends AbstractList<String> {

        private final LinkedHashSet<String> seen = new LinkedHashSet<>();

        @Override
        public int size() {
            return seen.size();
        }

        @Override
        public String get(int index) {
            int i = 0;
            for (String s : seen) {
                if (i++ == index) {
                    return s;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + seen.size());
        }

        @Override
        public void add(int index, String element) {
            seen.add(element);
        }

        @Override
        public String remove(int index) {
            String previous = get(index);
            seen.remove(previous);
            return previous;
        }

        /** 判重集合无「按位替换」语义：旧值移除、新值追加到末尾（仓内无调用点，仅为满足 List 契约）。 */
        @Override
        public String set(int index, String element) {
            String previous = get(index);
            if (!previous.equals(element)) {
                seen.remove(previous);
                seen.add(element);
            }
            return previous;
        }

        @Override
        public boolean add(String element) {
            return seen.add(element);
        }

        @Override
        public void clear() {
            seen.clear();
        }

        @Override
        public boolean contains(Object element) {
            return seen.contains(element);
        }

        @Override
        public int indexOf(Object element) {
            int i = 0;
            for (String s : seen) {
                if (Objects.equals(s, element)) {
                    return i;
                }
                i++;
            }
            return -1;
        }

        @Override
        public Iterator<String> iterator() {
            return seen.iterator();
        }
    }
}
