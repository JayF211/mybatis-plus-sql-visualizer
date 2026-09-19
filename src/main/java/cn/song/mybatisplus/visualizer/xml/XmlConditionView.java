package cn.song.mybatisplus.visualizer.xml;

/**
 * XML 预览条件行（T-M24-04）：一个可勾选开关的动态条件（if / choose-when）。
 *
 * @param id             条件标识 = test 表达式原文（启用集合元素；同表达式多处出现共享开关）
 * @param label          人读条件文本（复选框标签 = 表达式）
 * @param defaultEnabled 默认勾选态（启发式：含 == null → false，其余 true——保守展开）
 * @param support        表达式支持面分档（T-ExprAstPrecheck T-EP-03 预检产物，xml 层字符串，零 PSI）
 * @param supportReason  分档理由码（标注 tooltip 用；SUPPORTED 时为 null）
 * @param supportDetail  理由细节（方法名 / @类@字段 / 解析错误消息）
 */
public record XmlConditionView(String id, String label, boolean defaultEnabled,
                               OgnlExpr.ExprSupport support, OgnlExpr.ExprReason supportReason, String supportDetail) {

    /** Kotlin 默认参数等价重载：support 缺省 SUPPORTED、reason/detail 缺省 null（T-EP-03 之前的三参形态）。 */
    public XmlConditionView(String id, String label, boolean defaultEnabled) {
        this(id, label, defaultEnabled, OgnlExpr.ExprSupport.SUPPORTED, null, null);
    }
}
