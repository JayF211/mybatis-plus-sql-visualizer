package cn.song.mybatisplus.visualizer.testutil

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.serviceContainer.AlreadyDisposedException

/**
 * `AlreadyDisposedException: Container is already disposed` 的诊断上下文补全（**T-ST-02c 观测手段**）。
 *
 * ## 为什么需要（实测形态，2026-09-19 T-ST-02）
 *
 * 门禁 `testShards` 首跑出现过一族「抛点在 `project.getService(...)`」的
 * `AlreadyDisposedException: Container is already disposed`（`SectionConfigurableAutoCaptureLinkageTest`
 * ×2 / `AgentListenSyncTest` ×1 / `McpHistoryIngestTest` ×2 / `EpBridgeSinglePathTest` 等），
 * 类内隔离复跑恒绿、重跑全量亦绿——**自然触发路径未复现**。
 *
 * 受控前置下已把该形态的**机制**钉死到行（临时探针实测，探针已删）：
 *
 * ```
 * 前置：light 项目处于 temporarily-disposed 窗口（ProjectImpl.isDisposed == true，但实例容器未释放）
 * 触发：project.getService(X) 首次创建 X，而 X 的构造期订阅 project.messageBus
 *   → MessageBusImpl.checkOwnerDisposed() 判 owner.isDisposed（= isTemporarilyDisposed）→ 抛
 *     AlreadyDisposedException("MessageBus(owner=Project(... containerState=disposed temporarily ...))")
 *     （MessageBusImpl.kt:224；该类 extends CancellationException）
 *   → 容器服务创建链把该 CancellationException 归一成
 *     AlreadyDisposedException("Container is already disposed")（ComponentManagerImpl.kt:1670）
 * ```
 *
 * 即：**该异常的前置条件只有一个——「项目处于 temporarily-disposed 窗口」时首次创建订阅项目总线的
 * 项目服务**；缺的是「谁在什么窗口里触发了这次创建」。本对象补的正是这条上下文。
 *
 * ## 补什么
 *
 * 异常消息与一条 WARN 日志里补：**片号 / 触发点（测试类#方法:行）/ 服务名 / 线程 / 项目态**，
 * 并把原异常挂为 cause（原始链路——含 `containerState=disposed temporarily` 那句——完整保留）。
 *
 * ## 不改行为
 *
 * 抛出的仍是 `AlreadyDisposedException`（同类型、同控制流：调用方的 catch/runCatching、
 * `is CancellationException` 判定、失败归属都不变），只是消息前缀多了一段上下文。
 * 未命中异常时零开销（不建字符串、不打日志）。
 *
 * ## 用法
 *
 * 测试类里把 `project.getService(X::class.java)` 换成
 * `AlreadyDisposedDiagnostics.projectService(project, X::class.java, "<本调用点用途>")`。
 * `trigger` 写调用点的语义标签（如 `"setUp.capture"` / `"tearDown.restore"`），便于一眼定位。
 */
object AlreadyDisposedDiagnostics {

    private val LOG = Logger.getInstance(AlreadyDisposedDiagnostics::class.java)

    /** 本仓测试源码包前缀——用于在栈里认出「触发点」所属的测试类。 */
    private const val TEST_SOURCE_PREFIX = "cn.song.mybatisplus.visualizer"

    /**
     * 等价于 `project.getService(serviceClass)`；仅在抛 [AlreadyDisposedException] 时补诊断上下文。
     */
    fun <T : Any> projectService(project: Project, serviceClass: Class<T>, trigger: String): T {
        try {
            return project.getService(serviceClass)
        }
        catch (e: AlreadyDisposedException) {
            val detail = buildContext(project, serviceClass, trigger, e)
            LOG.warn(detail, e)
            // 同类型重抛：控制流与失败归属不变，仅消息带上上下文；原链路挂 cause 保留
            throw AlreadyDisposedException(detail).also { it.initCause(e) }
        }
    }

    /** 拼「片号 / 触发点 / 服务 / 线程 / 项目态」上下文（不含原始链路——链路由 cause 承载）。 */
    private fun buildContext(
        project: Project,
        serviceClass: Class<*>,
        trigger: String,
        original: AlreadyDisposedException,
    ): String = buildString {
        append("[T-ST-02] AlreadyDisposed@")
        append(shardId())
        append(" trigger=").append(trigger)
        append(" service=").append(serviceClass.simpleName)
        append(" caller=").append(callerPoint())
        append(" thread=").append(Thread.currentThread().name)
        append(" project.isDisposed=").append(project.isDisposed)
        append(" project=").append(runCatching { project.name }.getOrDefault("?"))
        append(" :: ").append(original.message)
    }

    /**
     * 片号：分片任务的测试沙盒落在 `<build>/test-sandbox/shardN`，故从平台 config 路径反推；
     * 取不到时退化为沙盒目录名（默认 `test` 任务 = 本仓 sandboxContainer 名）。
     */
    private fun shardId(): String {
        val cfg = runCatching { PathManager.getConfigPath() }.getOrNull().orEmpty().replace('\\', '/')
        Regex("test-sandbox/(shard[0-9]+)").find(cfg)?.let { return it.groupValues[1] }
        Regex("/build/([^/]+)/").find(cfg)?.let { return it.groupValues[1] }
        return "unknown"
    }

    /**
     * 触发点：栈里第一个本仓测试源码的帧（`包.类#方法:行`）——即 `projectService(...)` 的直接调用点。
     * 只跳过本对象自身，不跳过整个 `testutil` 包：调用点若在 testutil 的辅助件里，报出辅助件比报
     * `unknown` 更有用。
     */
    private fun callerPoint(): String {
        val self = AlreadyDisposedDiagnostics::class.java.name
        for (frame in Throwable().stackTrace) {
            val cls = frame.className
            if (!cls.startsWith(TEST_SOURCE_PREFIX)) continue
            if (cls == self) continue
            return "$cls#${frame.methodName}:${frame.lineNumber}"
        }
        return "unknown"
    }
}
