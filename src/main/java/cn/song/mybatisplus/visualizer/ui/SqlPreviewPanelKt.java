// T-SyncK2JMaster482 波D1 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt
// 文件顶层声明移植（KOTLIN_TO_JAVA_RULES §5 铁律⑦：顶层函数 → XKt.java 门面）。
// T-M53-II（2026-10-10）拆分后本文件顶层仅剩两个常量；原 buildStackDigestMetaText /
// ellipsizeMiddle / clipTailToWidth / metaLineWithinBudget / META_DIGEST_MIN_PX 已随
// SqlPreviewPanelText.kt 迁至 {@link SqlPreviewPanelTextKt}（buildStackDigestMetaText 收窄为
// buildStackDigestInlineLine）。
package cn.song.mybatisplus.visualizer.ui;

/**
 * {@code SqlPreviewPanel.kt} 顶层常量的 Java 承载类。
 *
 * <p>Kotlin 顶层声明编译落点为 {@code <文件名>Kt}，故本类名为 {@code SqlPreviewPanelKt}。</p>
 */
public final class SqlPreviewPanelKt {

    private SqlPreviewPanelKt() {
    }

    // ------------------------------------------------------------------
    // T-SourceJumpFunnel P2-2（§3.3 验收项单测抽取）：调用栈快照文案组装
    // ------------------------------------------------------------------

    /** 调用栈摘要字符上限：超过即截断到该长度并追加 {@link #STACK_DIGEST_ELLIPSIS}。 */
    public static final int STACK_DIGEST_MAX_CHARS = 120;

    /** 调用栈摘要截断标记。 */
    public static final String STACK_DIGEST_ELLIPSIS = "…";
}
