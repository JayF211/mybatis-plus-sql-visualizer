package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertFalse
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
 *  3. 结构化视图经非模态对话框展示（ExplainPlanDialog/showExplainDialog），不替换编辑器；
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
    fun `结构化视图经非模态对话框展示不替换编辑器`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "结构化渲染结果必须经 ExplainPlanDialog/showExplainDialog 展示",
            source.contains("ExplainPlanDialog(") && source.contains("showExplainDialog("),
        )
        assertFalse(
            "结构化路径不得再调用 embedExplainView 替换预览编辑器",
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
        assertTrue(
            "submitExplainOrStructured 必须用串感知占位探测喂 hasUnfilledParams（否则残留 ? 重新下发探测）",
            source.contains("hasUnfilledParams = placeholderPositions(explainSql).isNotEmpty()") &&
                source.contains("hasUnfilledParams = hasUnfilledParams,"),
        )
    }

    /**
     * T-M53-II D2-5：面板的组合式重构把 EXPLAIN 分派链（explainSql / submitExplainDirect /
     * submitExplainOrStructured / embedExplainView）逐字符搬出到同包协作文件，故读取面取
     * **面板全体源文件**的并集——只读主文件会在成员搬走后变成空扫描（断言假红或假绿的同一根因）。
     * R61 接线钉：跨次对比必须真的在结构化 onPlan 链上——基线存储（lastStructuredExplain）+
     * 对比计算（ExplainPlanDiff.diff）+ 基线滚动更新 + 视图入参（diff = planDiff）四件齐全；
     * 摘掉任一件对比条即静默消失（无编译期保护的 wiring 面，同 hasUnfilledParams 教训）。
     */
    @Test
    fun `R61_跨次对比接线在位`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "结构化 onPlan 必须计算跨次 diff（否则对比逻辑成死代码）",
            source.contains("ExplainPlanDiff.diff("),
        )
        assertTrue(
            "会话级基线必须滚动更新（否则永远比第一次）",
            source.contains("lastStructuredExplain = explainSql to outcome"),
        )
        assertTrue(
            "diff 必须经构造参数进结构化视图（否则对比条永不出现）",
            source.contains("diff = planDiff"),
        )
    }

    @Test
    fun `R90_真实提交捕获上下文且成功回调接持久化与选择器`() {
        val source = sqlPreviewPanelSource()
        val begin = source.indexOf("val historyRequest = captureExplainPlanHistory(")
        val submit = source.indexOf("ExplainPanelChannel.executeStructured(", begin)
        val complete = source.indexOf("completeExplainPlanHistory(history, historyRequest, outcome.raw)", submit)
        assertTrue("上下文必须在提交前固定并由成功回调消费", begin >= 0 && submit > begin && complete > submit)
        assertTrue(source.contains("originalPreview?.historyEntryId.orEmpty()"))
        assertTrue(source.contains("return explainPlanHistorySession.begin("))
        assertTrue(source.contains("DataSourceSettings.getUniqueId(ds)"))
        assertTrue(source.contains("val retentionEnabled = PluginSettings.getInstance().state.explainPlanRetentionEnabled"))
        assertTrue(source.contains("explainPlanHistorySession.complete(history, request, raw, PluginSettings.getInstance().state.explainPlanRetentionEnabled)"))
        assertTrue(source.contains("baselines = historyCompletion.baselines"))
        assertTrue(source.contains("historyStatus = historyCompletion.saved"))
        val service = File(repoRoot(), "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt").readText()
        assertTrue(service.contains("result.historyEntryId = manager.add("))
        assertTrue(service.contains("ExplainPlanKey.projectIdentity(project.basePath)"))
    }

    /**
     * T-UI030：无已绑数据源时**直接弹既有数据源选择器**（prepareConsole，只绑定不执行），
     * 不再只发一条「已回退原生控制台」通知。摘掉这行接线，用户又回到「告知失败、没有下一步」。
     */
    @Test
    fun `T_UI030_无数据源直接走既有选择器且只绑定不执行`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "NO_DATA_SOURCE 必须真的调用 promptDataSourceThenRetryStructured（只定义函数不算接线）",
            source.contains("promptDataSourceThenRetryStructured(proj, dialect, explainSql, dialectGateRequest)"),
        )
        assertTrue(
            "绑定必须复用 DataSourceSelector.prepareConsole（executeAfterPrepare=false：只绑定不执行任何 SQL）",
            source.contains("DataSourceSelector.prepareConsole("),
        )
        assertTrue(
            "重试路径必须显式禁止再弹选择器（防取消→再弹→再取消循环）",
            source.contains("allowDataSourcePrompt = false"),
        )
    }

    /**
     * T-UI030：取消选择的收口走纯判定 GIVE_UP（只给说明、不再弹第二次）。
     * 摘掉这个分支，取消后会被当成「继续等待」或重新拉起选择器。
     */
    @Test
    fun `T_UI030_取消选择由GIVE_UP收口不再弹第二次`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "轮询必须消费 dataSourceBindTick 的 GIVE_UP 分支",
            source.contains("DataSourceBindTick.GIVE_UP"),
        )
        assertTrue(
            "GIVE_UP 必须调用取消说明（未执行 + 下一步），而不是再次 prepareConsole",
            source.contains("notifyExplainDataSourceNotBound("),
        )
        val guidance = File(
            repoRoot(),
            "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/ExplainFallbackGuidance.kt",
        ).readText()
        assertTrue(
            "准备链终结仍未绑定必须判 GIVE_UP（取消即收口）",
            guidance.contains("!prepareInFlight -> DataSourceBindTick.GIVE_UP"),
        )
    }

    /**
     * T-UI030：开关关闭时通知必须带「启用结构化并重试」动作，且只在方言确有结构化面时出现。
     */
    @Test
    fun `T_UI030_开关关闭通知带启用并重试动作`() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "开关关闭时必须经纯判定决定是否提示（TEXT/XML/Community 不噪音）",
            source.contains("shouldOfferStructuredEnable("),
        )
        assertTrue(
            "通知必须真的 addAction（不是只有一段说明文字）",
            source.contains("notification.addAction(") &&
                source.contains("panel.explain.structured.enableAndRetry"),
        )
        assertTrue(
            "动作清单必须由 structuredExplainDisabledActions 生成（空表/死循环不算挂上动作）",
            source.contains("structuredExplainDisabledActions(onEnableAndRetry)"),
        )
        assertTrue(
            "动作回调必须写开关并重试（enableStructuredExplainAndRetry）",
            source.contains("enableStructuredExplainAndRetry(") &&
                source.contains("state.explainStructuredRender = true"),
        )
    }

    private fun sqlPreviewPanelSource(): String =
        panelSourceFiles().joinToString("\n") { File(repoRoot(), it).readText() }

    private fun panelSourceFiles(): List<String> =
        File(repoRoot(), "src/main/kotlin/cn/song/mybatisplus/visualizer/ui").listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("SqlPreviewPanel") && it.extension == "kt" }
            .map { "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/${it.name}" }
            .sorted()

    /** 仓库根定位（同 McpErrorPositionTextTest：从 user.dir 上溯到含 src/main/kotlin 的目录）。 */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }
}
