package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-FixN1（N1）：**预览装配回调的写安全**运行期断言。
 *
 * 缺陷形态（真机必现，第二轮复核 `a617228` 确认）：`SqlPreviewPanel.prewarmPreviewTempFile()` 给
 * [PrewarmAwait] 传的派发通道是 `invokeLater(runnable, ModalityState.any())`，而回调体
 * （`resolvePreviewEditorSource` 的 onReady → `assemblePreviewEditor`）**写文档**
 * （`WriteCommandAction { d.setText(sql) }`）。`any()` 恒 write-unsafe——平台
 * `TransactionGuardImpl.wrapLaterInvocation` 只对 `isWriteSafeModality(state)` 为真的 runnable 套
 * `runWithWritingAllowed`（`myWriteSafeModalities` 构造期只预置 `nonModal()→true`），any() 走 else
 * 分支直接 run，`myWritingAllowed` 保持 false；`DocumentImpl.assertWriteAccess` →
 * `assertWriteActionAllowed` 随即 `LOG.error("Write-unsafe context! …")`（真机 SEVERE + Suppressed 计数）。
 *
 * **为什么必须有本用例**：`convention/BannedApiSourceScanTest` 的两条写安全规则是**词法**的——
 * 它扫 `invokeLater` 站点的实参区与尾随 lambda 体，而本缺陷的写入在回调**间接调用**的方法里
 * （回调 → `assemblePreviewEditor` → … → `setText`），静态扫描看不到（该站点的 body 只有
 * `(runnable, ModalityState…)`，无任何写入令牌，两条规则都判绿）。运行期断言是本缺陷唯一能收口的护栏。
 *
 * **为什么不能用「平台在测试模式会自动把 ERROR 判失败」这条现成机制**（原计划的假设，243 平台 jar
 * 反编译 + 本文件实跑双重订正）：
 *  - `UsefulTestCase.wrapTestRunnable` 确实把用例体包进
 *    `TestLoggerKt.recordErrorsLoggedInTheCurrentThreadAndReportThemAsFailures`，但它装的 ErrorLog
 *    走的是 **`ThreadContext.withThreadLocal`**（`withErrorLog` 反编译实证），**不是普通 ThreadLocal**；
 *  - 而 `UIUtil.dispatchAllInvocationEvents()`（本仓库测试统一用它抽 EDT 队列）**第一件事就是
 *    `ThreadContext.resetThreadContext()`**（反编译实证：reset → dispatch → token.close）——
 *    于是**派发中的 ERROR 一律看不到那个 ErrorLog**（`getErrorLog()` 为 null）；
 *  - `TestLoggerFactory$TestLogger.error` 在 `errorLog == null` 且 processor 返回 `Action.ALL`
 *    （默认）时，会先 dump `ERROR: …` 到 stderr、再**抛 `TestLoggerAssertionError`**——而这条异常
 *    恰好被 `assemblePreviewEditor` 的 `runCatching` 吞掉并降级到内存 PSI（变异实跑即此形态）。
 *  结论：**本缺陷必须自建捕获**（[RecordingProcessor] + 显式断言），不能依赖 UsefulTestCase 的自动判红。
 *
 * **覆盖层级与三重防空转**：写安全断言只对**本地文件承载**的 `DocumentImpl` 生效
 * （`DocumentImpl.assertWriteAccess` 只在 `FileDocumentManager.getFile(doc).isInLocalFileSystem`
 * 时才调 `TransactionGuardImpl.assertWriteActionAllowed`），而装配有「临时文件分支 / 内存 PSI 降级
 * 分支」两条路。故本文件除主断言外还有两道自证：
 *  ① 主用例断言装配**确实落在临时文件分支且真的写了文档**（非空文本：预热占位是空串）；
 *  ② [test正对照_any模态下的同形文档写必被平台判写不安全] 证明本环境**具备**把这类写判成写不安全的能力。
 *
 * 与 `SqlPreviewPanelPrewarmConsumeTest` 的分工：那一条锁「预热产物就绪→消费」的分支走向
 * （临时文件 vs 内存 PSI），本文件锁「派发模态的写安全资格」，共用同一套「等预热出结果再驱动消费」的
 * headless 前提。
 */
class SqlPreviewPanelPrewarmWriteSafeTest : BasePlatformTestCase() {

