package cn.song.mybatisplus.visualizer.xml;

/** 分档理由码（T-EP-03 条件行标注 tooltip 用；UI 侧映射 bundle 文案，xml 层不持有文案）。 */
public enum ExprReason {
    /** 解析失败（{@link ExprSupport#UNSUPPORTED_SYNTAX} 的唯一理由）。 */
    SYNTAX,

    /** 未知方法调用：不在白名单内且非 getter 形态，求值静默返回 null。 */
    UNKNOWN_METHOD,

    /** 白名单方法在接收者载体上无实现（如 {@code length()} 只对字符串有实现），求值静默返回 null。 */
    NARROW_CARRIER,

    /** 静态字段引用：取值由 PSI 层注入，xml 层无法判定可用性，未注入时求值为 null。 */
    STATIC_FIELD,
}
