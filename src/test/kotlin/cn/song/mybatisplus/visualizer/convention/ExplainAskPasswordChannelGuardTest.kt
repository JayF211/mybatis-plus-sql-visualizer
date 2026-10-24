package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ExplainPwdPrompt W3 守卫（2026-10-24）：`McpDbExecutor.executeQuery` 的 `askPassword`
 * 通道表态对账。
 *
 * 背景：`askPassword` 是**必填**参数（编译期强迫每个调用点表态），但编译期挡不住「显式传错」——
 * 面板 UI 通道（结构化执行计划/预估行数）若被改回 false，数据源未存密码时退回「红字不弹框」
 * 缺陷（沙盒实测 2026-10-23）；MCP 工具通道若被改成 true，程序性调用弹模态密码框即挂死
 * （零弹窗红线）。本守卫把三个调用点的表态钉死在源码上，反向：改掉任一表态必红。
 */
class ExplainAskPasswordChannelGuardTest : BannedApiScanSupport() {

    private fun sourceOf(suffix: String): String =
        mainSources().firstOrNull { it.first.endsWith(suffix) }?.second
            ?: error("源文件不在快照中：$suffix")

    @Test
    fun `面板执行计划结构化通道必须允许询问密码`() {
        val code = sourceOf("execute/ExplainPanelChannel.kt")
        assertTrue(
            "ExplainPanelChannel 的 McpDbExecutor.executeQuery 须带 askPassword = true" +
                "（UI 上下文：数据源未存密码时交平台原生密码框，T-ExplainPwdPrompt）",
            code.contains("askPassword = true"),
        )
    }

    @Test
    fun `预估行数与EXPLAIN直送旁路必须允许询问密码`() {
        val code = sourceOf("ui/PreviewSideQuery.kt")
        assertTrue(
            "PreviewSideQuery 的 McpDbExecutor.executeQuery 须带 askPassword = true" +
                "（UI 上下文，T-ExplainPwdPrompt）",
            code.contains("askPassword = true"),
        )
    }

    @Test
    fun `MCP工具通道必须保持零弹窗`() {
        val code = sourceOf("mcp/SqlVisualizerExplainTool.kt")
        assertTrue(
            "SqlVisualizerExplainTool 的 McpDbExecutor.executeQuery 必须显式 askPassword = false" +
                "（MCP 零弹窗红线：程序性调用弹模态密码框即挂死，T-ExplainPwdPrompt）",
            code.contains("askPassword = false"),
        )
    }

    @Test
    fun `提交链必须带模块学习回落取源`() {
        val code = sourceOf("ui/SqlPreviewPanelExecution.kt")
        assertTrue(
            "submitExplainOrStructured 必须接 explainFallbackDataSource(proj, sourceModuleKey) 回落" +
                "（绑定链 runner 枚举被方言路由卡死时，结构化通道靠模块学习/last-used 记忆取源到达，" +
                "T-ExplainPwdPrompt W4 + T-DialectInferPollution W9 模块归属修正）",
            code.contains("explainFallbackDataSource(\n                proj, sourceModuleKey,"),
        )
    }

    @Test
    fun `参数未回填不得回退直送`() {
        val code = sourceOf("ui/SqlPreviewPanelExecution.kt")
        // DirectFallback(UNFILLED_PARAMS) 分支必须先于「其余回退直送」的 else 被单独拦下：
        // 回退直送在无绑定形态必失败且弹误导性「未绑定数据源」红字（沙盒实测 2026-10-24）。
        val unfilledIdx = code.indexOf("FallbackReason.UNFILLED_PARAMS")
        val elseIdx = code.indexOf("notifyExplainStructuredFallback(proj, verdict.reason, detail = null)\n                submitExplainDirect")
        assertTrue(
            "submitExplainOrStructured 须有 UNFILLED_PARAMS 专用分支（不回退直送，T-ExplainPwdPrompt W5）",
            unfilledIdx >= 0,
        )
        assertTrue(
            "UNFILLED_PARAMS 分支必须出现在回退直送的 else 之前（否则未回填参数仍会弹误导红字）",
            elseIdx < 0 || unfilledIdx in 0 until elseIdx,
        )
    }

