package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.RunConfigurationProcessListener
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 不调用真实 attach、不启动外部进程；可观察排队与最终建会话边界。 */
class AgentAttachTargetGateTest : BasePlatformTestCase() {
    private lateinit var manager: AgentManager
    private lateinit var originalInspector: (Long, Any?, Boolean) -> AgentAttachTarget.Decision
    private lateinit var originalFactory: (Long) -> AgentSession?
    private lateinit var originalDispatcher: (Runnable) -> Unit
    private val tasks = mutableListOf<Runnable>()
    private var attachCalls = 0
    private val pid = 999_807_007L

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        originalInspector = manager.attachTargetInspector
        originalFactory = manager.attachSessionFactory
        originalDispatcher = manager.attachTaskDispatcher
        manager.attachSessionFactory = { attachCalls++; null }
        manager.attachTaskDispatcher = { tasks.add(it) }
        PluginSettings.getInstance().state.enhancedCaptureEnabled = true
        PluginSettings.getInstance().state.enhancedCaptureConfirmed = true
    }

    override fun tearDown() {
        try {
            tasks.clear()
            manager.shutdownAll()
            manager.attachTargetInspector = originalInspector
            manager.attachSessionFactory = originalFactory
            manager.attachTaskDispatcher = originalDispatcher
            PluginSettings.getInstance().state.enhancedCaptureEnabled = false
            PluginSettings.getInstance().state.enhancedCaptureConfirmed = false
        } finally { super.tearDown() }
    }

    fun testProcessStarted真实Helper已授权仍不排队不Attach() {
        RunConfigurationProcessListener.offerStartedProcess(project, Handler(pid, AgentAttachTargetTest.REAL_HELPER), false)
        assertRejected()
    }

    fun testBackfill与SettingsRescan真实BaseOSHandler同样拒绝() {
        // 构造器与 getCommandLine 已用243 util-8.jar javap确认；不 startNotify，不产生读线程。
        val process = StubProcess(pid)
        val clazz = Class.forName("com.intellij.execution.process.BaseOSProcessHandler", true,
            com.intellij.openapi.project.Project::class.java.classLoader)
        val handler = clazz.getConstructor(Process::class.java, String::class.java, java.nio.charset.Charset::class.java)
            .newInstance(process, AgentAttachTargetTest.REAL_HELPER, Charsets.UTF_8)
        try {
            for (tag in listOf("backfill", "settings-rescan")) {
                RunConfigurationProcessListener.scanDescriptors(project, tag, arrayOf(Descriptor(handler)))
                assertRejected()
            }
            val application = clazz.getConstructor(Process::class.java, String::class.java, java.nio.charset.Charset::class.java)
                .newInstance(process, AgentAttachTargetTest.REAL_HELPER.replace(AgentAttachTargetTest.HELPER, AgentAttachTargetTest.APP), Charsets.UTF_8)
            try {
                for (tag in listOf("backfill", "settings-rescan")) {
                    tasks.clear()
                    manager.pendingPids.clear()
                    RunConfigurationProcessListener.scanDescriptors(project, tag, arrayOf(Descriptor(application)))
                    assertTrue("$tag 业务应可排队", manager.pendingPids.containsKey(pid))
                    assertEquals(1, tasks.size)
                }
            } finally { clazz.getMethod("destroyProcess").invoke(application) }
        } finally {
            process.destroy()
            clazz.getMethod("destroyProcess").invoke(handler)
        }
    }

    fun test最终Pid入口拒绝Helper而非靠Attach失败() {
        manager.attachTargetInspector = { _, _, readFiles ->
            assertTrue("最终入口允许解析启动文件", readFiles)
            AgentAttachTarget.classifyCommand(AgentAttachTargetTest.REAL_HELPER)
        }
        assertFalse(manager.attachToPid(pid))
        assertRejected()
    }

    fun test业务携带相同驱动Classpath可排队且最终可到Attach边界() {
        val command = AgentAttachTargetTest.REAL_HELPER.replace(AgentAttachTargetTest.HELPER, AgentAttachTargetTest.APP)
        RunConfigurationProcessListener.offerStartedProcess(project, Handler(pid, command), true)
        assertTrue(manager.pendingPids.containsKey(pid))
        assertEquals(1, tasks.size)
        // 最终入口用相同判定，factory 代替真正注入，避免以不可达PID假绿。
        manager.attachTargetInspector = { _, _, _ -> AgentAttachTarget.classifyCommand(command) }
        manager.attachToPid(pid)
        assertEquals(1, attachCalls)
    }

    fun test未知身份保留既有项目候选与授权边界() {
        manager.attachTargetInspector = { _, _, _ -> AgentAttachTarget.Decision.UNKNOWN }
        manager.onProcessAppeared(Handler(pid, ""), false)
        assertTrue(manager.pendingPids.containsKey(pid))
        manager.attachToPid(pid)
        assertEquals(1, attachCalls)
    }

    fun test业务Premain已写幂等表不二次Attach() {
        manager.attachTargetInspector = { _, _, _ -> AgentAttachTarget.Decision.APPLICATION }
        manager.attachedPids[pid] = 1L // premain 提交的幂等状态；真实 HELLO 测试见 AgentManagerPremainTest。
        manager.attachToPid(pid)
        assertEquals(0, attachCalls)
        assertEquals(1L, manager.attachedPids[pid])
    }

    fun testArgfileHelper先后台判身份不入Attach队列() {
        manager.attachTargetInspector = { _, _, readFiles ->
            if (!readFiles) AgentAttachTarget.Decision.NEEDS_FILES
            else AgentAttachTarget.classifyCommand("java @args.txt", argFile = { AgentAttachTargetTest.HELPER })
        }
        manager.onProcessAppeared(Handler(pid, "java @args.txt"))
        assertTrue(manager.pendingPids.isEmpty())
        assertEquals("仅身份读取任务，不是attach任务", 1, tasks.size)
        tasks.removeAt(0).run()
        assertRejected()
    }

    private fun assertRejected() {
        assertTrue("helper 不进入挂载排队表", manager.pendingPids.isEmpty())
        assertTrue("helper 不提交挂载任务", tasks.isEmpty())
        assertEquals("helper 不调用 attach 建会话边界", 0, attachCalls)
        assertTrue(manager.attachedPids.isEmpty())
        assertTrue(manager.sessions.isEmpty())
    }

    class Handler(private val pid: Long, private val command: String) {
        fun getNativePid() = pid
        fun getCommandLine() = command
    }
    class Descriptor(private val handler: Any) { fun getProcessHandler() = handler }
    class StubProcess(private val id: Long) : Process() {
        private val done = CountDownLatch(1)
        override fun pid() = id
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int { done.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = done.await(timeout, unit)
        override fun exitValue(): Int { if (done.count > 0) throw IllegalThreadStateException(); return 0 }
        override fun destroy() { done.countDown() }
        override fun isAlive() = done.count > 0
    }
}