    /**
     * 记录平台 ERROR 级日志的处理器（[LoggedErrorProcessor.executeWith] 局部生效，出块即还原）。
     *
     * **一律返回 `Action.NONE`（不 dump、不抛）**，理由有二：
     *  - 本用例的判据是「捕获到的日志里有没有 `Write-unsafe context!`」，由显式断言给出可读消息；
     *  - 若返回默认的 `Action.ALL`，`TestLogger.error` 在 `errorLog == null`（见类 KDoc：抽 EDT 时
     *    ThreadContext 被 reset）时会抛 `TestLoggerAssertionError`，而该异常被
     *    `assemblePreviewEditor` 的 `runCatching` 吞掉 → 装配降级到内存 PSI（**变异实跑实测**）——
     *    信号被降级掩盖，失败消息也指向「没走临时文件分支」而非本缺陷本身。
     *
     * 两个参数都收：`TestLoggerFactory$TestLogger.error` 传给 `processError` 的第一个实参是
     * **logger 名**、第二个才是消息体（javap 实证），只取一个会漏掉真正含 "Write-unsafe context!" 的那侧。
     */
    private class RecordingProcessor : LoggedErrorProcessor() {
        val seen = mutableListOf<String>()

        override fun processError(
            message: String,
            details: String,
            trace: Array<String>,
            throwable: Throwable?,
        ): MutableSet<Action> {
            seen += message
            seen += details
            return Action.NONE
        }

        fun writeUnsafeHits(): List<String> = seen.filter { it.contains("Write-unsafe context!") }
    }

    private fun result() = PreviewResult(
        sql = "SELECT id\nFROM user\nWHERE username = ?",
        compactSql = "SELECT id FROM user WHERE username = ?",
        unresolvedParams = listOf("username"),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "user",
        columns = listOf("id", "username"),
    )