    @Test
    fun `认证失败不得弹红字模态框`() {
        val code = sourceOf("ui/SqlPreviewPanelExecution.kt")
        // AUTH 出口走轻通知（notifyExplainAuthFailure）——平台凭据对话框已呈现失败与出口，
        // Abort 后再弹红字框是二次打断（用户报障 2026-10-24：「点终止还是出现执行计划的弹框」）。
        // 提取全部 AUTH 分支体（结构化 onFailed 带花括号、直送 Failed 无花括号两种形态）：
        // 按分支起点切片到下一个 when 分支起点，逐一断言切片内不得出现 showExplainErrorDialog。
        // （首版 indexOf 近邻检查被直送链调用点干扰假绿、反向探针不红；第二版只匹配带花括号
        // 形态漏掉直送分支——探针必须让守卫真红才算验证过。）
        val starts = Regex("ExplainFailureKind\\.AUTH ->").findAll(code).map { it.range.first }.toList()
        assertTrue(
            "应存在 AUTH 分支（结构化 onFailed 与直送 Failed 两处）",
            starts.size >= 2,
        )
        val branchEnds = Regex("ExplainFailureKind\\.(CANCELLED|OTHER) ->|is PreviewSideQuery\\.Result\\.Scalar")
            .findAll(code).map { it.range.first }.toList()
        val offenders = starts.count { s ->
            val end = branchEnds.firstOrNull { it > s } ?: code.length
            code.substring(s, end).contains("showExplainErrorDialog")
        }
        assertTrue(
            "AUTH 分支内不得调 showExplainErrorDialog（红字模态框=二次打断，须走轻通知）：命中 $offenders 处",
            offenders == 0,
        )
        assertTrue(
            "AUTH 轻通知出口 notifyExplainAuthFailure 必须存在",
            code.contains("notifyExplainAuthFailure(proj)"),
        )
    }

    @Test
    fun `EXPLAIN闸门重渲染后必须自动重提交一次`() {
        val code = sourceOf("ui/SqlPreviewPanelExecution.kt")
        // T-DialectInferPollution W7（用户真机报障 2026-10-24）：渲染方言与目标数据源不一致
        // （last-used 被竞品测试数据源 dbx_test/PG 残留占据，MySQL 预览按 PG 渲染）时，闸门
        // AUTO_RERENDER_REQUIRED 重渲染成功后若断链让用户重按「执行计划」，第二次点击在开关
        // 关闭形态还会落进直送链弹「未绑定数据源」红字——重渲染成功必须自动重提交一次
        // （rerendered 参数防循环），重渲染失败/已重渲染过才回落红条。
        val gateIdx = code.indexOf("Status.AUTO_RERENDER_REQUIRED &&")
        val notRerendered = code.contains("!rerendered &&")
        val recursion = code.contains("allowDataSourcePrompt = false, rerendered = true")
        val param = code.contains("rerendered: Boolean = false")
        assertTrue(
            "submitExplainOrStructured 的闸门回调须有 AUTO_RERENDER_REQUIRED 自动重提交分支（W7）",
            gateIdx >= 0 && notRerendered,
        )
        assertTrue(
            "submitExplainOrStructured 须带 rerendered 参数且自动重提交时置位（防闸门循环）",
            param && recursion,
        )
    }

    @Test
    fun `直送链取源必须接模块学习回落`() {
        val code = sourceOf("ui/SqlPreviewPanelExecution.kt")
        // T-DialectInferPollution W9（用户沙盒实测：jar-model demo 表被 last-used mp_demo 硬套
        // → mp_demo.wms_inventory_jar_demo doesn't exist）：直送链与结构化链取源统一走
        // explainFallbackDataSource——模块学习优先、冲突不猜、last-used 兜底。
        val directIdx = code.indexOf("val target = explainTargetForTest?.invoke()")
        val fallbackIdx = code.indexOf("explainFallbackDataSource(\n                proj, sourceModuleKey,", directIdx)
        val noDsIdx = code.indexOf("panel.explain.noDataSource", directIdx)
        assertTrue(
            "submitExplainDirect 的取源须接 explainFallbackDataSource(proj, sourceModuleKey) 模块学习回落（W9）",
            directIdx >= 0 && fallbackIdx > directIdx,
        )
        assertTrue(
            "回落必须先于「未绑定数据源」红字分支（否则回落不生效）",
            noDsIdx < 0 || fallbackIdx in 0 until noDsIdx,
        )
    }
}
