package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import java.util.concurrent.atomic.AtomicInteger

/**
 * T-WriteSafeCtx：程序性文档写入的「写安全上下文」闸门。
 *
 * 平台契约（243.28141.41 源码逐帧实证，`TransactionGuard.java:73-76` 原文：nonModal 恒 write-safe，
 * any 恒 write-unsafe）：
 *  - **写入合法性不取决于是否包了 `WriteCommandAction`**，而取决于这次 EDT 活动有没有 write-safe
 *    资格：要么是用户输入活动（`IdeEventQueue.kt:1054` 只把输入/Item/Focus 事件算作用户活动，
 *    posted runnable 一律不算），要么该 runnable 是以 write-safe 模态排队的
 *    （`TransactionGuardImpl.wrapLaterInvocation:192-203` 只对 write-safe 模态套 runWithWritingAllowed）。
 *  - `ModalityState.any()` 排出的 runnable 写文档必触发 `Write-unsafe context!`
 *    （`TransactionGuardImpl:141-145` LOG.error）：生产 IDE 只记 SEVERE +「Plugin to blame」+ FUS 上报
 *    （IdeaLogger 不抛，写入照常完成），单测 logger 直接抛 AssertionError。
 *  - `ModalityState.defaultModalityState()` 只在**写安全上下文里创建**时才安全（`enteredModality:188-190`
 *    记录的是进入该模态那一刻的 myWritingAllowed 快照）——别拿它兜底，宁可 nonModal。
 *
 * 两条被真机事故证伪的假设（勿再沿用）：
 *  1. 「走 document 写（非裸写）就安全」——document 写只规避 File Cache Conflict 系问题，与 write-safe
 *     资格无关；`WriteCommandAction { doc.setText(...) }` 在 any() 活动里照样报错（SqlPreviewPanel:1712 事故）。
 *  2. 「`any()` 是为了模态弹窗存续期照常执行」——该惯例只适用**纯 UI** 回调；**改文档**恰好是模态
 *     存续期禁止的行为，写入类回调一律 nonModal（延迟到弹窗关闭是正确语义，非退化）。
 */
object DocWriteContext {

    private val LOG = Logger.getInstance(DocWriteContext::class.java)

    /** 测试钩子：非 null 时优先返回其判定（覆盖单测放行，见 [isWriteAllowedNow]）。 */
    internal var writeAllowedProbe: (() -> Boolean)? = null

    /** 投递计数（测试观察点：断言「没有同步写，而是投递了一拍」）。 */
    internal val deferredCount = AtomicInteger(0)

    /**
     * 当前上下文是否允许写文档模型。
     *
     * 单测环境直接放行：测试里没有真实的事务/模态语义（测试线程非真实 EDT，`isWritingAllowed` 的
     * 前置断言也未必成立），若按真实判据返回 false，既有面板测试会因「渲染被投递到下一拍」而整片红。
     * 闸门的投递行为由 [writeAllowedProbe] 注入覆盖来测。
     */
    fun isWriteAllowedNow(): Boolean {
        writeAllowedProbe?.let { return it() }
        val app = ApplicationManager.getApplication() ?: return false
        if (app.isUnitTestMode) return true
        if (!app.isDispatchThread) return false
        // isWritingAllowed() 要求写意图锁（BGT/无锁时抛）——保守兜底 false（宁延迟一拍不违规）
        return runCatching { TransactionGuard.getInstance().isWritingAllowed() }.getOrDefault(false)
    }

    /** 以 write-safe 模态（nonModal）投递；[expireWith] 已销毁则丢弃该拍。 */
    fun invokeLaterWriteSafe(expireWith: Disposable?, reason: String, runnable: Runnable) {
        val app = ApplicationManager.getApplication() ?: return
        deferredCount.incrementAndGet()
        LOG.info("[T-WSC] write deferred to nonModal (write-unsafe context): $reason")
        app.invokeLater({
            if (expireWith != null && Disposer.isDisposed(expireWith)) return@invokeLater
            runnable.run()
        }, ModalityState.nonModal())
    }

    /** 就地写：允许写→[block]；不允许→投递到 nonModal。调用方须保证 [block] 幂等（重投递会重跑）。 */
    fun runWriteSafe(expireWith: Disposable?, reason: String, block: () -> Unit) {
        if (isWriteAllowedNow()) {
            block()
            return
        }
        invokeLaterWriteSafe(expireWith, reason, Runnable { block() })
    }
}
