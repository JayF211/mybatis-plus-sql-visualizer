package cn.song.mybatisplus.visualizer.testutil

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.SqlLogManager

/**
 * 项目服务级 [SqlLogManager] 状态快照 / 原样复位（T-TestIsolation）。
 *
 * **为什么需要**：light 测试项目在同一分片 JVM 内**跨测试类复用同一个 ProjectImpl**
 * （`LightPlatformTestCase.ourProject` 片内只建一次，仅 `setLightProjectName` 随用例改名），
 * 因此 `project.getService(SqlLogManager::class.java)` 返回的是**全片共享**的单例——
 * 任何测试类改了它的可变状态却不复位，都会串台到同片后续测试类。是否发作取决于分片时长权重
 * 算出来的邻居（新增测试类即重排，见 memory「分片重平衡型顺序依赖假失败」），
 * 所以这类红总是「本类隔离跑绿、分片跑红」。
 *
 * **实测两例（2026-09-19，均为项目服务级状态未复位）**：
 *  - `McpHistoryIngestTest` 经 `recordMcpExecution` 往共享 buffer 入队 2 条后未清 →
 *    同片后位的 `SqlConsoleBridgeFilterProviderTest.test非捕捉态_零处理_不置位`
 *    断言 `snapshot().size == 0` 红（`AssertionFailedError` at 该用例 :43）；
 *  - `SqlConsoleBridgeFilterProviderTest` / `EpBridgeSinglePathTest` 把
 *    `entries.dedupeEnabled` 置 false 后不复位 → 依赖「去重默认开」的用例红；
 *  - `SectionConfigurableAutoCaptureLinkageTest` 经 `apply()` → `onAutoStartSettingEnabled()`
 *    → `startCapture()` 在共享实例上置 capturing / 等待态、解除 paused 并**启动控制台轮询**，
 *    同样无人复位。
 *
 * **用法**：`setUp` 里（`super.setUp()` 之后、任何改动之前）[capture]；`tearDown` 里
 * （`super.tearDown()` 之前）[restore]。已被本类显式复位过的字段再走一次 [restore] 是幂等的。
 *
 * **T-AgentResiduals R2（2026-09-22）——快照持有服务引用，tearDown 不再取服务**：
 * 旧形态是 `capture(projectService(project, …))` + `restore(projectService(project, …), state)`
 * ——tearDown 里**再取一次服务**。而 `@After`/`tearDown` 正是「项目 temporarily-disposed」窗口的
 * **高发时段**，一旦此刻才发起服务创建，平台 `LazyInstanceHolder` 会把 `CannotInitialize` 缓存下来、
 * **该服务在该 ProjectImpl 上永久重抛**（T-ST-02b2 已证），同片后续所有取用点成片变红。
 * 现快照**自持 manager 引用**，[restore] 只吃快照（单参），tearDown 路径**结构上不再触碰服务容器**；
 * 且复原的正是被本类改过的那个实例（即便容器此后被重建，复原目标依旧正确）。
 *
 * **复位边界**：只回滚「本类引入的差异」——
 *  - 条目按**引用同一性**移除本类新增的那些（buffer 入库实例即持有实例，与 `removeEntry` 同一性
 *    语义一致）；被本类 `clear()` 掉的既有条目无法复活，故各测试类仍应自清；
 *  - 轮询只在「捕获时未在轮询」的前提下停止（把本类 `startCapture()` 起的轮询收回去）。
 *
 * **k2j 适配**：
 *  - master 的 `LogSqlBuffer.onEntryStored` / `onEvicted` 是 Kotlin 函数类型，k2j 的
 *    `SqlLogEntryBuffer` 是 Java `Consumer<LogSqlEntry>` 字段——Kotlin 函数类型与 Java SAM
 *    之间**无隐式转换**，故本快照类同口径改存 `java.util.function.Consumer<LogSqlEntry>`；
 *  - `manager.userArmed` 在 Java 侧只有 `isUserArmed()`（无 `getUserArmed()`），Kotlin 属性名是
 *    `isUserArmed`；
 *  - 其余属性名与 master 逐字一致（`isCapturing`/`getCapturing` 双入口使 `capturing` 可读）。
 *
 * **⚠ 生产侧可见性缺口（待主会话放行，见交付回报）**：master 里 `lastAutoShowAt` /
 * `autoShowSuppressedByUserClose` / `logWindowWasVisible` / `consoleBridge` 均为 `internal`
 * （模块内可见），k2j 的 `SqlLogManager.java` 承载成了**包私有**——本类在 `testutil` 包，
 * 与 `log` 包不同包，故这四处目前**编译不过**。按同步计划 §互操作铁律 ⑤（`internal` → `public`，
 * 跨包测试可见性）放开这四处即可，本文件无需再改。
 */
