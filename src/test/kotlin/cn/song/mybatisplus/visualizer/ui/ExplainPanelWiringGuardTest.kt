package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R33 EXPLAIN 结构化通道**接线守卫**（源码扫描，照 McpErrorPositionTextTest 先例）。
 *
 * 只钉纯判定（ExplainPanelChannelTest）不钉接线的话，把 `SqlPreviewPanel.explainSql` 里的
 * 分派调用删了判定就是死代码（空绿）——本守卫钉四件事：
 *  1. explainSql 真的经 `ExplainPanelChannel.decide` 分派；
 *  2. 直送通道收敛为 `submitExplainDirect` 单函数且**至少两处复用**（初始回退 + 回到原生
 *     控制台入口——双通道并存的守恒判据：结构化通道绝不成为唯一路径）；
 *  3. 结构化视图经 `embedExplainView` 换入；
 *  4. 索引建议真的接入面板侧消费（ExplainIndexAdvisor.advise 在 SqlPreviewPanel 有调用点）。
 */
class ExplainPanelWiringGuardTest {

    @Test
    fun `面板explainSql经过通道判定`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "SqlPreviewPanel.explainSql 必须经 ExplainPanelChannel.decide 分派（否则判定成死代码）",
            source.contains("ExplainPanelChannel.decide"),
        )
    }

    @Test
    fun `直送通道收敛单函数且回退与入口两处复用`() {
        val source = sqlPreviewPanelSource()
        val occurrences = Regex("submitExplainDirect\\(").findAll(source).count()
        assertTrue(
            "submitExplainDirect 必须收敛直送链并被回退+回到控制台入口两处复用（实际 $occurrences 处）",
            occurrences >= 3, // 定义 1 + 调用 ≥ 2
        )
        assertTrue(
            "直送链必须保留既有 SqlExecutor.execute 行为（双通道并存，不替换）",
            source.contains("SqlExecutor.execute"),
        )
    }

    @Test
    fun `结构化视图经embedExplainView换入`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "结构化渲染结果必须经 embedExplainView 换入结果区",
            source.contains("embedExplainView"),
        )
    }

    @Test
    fun `索引建议接入面板侧消费`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "ExplainIndexAdvisor.advise 必须在面板侧有消费点（联动建议条）",
            source.contains("ExplainIndexAdvisor.advise"),
        )
    }

    /**
     * R33 真机走查修复接线钉：结构化探测前必须做未回填 `?` 占位探测并显式传给判定——
     * `hasUnfilledParams` 是带默认值的参数（漏传编译不红），删掉这行接线判定层就是死参数，
     * 语法错场景会重新滑进结构化探测（真机 `SQLException: … SQL syntax` 复现）。
     */
    @Test
    fun `结构化探测前钉占位回填检查`() {
        val source = sqlPreviewPanelSource()
        // k2j：Java 门面无具名实参——占位探测以局部变量承载（master：
        // hasUnfilledParams = placeholderPositions(explainSql).isNotEmpty()），decide 调用按位置传参
        assertTrue(
            "submitExplainOrStructured 必须用串感知占位探测喂 hasUnfilledParams（否则残留 ? 重新下发探测）",
            source.contains("hasUnfilledParams = !PlaceholderPositionsKt.placeholderPositions(explainSql).isEmpty()") &&
                source.contains("hasUnfilledParams);"),
        )
    }

    /**
     * T-M53-II D2-5：面板的组合式重构把 EXPLAIN 分派链（explainSql / submitExplainDirect /
     * submitExplainOrStructured / embedExplainView）逐字符搬出到同包协作文件，故读取面取
     * **面板全体源文件**的并集——只读主文件会在成员搬走后变成空扫描（断言假红或假绿的同一根因）。
     *
     * k2j 适配：生产树是 src/main/java 下的 .java 文件（master 侧为 src/main/kotlin 下的 .kt），
     * 且 `hasUnfilledParams` 是局部变量而非具名实参（Java 无具名实参），分派调用形如
     * `ExplainPanelChannel.decide(... hasUnfilledParams)`。
     */
    private fun sqlPreviewPanelSource(): String =
        panelSourceFiles().joinToString("\n") { File(repoRoot(), it).readText() }

    private fun panelSourceFiles(): List<String> =
        File(repoRoot(), "src/main/java/cn/song/mybatisplus/visualizer/ui").listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("SqlPreviewPanel") && it.extension == "java" }
            .map { "src/main/java/cn/song/mybatisplus/visualizer/ui/${it.name}" }
            .sorted()

    /** 仓库根定位（同 McpErrorPositionTextTest：从 user.dir 上溯到含 src/main/java 的目录）。 */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }
}
