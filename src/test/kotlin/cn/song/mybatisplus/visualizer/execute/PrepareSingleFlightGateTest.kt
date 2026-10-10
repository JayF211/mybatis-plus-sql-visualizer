package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-FirstExecResultInvisible 单测（纯逻辑 + 接线守卫，计划 §四 S3）。
 *
 * 三组覆盖：
 *  1. [PrepareSingleFlightGate] 单飞 + 排队（F1）：在飞拒绝第二链、排队意图一次性消费、
 *     预览直通不取所有权、卡死逃生；
 *  2. [ConsoleEmbedMode] 折叠判定（F3）：EXECUTE 恒展开，PREVIEW 仅结果区无内容时折叠；
 *  3. 接线守卫（源码扫描，照 [ErrorPositionWiringGuardTest] 先例）：面板 8 个 embedConsole
 *     调用点模式显式化、DS 两处折叠点走 shouldCollapseDivider、F1 消费点接线在位。
 *
 * 与 UI/DBN 链无耦合（gate/枚举纯 JDK），默认 test 任务可跑（chooseAndPrepare/
 * executeOnConsole 不可达同款口径，见 MysqlClientGSuffixSubmitChainTest 头注）。
 */
class PrepareSingleFlightGateTest {

    // ------------------------------------------------------------------
    // 第一组：F1 单飞 + 排队
    // ------------------------------------------------------------------

    private fun intent(tag: String = "i") = QueuedExecuteIntent(
        onConsole = null,
        onEditor = null,
        onError = { msg -> "[$tag] $msg" },
        onErrorRich = null,
        wholeScriptExec = false,
    )

