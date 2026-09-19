package cn.song.mybatisplus.visualizer.xml;

/**
 * 表达式支持面分档（T-ExprAstPrecheck T-EP-01）：把「求值失败/静默降级」提前成声明性判定。
 *
 * <p>支持面清单不另造语义，直接取自求值器既有事实：解析器能产出的 AST 节点与运算符、
 * {@code OgnlEvaluator} 方法白名单、{@code OgnlEvaluator} 的属性/getter 访问语义。</p>
 */
public enum ExprSupport {
    /** 语法与语义都在受限子集内（含属性链、下标、getter 形态、载体无关的白名单方法）。 */
    SUPPORTED,

    /** 语法即超出受限子集（三元 / 赋值 / lambda / 静态方法 / new 等），解析返回 {@link ParseResult.Err}。 */
    UNSUPPORTED_SYNTAX,

    /** 语法可解析，但含求值期降级形态（未知方法链 / 载体受限的白名单方法 / 静态字段引用）——求值会返回 null 而不报错。 */
    EVAL_FALLBACK,
}
