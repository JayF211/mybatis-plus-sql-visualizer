package cn.song.mybatisplus.visualizer.xml;

/**
 * 分类结论：档位 + 理由码 + 细节（方法名 / {@code @类@字段} / 解析错误消息）。
 * {@link ExprSupport#SUPPORTED} 时 {@code reason} 与 {@code detail} 均为 null。
 *
 * @param support 支持面档位
 * @param reason  分档理由码（SUPPORTED 时为 null）
 * @param detail  理由细节（方法名 / {@code @类@字段} / 解析错误消息）
 */
public record ExprClassification(ExprSupport support, ExprReason reason, String detail) {

    /** Kotlin 默认参数等价重载：{@code reason} / {@code detail} 缺省为 null（SUPPORTED 档形态）。 */
    public ExprClassification(ExprSupport support) {
        this(support, null, null);
    }
}
