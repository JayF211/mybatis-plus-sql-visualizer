// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerSchemaContextTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

/**
 * P3 / T-McpArgLooseDecode：容错入参共同面。
 *
 * <p>{@link #getKind()} 供校验层判形态，{@link #getText()} 为原始文本（形态不符时仅用于报错）。</p>
 *
 * <p>移植注记：master 侧为同文件顶层 {@code interface McpArg { val kind; val text }}——Kotlin 属性
 * 在 Java 侧展开为 {@code getKind()} / {@code getText()}。</p>
 */
public interface McpArg {

    McpArgKind getKind();

    String getText();
}