object SqlLogManagerProjectState {

    /**
     * 一次快照：**所属服务实例** + buffer 可变状态 + 条目内容 + 管理器捕获态 / 自动展开态 + 桥轮询态。
     *
     * [manager] 是 R2 的核心：快照自持服务引用，复原时不再经 `project.getService(…)` 取服务。
     */
    class Snapshot internal constructor(
        internal val manager: SqlLogManager,
        internal val entries: List<LogSqlEntry>,
        internal val dedupeEnabled: Boolean,
        internal val paused: Boolean,
        internal val filterKeywords: List<String>,
        internal val maxEntries: Int,
        internal val dedupeWindowMs: Long,
        internal val errorBindWindowMs: Long,
        internal val onEntryStored: java.util.function.Consumer<LogSqlEntry>?,
        internal val onEvicted: java.util.function.Consumer<LogSqlEntry>?,
        internal val capturing: Boolean,
        internal val userArmed: Boolean,
        internal val waitingForConsole: Boolean,
        internal val autoShowSuppressedByUserClose: Boolean,
        internal val lastAutoShowAt: Long,
        internal val logWindowWasVisible: Boolean,
        internal val pollingActive: Boolean,
    )

    /**
     * 存初值（在测试类改动任何共享状态之前调用）。
     *
     * [manager] 为 `null` 时返回 `null`（**整个存复原退化为 no-op**，不抛异常）——服务未创建 /
     * 只拿到读路径的「取不到就 null」（如 `sqlLogManagerIfCreated`）时，调用方无需自行分支。
     */
    fun capture(manager: SqlLogManager?): Snapshot? {
        if (manager == null) return null
        val buffer = manager.entries
        return Snapshot(
            manager = manager,
            entries = buffer.snapshot(),
            dedupeEnabled = buffer.dedupeEnabled,
            paused = buffer.isPaused,
            filterKeywords = buffer.filterKeywords,
            maxEntries = buffer.maxEntries,
            dedupeWindowMs = buffer.dedupeWindowMs,
            errorBindWindowMs = buffer.errorBindWindowMs,
            onEntryStored = buffer.onEntryStored,
            onEvicted = buffer.onEvicted,
            capturing = manager.capturing,
            userArmed = manager.isUserArmed,
            waitingForConsole = manager.isWaitingForConsole,
            autoShowSuppressedByUserClose = manager.autoShowSuppressedByUserClose,
            lastAutoShowAt = manager.lastAutoShowAt,
            logWindowWasVisible = manager.logWindowWasVisible,
            pollingActive = manager.consoleBridge.isPollingActive,
        )
    }

    /**
     * 原样复位（在 `super.tearDown()` 之前调用）。快照为 `null`（未捕获 / 服务未创建）时**静默跳过**。
     *
     * 只吃快照、不吃服务——复原目标取自 `snapshot.manager`，`tearDown` 不发起任何服务查找（R2）。
     */
    fun restore(snapshot: Snapshot?) {
        val snapshot = snapshot ?: return
        val manager = snapshot.manager
        val buffer = manager.entries
        // 条目：先按引用同一性移除本类新增的，再复位其余可变字段
        val kept = snapshot.entries.mapTo(HashSet()) { System.identityHashCode(it) }
        buffer.snapshot()
            .filterNot { System.identityHashCode(it) in kept }
            .forEach { buffer.removeEntry(it) }
        buffer.dedupeEnabled = snapshot.dedupeEnabled
        buffer.isPaused = snapshot.paused
        buffer.filterKeywords = snapshot.filterKeywords
        buffer.maxEntries = snapshot.maxEntries
        buffer.dedupeWindowMs = snapshot.dedupeWindowMs
        buffer.errorBindWindowMs = snapshot.errorBindWindowMs
        buffer.onEntryStored = snapshot.onEntryStored
        buffer.onEvicted = snapshot.onEvicted
        manager.restoreCaptureStateForTest(snapshot.capturing, snapshot.userArmed, snapshot.waitingForConsole)
        manager.autoShowSuppressedByUserClose = snapshot.autoShowSuppressedByUserClose
        manager.lastAutoShowAt = snapshot.lastAutoShowAt
        manager.logWindowWasVisible = snapshot.logWindowWasVisible
        if (!snapshot.pollingActive) {
            // 本类（经 startCapture / startListeningOnly）把共享桥挂起来过：轮询 / 内容监听 / 已挂
            // filter 一并收回——否则桥在片内后续测试类里继续轮询，残留桩控制台还会让
            // hasConsoles() 恒真（startCapture 走「控制台已存在」分支而非 armed 分支）
            manager.consoleBridge.detachAllFilters()
            manager.consoleBridge.stopPolling()
            manager.consoleBridge.detachContentListener()
        }
    }
}