    @Test
    fun `单飞_空闲时链持有门`() {
        val gate = PrepareSingleFlightGate()
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = false, nowMs = 0))
        gate.finishPrepare()
        assertTrue("复位后门应空闲", !gate.prepareInFlight.get())
    }

    @Test
    fun `单飞_在飞时执行意图排队不再进第二链`() {
        val gate = PrepareSingleFlightGate()
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = false, nowMs = 0))
        // 准备链（预览）在飞期间用户点「执行 SQL」→ 只排队，不建第二条临时文件/会话/控制台
        val queued = intent("exec")
        val decision = gate.tryEnter(executeAfterPrepare = true, intent = queued, nowMs = 10)
        assertEquals(PrepareGateDecision.QUEUE_ONLY, decision)
        assertTrue("排队标记应在位", gate.pendingExecute.get())
        // 完成准备时消费：拿到的是排队时那个意图，且一次性
        assertSame(queued, gate.consumePendingExecute())
        assertNull("意图只能消费一次", gate.consumePendingExecute())
        assertFalse("消费后标记应复位", gate.pendingExecute.get())
    }

    @Test
    fun `排队_同窗口连点后到意图覆盖先到`() {
        val gate = PrepareSingleFlightGate()
        gate.tryEnter(executeAfterPrepare = false, nowMs = 0)
        gate.tryEnter(executeAfterPrepare = true, intent = intent("first"), nowMs = 10)
        val second = intent("second")
        assertEquals(PrepareGateDecision.QUEUE_ONLY, gate.tryEnter(executeAfterPrepare = true, intent = second, nowMs = 20))
        assertSame("最新用户意图优先", second, gate.consumePendingExecute())
    }

    @Test
    fun `预览在飞时直通不排队不取所有权`() {
        val gate = PrepareSingleFlightGate()
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = false, nowMs = 0))
        // 另一条预览准备到达：PASS_THROUGH——不拦截（拦截会丢切换预览条目的 SQL 同步）、
        // 不取所有权、不置排队
        assertEquals(PrepareGateDecision.PASS_THROUGH, gate.tryEnter(executeAfterPrepare = false, nowMs = 10))
        assertFalse(gate.pendingExecute.get())
        assertNull(gate.consumePendingExecute())
        // 直通链不得动门：finishPrepare 只归持有链（此处模拟持有链仍在飞）
        assertTrue(gate.prepareInFlight.get())
    }

    @Test
    fun `finishPrepare_复位后新链可重新持有`() {
        val gate = PrepareSingleFlightGate()
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = true, nowMs = 0))
        gate.finishPrepare()
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = true, intent = intent("next"), nowMs = 10))
    }

    @Test
    fun `卡死逃生_悬挂超窗后放行新链并清陈旧排队`() {
        val gate = PrepareSingleFlightGate()
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = false, nowMs = 0))
        gate.tryEnter(executeAfterPrepare = true, intent = intent("stale"), nowMs = 10)
        // 门持有链消亡未复位（runner 失败无 session 回调族）——窗口过后新执行链接管自愈
        val takeover = intent("fresh")
        assertEquals(
            PrepareGateDecision.PROCEED,
            gate.tryEnter(executeAfterPrepare = true, intent = takeover, nowMs = PrepareSingleFlightGate.STUCK_ESCAPE_MS + 1),
        )
        assertNull("陈旧排队意图应被清空（旧链已死，消费无意义）", gate.consumePendingExecute())
        // 接管后终结复位照常
        gate.finishPrepare()
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = false, nowMs = PrepareSingleFlightGate.STUCK_ESCAPE_MS + 2))
    }

    // ------------------------------------------------------------------
    // 第二组：F3 折叠判定
    // ------------------------------------------------------------------

    @Test
    fun `embedMode_EXECUTE_恒展开`() {
        assertFalse(ConsoleEmbedMode.EXECUTE.shouldCollapseDivider(resultAreaHasContent = false))
        assertFalse(ConsoleEmbedMode.EXECUTE.shouldCollapseDivider(resultAreaHasContent = true))
    }

    @Test
    fun `embedMode_PREVIEW_无内容折叠`() {
        assertTrue(ConsoleEmbedMode.PREVIEW.shouldCollapseDivider(resultAreaHasContent = false))
    }

    @Test
    fun `embedMode_PREVIEW_有内容不折叠`() {
        // 竞态窗口（执行中快速切换预览条目）内结果已就位 → 不吞用户可见的结果
        assertFalse(ConsoleEmbedMode.PREVIEW.shouldCollapseDivider(resultAreaHasContent = true))
    }

    // ------------------------------------------------------------------
    // 第三组：接线守卫（源码扫描——「实现有、接线漏」单测照不出来，照
    // ErrorPositionWiringGuardTest 先例钉生产调用点）
    // ------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun sourceOf(relativePath: String): String =
        File(repoRoot(), relativePath).readText()

    /** T-M53-II（2026-10-10）：组合式重构后 DataSourceSelector 的成员分散到同包三个文件，
     * 形态类断言必须按**全集**读取（只读主文件会因成员搬走而假红，或漏掉真实违规而假绿）。 */
    private fun selectorUnion(): String = listOf(
        "DataSourceSelector.kt",
        "DataSourceSelectorConsole.kt",
        "DataSourceSelectorResults.kt",
    ).joinToString("\n") {
        sourceOf("src/main/kotlin/cn/song/mybatisplus/visualizer/execute/$it")
    }

    /** T-M53-II D2-5：嵌入调用点随执行链搬到同包协作文件，读取面取面板全体源文件并集。 */
    private fun panelUnion(): String {
        val dir = java.io.File(
            java.io.File(System.getProperty("user.dir")).absoluteFile.let { d ->
                var x: java.io.File? = d
                while (x != null && !java.io.File(x, "src/main/kotlin").isDirectory) x = x.parentFile
                x ?: error("未定位到仓库根")
            },
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui",
        )
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("SqlPreviewPanel") && it.extension == "kt" }
            .sortedBy { it.name }
            .joinToString("\n") { it.readText() }
    }

    @Test
    fun `接线_全链执行embed调用点全部显式EXECUTE`() {
        val panel = panelUnion()
        // 五条全链执行链（executeSql 两处 / executeAdhocSql 两处 / EXPLAIN 直送一处）
        assertEquals(
            5,
            "embedConsole(consoleComponent, embedMode = ConsoleEmbedMode.EXECUTE)".let { needle -> panel.split(needle).size - 1 },
        )
        // executePrepared 的 onResultsReady 两处（executeSql + executeAdhocSql）
        assertEquals(
            2,
            panel.split("embedConsole(fullConsole, embedMode = ConsoleEmbedMode.EXECUTE)").size - 1,
        )
        // 预览准备链唯一入口（prepareDefaultConsole onConsole）
        assertEquals(
            1,
            panel.split("embedConsole(consoleComponent, embedMode = ConsoleEmbedMode.PREVIEW)").size - 1,
        )
        // 旧布尔签名彻底退役（注释除外——源码形态扫描只认实参写法）
        assertFalse(panel.contains("embedConsole(consoleComponent, previewMode = true)"))
        assertFalse(panel.contains("embedConsole(consoleComponent)"))
    }

    @Test
    fun `接线_DS侧折叠点归口shouldCollapseDivider`() {
        val selector = selectorUnion()
        // prepareAfterEditorResolved（Console 侧）+ reuseSharedConsole（Results 侧）两处折叠判定
        assertEquals(
            2,
            selector.split("shouldCollapseDivider(").size - 1,
        )
    }

    @Test
    fun `接线_F1单飞入口与消费点在位`() {
        val selector = selectorUnion()
        // 入口单飞判定（先于共享复用）+ QUEUE_ONLY 只排队 callback(true)
        assertTrue(selector.contains("prepareGate.tryEnter(executeAfterPrepare, queuedIntent)"))
        assertTrue(selector.contains("PrepareGateDecision.QUEUE_ONLY ->"))
        // 消费点：完成准备后按 executePrepared 口径消费（onResultsReady=意图 onConsole）
        assertTrue(selector.contains("queuedIntent = prepareGate.consumePendingExecute()"))
        assertTrue(selector.contains("onResultsReady = queuedIntent?.onConsole"))
        // 单飞位复位兜底（成功/失败同尽）
        assertTrue(selector.contains("if (ownsPrepareGate) prepareGate.finishPrepare()"))
        // 卡死逃生在 tryEnter 内（门自愈，不在调用方）
        val gate = sourceOf("src/main/kotlin/cn/song/mybatisplus/visualizer/execute/PrepareSingleFlightGate.kt")
        assertTrue(gate.contains("STUCK_ESCAPE_MS"))
    }
}
