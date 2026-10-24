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
            "submitExplainDirect 的取源须接 explainFallbackDataSource(proj, sourceModuleKey, sql) 模块学习回落（W9）",
            directIdx >= 0 && fallbackIdx > directIdx,
        )
        assertTrue(
            "回落必须先于「未绑定数据源」红字分支（否则回落不生效）",
            noDsIdx < 0 || fallbackIdx in 0 until noDsIdx,
        )
    }

    @Test
    fun `回落取源必须做表归属路由`() {
        val resolver = sourceOf("ui/highlight/DatabaseSchemaResolver.kt")
        val selector = sourceOf("execute/DataSourceSelector.kt")
        val chain = sourceOf("ui/SqlPreviewPanelExecution.kt")
        // T-DialectInferPollution W10（用户沙盒实测两连错源：wms_inventory_jar_demo/order_entity
        // 被 last-used/学习源硬套）：模块学习与 last-used 证据都可能陈旧，而「表在哪个库」是更强
        // 的归属事实。resolver 须提供只读缓存的表归属路由入口；取源函数（DataSourceSelector 侧）
        // 须接线（候选源不持有表而唯一其他源持有时自动路由；多源/零源不猜）；两条 EXPLAIN 链须
        // 传预览 SQL。
        assertTrue(
            "DatabaseSchemaResolver 须有 cachedOwnerIndexes/ownerIndexesForSources 表归属路由入口（W10）",
            resolver.contains("cachedOwnerIndexes") && resolver.contains("ownerIndexesForSources"),
        )
        assertTrue(
            "explainFallbackDataSource 须接线表归属路由（cachedOwnerIndexes，W10）",
            selector.contains("cachedOwnerIndexes"),
        )
        assertTrue(
            "表归属路由须传预览 SQL（否则无表可验证）",
            Regex("explainFallbackDataSource\\(\\s*proj,\\s*sourceModuleKey,\\s*(sql|explainSql)").containsMatchIn(chain),
        )
    }

    @Test
    fun `排队执行意图必须有自有有界结算`() {
        val gate = sourceOf("execute/PrepareSingleFlightGate.kt")
        val selectorResults = sourceOf("execute/DataSourceSelectorResults.kt")
        val selector = sourceOf("execute/DataSourceSelector.kt")
        // T-DialectInferPollution W11（用户真机「点执行 SQL 卡住」）：QUEUE_ONLY 排队意图原本
        // 只能等 owner 60s 逃生被下一链接管，排队者自身无触发点 → UI 永远「执行中…」。排队意图
        // 须有自己的超时结算（expirePendingExecute，门与 owner 不动）。
        assertTrue(
            "PrepareSingleFlightGate 须有 expirePendingExecute 排队自有结算（W11）",
            gate.contains("expirePendingExecute"),
        )
        assertTrue(
            "chooseAndPrepare（DataSourceSelectorResults）的 QUEUE_ONLY 分支须起排队超时结算 Timer（W11）",
            selectorResults.contains("PENDING_EXECUTE_TIMEOUT_MS") &&
                selectorResults.contains("expirePendingExecute"),
        )
        assertTrue(
            "排队超时常量与文案须定义在 DataSourceSelector（W11）",
            selector.contains("PENDING_EXECUTE_TIMEOUT_MS = "),
        )
    }

    @Test
    fun `缓存热但表不在须引导选择而非错源执行`() {
        val code = sourceOf("execute/DataSourceSelector.kt")
        // T-DialectInferPollution W12（用户沙盒实测）：候选源不持有预览的表且缓存热（有源已
        // 内省出表）→ 表不存在于任何已连库，错源执行必败——须返回 null 引导选择数据源；
        // 缓存全冷（无法证明）才保持候选。
        assertTrue(
            "须有 cacheHasAnyTable 缓存热度判定（W12）",
            code.contains("cacheHasAnyTable"),
        )
        val hotIdx = code.indexOf("cacheHasAnyTable(project)")
        val nullReturn = code.indexOf("return null", hotIdx)
        assertTrue(
            "缓存热零命中须 return null 引导选择（W12）",
            hotIdx >= 0 && nullReturn in 0 until (hotIdx + 400),
        )
    }

    @Test
    fun `选错源失败不得弹红字模态框`() {
        val code = sourceOf("ui/SqlPreviewPanelExecution.kt")
        // T-ExplainFailUx W1（用户沙盒实测诉求：「这种情况就不应该弹窗了吧，应该直接引导用户
        // 更换数据源」）：WRONG_TARGET 档（表不在当前源）须走就地引导 showExplainWrongTarget，
        // 不得调 showExplainErrorDialog 弹模态。按分支起点切片断言（同 W6 AUTH 纪律）。
        val starts = Regex("ExplainFailureKind\\.WRONG_TARGET ->").findAll(code).map { it.range.first }.toList()
        assertTrue("应存在 WRONG_TARGET 分支（结构化 onFailed 与直送 Failed 两处）", starts.size >= 2)
        val branchEnds = Regex("ExplainFailureKind\\.(CANCELLED|OTHER) ->|is PreviewSideQuery\\.Result\\.Scalar")
            .findAll(code).map { it.range.first }.toList()
        val offenders = starts.count { s ->
            val end = branchEnds.firstOrNull { it > s } ?: code.length
            code.substring(s, end).contains("showExplainErrorDialog")
        }
        assertTrue(
            "WRONG_TARGET 分支内不得调 showExplainErrorDialog（选错源须就地引导换源，W1）：命中 $offenders 处",
            offenders == 0,
        )
        assertTrue(
            "WRONG_TARGET 就地引导出口 showExplainWrongTarget 必须存在且接原生换源入口（W1+W5/W6）",
            code.contains("showExplainWrongTarget") && code.contains("switchDataSourceViaNativeChooser"),
        )
    }

    @Test
    fun `换源必须弹原生选择器且不自动重试`() {
        val chain = sourceOf("ui/SqlPreviewPanelExecution.kt")
        val results = sourceOf("execute/DataSourceSelectorResults.kt")
        val panel = sourceOf("ui/SqlPreviewPanelParamPanel.kt")
        // T-ExplainSwitchNative W5/W6（用户实测三问题）：①点「切换数据源」没弹列表却直接重试
        // ——根因 forceChoose 只跳过 last-used 静默，runner 唯一时仍直选；②自研下拉只改显示、
        // 执行仍用原生控制台源；③理想形态是 IDE 原生 Data Sources 弹窗。
        // 纪律：换源统一走 switchDataSourceViaNativeChooser（forceChoosePopup=true 必弹），
        // **不自动重试**；自研下拉整体退役。
        assertTrue(
            "换源入口须存在且走 forceChoosePopup 必弹选择器（W6）",
            chain.contains("fun SqlPreviewPanel.switchDataSourceViaNativeChooser") &&
                chain.contains("forceChoosePopup = true"),
        )
        assertTrue(
            "换源入口不得再自动重试执行计划（旧 W3 的 awaitDataSourceBoundThenRetry 调用须移除）",
            !chain.substring(
                chain.indexOf("fun SqlPreviewPanel.switchDataSourceViaNativeChooser"),
                (chain.indexOf("fun SqlPreviewPanel.switchDataSourceViaNativeChooser") + 1200)
                    .coerceAtMost(chain.length),
            ).contains("awaitDataSourceBoundThenRetry"),
        )
        assertTrue(
            "chooseAndPrepare 的直选分支须受 forceChoosePopup 抑制（W6）",
            results.contains("shouldBeChosenIfAlone(runners.first()) && !forceChoosePopup"),
        )
        assertTrue(
            "自研数据源下拉必须整体退役（不得再有 buildDataSourceCombo / bindStateCombo）",
            !panel.contains("buildDataSourceCombo") && !panel.contains("bindStateCombo") &&
                !panel.contains("BIND_STATE_COMBO_NAME"),
        )
        assertTrue(
            "指示行换源入口须在插件不可用时降级为不渲染（W2 纪律沿用）",
            panel.contains("ExecuteSqlSupport.isAvailable()"),
        )
        // W7：执行目标单一事实源＝原生控制台——换源前必须丢弃旧共享控制台/已备会话，
        // 否则 reuseSharedConsole 命中旧源控制台（attach 源不跟随新选择）→ 图二错位。
        val swBody = chain.substring(
            chain.indexOf("fun SqlPreviewPanel.switchDataSourceViaNativeChooser"),
            (chain.indexOf("fun SqlPreviewPanel.switchDataSourceViaNativeChooser") + 2000)
                .coerceAtMost(chain.length),
        )
        assertTrue(
            "换源前须 dropSharedConsole + clearPrepared（W7 单一事实源）",
            swBody.contains("dropSharedConsole()") && swBody.contains("clearPrepared()"),
        )
    }

    @Test
    fun `渲染方言缺失时不得判成未知阻断执行`() {
        val code = sourceOf("ui/SqlPreviewPanelExecution.kt")
        // T-ExplainSwitchNative W10（用户实测「为什么 sql 无法执行」）：effectiveDialect 为 null
        // 只发生在「渲染链没记方言」的载体，而渲染器 byName(null) 的既有语义是回落 MySQL
        // （Dialect.kt:908-909）——闸门请求侧必须同口径回落，否则 RENDER_UNKNOWN_BLOCK 会阻断执行。
        val idx = code.indexOf("renderedDialectName = result?.effectiveDialect?.name")
        assertTrue("闸门请求须含 renderedDialectName 赋值（W10）", idx >= 0)
        val slice = code.substring(idx, (idx + 260).coerceAtMost(code.length))
        assertTrue(
            "renderedDialectName 为 null 时须回落 MySqlDialect.name（W10）",
            slice.contains("?: cn.song.mybatisplus.visualizer.sql.MySqlDialect.name"),
        )
    }

    @Test
    fun `方言闸红条的切换数据源不得走执行链`() {
        val code = sourceOf("ui/SqlPreviewPanelExecution.kt")
        // T-ExplainSwitchNative W9（用户实测「点击下方的切换数据源依然有问题，点击没有反应」）：
        // 红条按钮此前接 executeSql(forceChoose=true)——执行链会先过同一方言闸门、再次被同一
        // 原因阻断，表现为「点了没反应」。切换只换源，统一走原生选择器入口。
        val gateIdx = code.indexOf("internal fun SqlPreviewPanel.showDialectGateError")
        assertTrue("须有方言闸红条入口（W9）", gateIdx >= 0)
        val body = code.substring(gateIdx, (gateIdx + 2000).coerceAtMost(code.length))
        assertTrue(
            "红条「切换数据源」须走 switchDataSourceViaNativeChooser（W9）",
            body.contains("fix = { switchDataSourceViaNativeChooser() }"),
        )
        assertTrue(
            "红条「切换数据源」不得再走 executeSql 执行链（W9）",
            !body.contains("fix = { executeSql(forceChoose = true) }"),
        )
    }

    @Test
    fun `切换枚举必须回退attach且不得返回空`() {
        val results = sourceOf("execute/DataSourceSelectorResults.kt")
        val selector = sourceOf("execute/DataSourceSelector.kt")
        // T-ExplainSwitchNative W8-hotfix（用户实测「点击切换数据源就报错」）：首版把切换入口
        // 改成反射 getSwitchDataSourceRunners，但其真实签名是四参（含 DatabaseSession），而
        // 准备链此刻尚无已 attach 会话 → NoSuchMethodException 被吞成空列表 → runners=0 →
        // 「没有与该 SQL 方言匹配的已配置数据源」红条。纪律：准备链枚举一律走 attach 版
        // （此前一直正常、能弹原生列表的形态）；即便将来启用 switch 版，**失败必须回退 attach**。
        assertTrue(
            "准备链枚举须直接走 attach 版（W8-hotfix）",
            results.contains("getAttachDataSourceRunners(psiFile, submitSql, sessionConsumer)"),
        )
        assertTrue(
            "枚举实现须保留 getSwitchDataSourceRunners 的真实四参签名并失败回退 attach（W8-hotfix）",
            selector.contains("Class.forName(SESSION_CLASS)") &&
                selector.contains("switched.ifEmpty { attach() }"),
        )
    }

}
