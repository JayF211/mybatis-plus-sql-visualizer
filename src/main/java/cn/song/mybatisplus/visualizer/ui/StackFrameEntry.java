// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/StackDigestFrames.kt
// （KOTLIN_TO_JAVA_RULES §2：data class 全 val 无方法 → Java record；record 强制带字段注释）
package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.NotNull;

/**
 * T-StackHoverUi：调用栈快照串的单个帧（{@code StackDigestFrames.parse} 的产物）。
 *
 * <p>Kotlin {@code internal data class} → Java {@code record}（访问器 {@code fqcn()} /
 * {@code method()} / {@code line()}，record 无 get 前缀，与 data class 组件语义一致）。</p>
 */
public record StackFrameEntry(
        /** 全限定类名（`#` 之前；内嵌类/代理类 `$` 段原样保留）。 */
        @NotNull String fqcn,
        /** 方法名（`#` 之后、行号 `:n` 之前；`lambda$run$0` 等 `$` 段原样保留）。 */
        @NotNull String method,
        /** 源码行号（agent 侧语义：负数不写 `:n`，此处 0 = 未知）。 */
        int line) {
}