    /**
     * 有界等待预热线程出结果（与 `SqlPreviewPanelPrewarmConsumeTest` 同款前提，此处内联一份避免
     * 跨类暴露测试口）：预算 60s（light fixture 毫秒级，但每个 JVM 首个面板要付冷启动代价）；
     * **等待期间持续抽干 EDT 队列**——预热线程的方言预映射经 `invokeLater` 派 EDT 并有界等待，
     * 纯 sleep 会烧掉预算并让后续需 EDT 的步骤排队超时。
     *
     * 为什么先等再注册：`PREWARM_WAIT_MS` 只有 60ms，headless 下预热远慢于它，若在冷态注册则
     * `completeOnTimeout` 先以 null 完成 → 回调走 onFallback → 降级到**内存 PSI**（无本地文件 →
     * 平台的写安全断言不生效），断言就空转了。等出结果后注册，`whenComplete` 对已完成 future
     * 立即以内联方式调 [PrewarmAwait] 的 dispatcher——**派发模态仍由生产代码给出**，被测点不变。
     */
    private fun awaitPrewarmProduct(panel: SqlPreviewPanel): Pair<com.intellij.openapi.vfs.VirtualFile, com.intellij.psi.PsiFile>? {
        val await = panel.prewarmAwaitForTest()
        assertNotNull("面板构造期应已发起预热", await)
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && !await!!.isDone()) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("预热应在预算内出结果（成功或失败）", await!!.isDone())
        return await.joinOrNull()
    }

    // ------------------------------------------------------------------
    // 一、主用例：生产 dispatcher 派出的装配回调不得产生写不安全告警
    // ------------------------------------------------------------------

    fun test装配回调经生产dispatcher派发_不产生写不安全告警() {
        val panel = SqlPreviewPanel(project)
        try {
            assertNotNull(
                "headless 下项目内预览文件应预热成功（失败=环境问题，不是本用例的通过条件）",
                awaitPrewarmProduct(panel),
            )
            val await = panel.prewarmAwaitForTest()
            assertNotNull(await)

            val rec = RecordingProcessor()
            var callbackRan = false
            LoggedErrorProcessor.executeWith(rec, ThrowableRunnable {
                // 注册到**生产** PrewarmAwait（dispatcher 即 SqlPreviewPanel 构造预热 await 时给的那个
                // lambda）；onReady/onFallback 内跑的是**生产装配**：showResult → resolvePreviewEditorSource
                // （await 已 done → 热态同步取产物）→ assemblePreviewEditor（含文档写）。
                val registered = await!!.scheduleOnce(
                    onReady = {
                        callbackRan = true
                        panel.showResult(result())
                    },
                    onFallback = {
                        callbackRan = true
                        panel.showResult(result())
                    },
                )
                assertTrue("首次注册应生效（幂等门未被先前调用占位）", registered)
                // 抽干 EDT：派发出的装配回调在此刻执行（回调体在 EDT 上）
                UIUtil.dispatchAllInvocationEvents()
            })

            assertTrue(
                "装配回调必须真的经生产 dispatcher 派发过（否则本用例空转，绿灯无意义）",
                callbackRan,
            )

            // ---- 主断言：装配回调（含其内文档写）不得触发平台的写不安全告警 ----
            assertTrue(
                "生产 dispatcher 排出的装配回调触发了平台的写不安全告警（派发模态须为 write-safe，" +
                    "见 SqlPreviewPanel 预热 await 构造处注释）。捕获到的 ERROR 日志：${rec.seen}",
                rec.writeUnsafeHits().isEmpty(),
            )

            // ---- 防空转自证：装配必须落在「项目内临时文件」分支，且本次装配真的写了文档 ----
            // （内存 PSI 降级产物的文档无本地文件，平台的写安全断言对它不生效，那样上面那条是空转）
            val doc = panel.editorForTest()?.document
            assertNotNull("装配应已创建预览编辑器文档", doc)
            val docFile = FileDocumentManager.getInstance().getFile(doc!!)
            assertNotNull(
                "装配必须走临时文件分支（内存 PSI 降级产物无 VFS 文件 → 写安全断言对它不生效，" +
                    "上面的主断言会空转）。降级横幅层级=${panel.degradationBannerForTest().kindForTest()}",
                docFile,
            )
            assertTrue(
                "临时文件应位于项目预览目录内，实际：${docFile!!.path}",
                docFile.path.contains("sql-visualizer"),
            )
            assertTrue(
                "本次装配应把 SQL 写进临时文件文档（预热只写空占位，非空即证明回调内确实发生了文档写）：" +
                    "实际文本=「${doc.text}」",
                doc.text.contains("SELECT"),
            )
        } finally {
            panel.clear()
        }
    }

    // ------------------------------------------------------------------
    // 二、冷态路径：派发模态在**非 EDT 线程**上解析，同样必须写安全
    // ------------------------------------------------------------------

    /**
     * **冷态路径**（生产真实形态）：预热未完成时注册，`whenComplete` 在**非 EDT 线程**上触发
     * （预热池线程，或 `completeOnTimeout` 的 Delayer 线程），故 `ModalityState.defaultModalityState()`
     * 是在**没有模态进度上下文的非 EDT 线程**上求值的——按平台取值链（ProgressManager 当前模态 →
     * 线程/协程上下文模态 → NON_MODAL）应落 NON_MODAL（恒 write-safe）。
     *
     * 为什么单独立一条：主用例走的是「已就绪 → `whenComplete` 内联」路径，模态在 **EDT** 上求值；
     * 而 `DocWriteContext` KDoc 明确记着 `defaultModalityState()` 的取值**依赖求值时刻的上下文**
     * （进模态时快照），两条路径求值上下文不同，必须分别钉住——否则「EDT 上安全」推不出「池线程上安全」，
     * 而后者才是真机冷态装配走的那条。
     *
     * 形态说明：headless 下预热远慢于 `PREWARM_WAIT_MS`（60ms），故本用例注册后通常先由超时以 null
     * 完成、走 onFallback；两条分支都写成同一动作（在派发出的回调里写一份本地文件文档）——被测点
     * 是**派发模态的写安全资格**，与产物是否就绪无关。
     */
    fun test冷态派发_回调在非EDT线程解析的模态同样写安全() {
        val vf = myFixture.addFileToProject("prewarm-cold-dispatch.sql", "placeholder").virtualFile
        val probeDoc = FileDocumentManager.getInstance().getDocument(vf)
        assertNotNull("冷态探针需要本地文件承载的文档（写安全断言只对本地文件文档生效）", probeDoc)

        val panel = SqlPreviewPanel(project)
        try {
            val await = panel.prewarmAwaitForTest()
            assertNotNull("面板构造期应已发起预热", await)

            val rec = RecordingProcessor()
            var dispatched = false
            var branch = ""
            // 前置条件（覆盖声明的成立依据）：此刻预热**尚未**完成，故完成回调不可能在 EDT 上内联执行，
            // 派发模态必然在非 EDT 线程（池线程或 completeOnTimeout 的 Delayer 线程）上解析。
            // 若这条将来变红：说明预热已能在面板构造后瞬时完成，本用例不再覆盖冷态路径（覆盖声明失效），
            // 应改回「让注册先于完成」的构造方式而不是放宽断言。
            assertTrue(
                "前置条件不成立：注册时预热已完成（await.isDone()=true）——此时完成回调会在 EDT 上内联，" +
                    "本用例就不再覆盖「非 EDT 线程解析派发模态」这条路径（冷态路径的覆盖声明失效）",
                !await!!.isDone(),
            )
            LoggedErrorProcessor.executeWith(rec, ThrowableRunnable {
                // 构造后**立即**注册：此时预热线程仍在跑（headless 下预热耗时远超 60ms 预算），
                // 完成回调不会在 EDT 上内联执行。
                val registered = await!!.scheduleOnce(
                    onReady = {
                        dispatched = true
                        branch = "onReady(预热先完成)"
                        WriteCommandAction.runWriteCommandAction(project) { probeDoc!!.setText("cold-ready") }
                    },
                    onFallback = {
                        dispatched = true
                        branch = "onFallback(60ms 预算到期)"
                        WriteCommandAction.runWriteCommandAction(project) { probeDoc!!.setText("cold-fallback") }
                    },
                )
                assertTrue("首次注册应生效（幂等门未被先前调用占位）", registered)
                val deadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < deadline && !dispatched) {
                    UIUtil.dispatchAllInvocationEvents()
                    Thread.sleep(10)
                }
                UIUtil.dispatchAllInvocationEvents()
            })

            assertTrue("派发回调必须真的执行过（否则本用例空转）", dispatched)
            assertTrue(
                "回调必须真的写过文档（探针初值是占位文本，被改写即证明派发拍内发生了文档写）：" +
                    "分支=$branch，实际文本=「${probeDoc!!.text}」",
                probeDoc!!.text.startsWith("cold-"),
            )
            assertTrue(
                "冷态派发（模态在非 EDT 线程解析）的回调写文档触发了写不安全告警（派发模态须为 write-safe）。" +
                    "分支=$branch，捕获到的 ERROR 日志：${rec.seen}",
                rec.writeUnsafeHits().isEmpty(),
            )
        } finally {
            panel.clear()
        }
    }

    // ------------------------------------------------------------------
    // 三、正对照：本环境确实能把「any() 模态下的文档写」判成写不安全
    // ------------------------------------------------------------------

    /**
     * **正对照（防空绿的最后一环）**：证明本环境具备被测能力——否则主用例的绿灯可能只是
     * 「平台压根没在测试模式报这个错」，那种绿灯是假绿。
     *
     * 形态与主用例逐点对齐：同一种承载（本地文件上的 `DocumentImpl`）、同一种写
     * （`WriteCommandAction.runWriteCommandAction { setText }`）、同一种派发（`invokeLater` + 抽 EDT），
     * 唯一变量是**模态**（`ModalityState.any()`）。这里必须报错；主用例的 `defaultModalityState()`
     * 必须不报——两条合起来才构成「模态选错 = 写不安全」的完整判据。
     */
    fun test正对照_any模态下的同形文档写必被平台判写不安全() {
        val vf = myFixture.addFileToProject("writesafe-control.sql", "select 1").virtualFile
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        assertNotNull("正对照需要本地文件承载的文档（写安全断言只对本地文件文档生效）", doc)
        assertTrue("正对照文件应在本地文件系统内", vf.isInLocalFileSystem)

        val rec = RecordingProcessor()
        LoggedErrorProcessor.executeWith(rec, ThrowableRunnable {
            ApplicationManager.getApplication().invokeLater(
                { WriteCommandAction.runWriteCommandAction(project) { doc!!.setText("control") } },
                ModalityState.any(),
            )
            UIUtil.dispatchAllInvocationEvents()
        })

        assertTrue(
            "正对照失败：本环境未把 ModalityState.any() 模态下的文档写判为写不安全——" +
                "「环境不具备被测能力」，主用例的绿灯此时不可信，须先解决环境问题（registry " +
                "ide.require.transaction.for.model.changes / LoadingState.COMPONENTS_LOADED / logger 装配）。" +
                "本次捕获到的 ERROR 日志：${rec.seen}",
            rec.writeUnsafeHits().isNotEmpty(),
        )
    }
}
