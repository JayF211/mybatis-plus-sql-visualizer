package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 初始化入口/收尾窗口对项目服务级状态的守卫（TST02d 等自证）
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class BannedApiSourceScanServiceStateTest {

    @Test
    fun `初始化入口改项目服务级状态须复位`() {
        // T-StabilityTail T-ST-02d：把「改共享服务状态须复位」这条人工审计结论固化为源码扫描守卫。
        // 判据与误报边界见 [projectServiceStateOffenders] 的 KDoc。
        val sources = testSources()
        // 路径解析失败 = 空扫描面 = 规则恒 0 违规的**空绿**（自证见 TST02d自证_… 的空面样本）
        assertTestSourcesResolved(sources)
        val offenders = projectServiceStateOffenders(sources)
        assertTrue(
            "测试类在 setUp/@Before 里改了项目服务级共享状态（project.getService(…) 或 " +
                "AlreadyDisposedDiagnostics.projectService(…) 返回对象）却无**对该服务对象的复位调用**，" +
                "命中 ${offenders.size} 处：$offenders。light 测试项目在同一分片 JVM 内跨测试类复用同一个 " +
                "ProjectImpl，这类改动会串台到同片后续测试类（发作与否取决于分片邻居，新增测试类即重排）。" +
                "复位走 testutil.SqlLogManagerProjectState.capture（setUp）+ restore（tearDown）、" +
                "或服务自带的 clearForTest/resetForTest/loadState 等复位口（须调在承接该服务的标识符上）；" +
                "确属幂等安全或非共享实例的写，在写入行上方加 `// project-state: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `收尾窗口不得取项目服务`() {
        // T-AgentResiduals R2：见 [teardownServiceLookupOffenders] 的 KDoc。
        val sources = testSources()
        assertTestSourcesResolved(sources)
        val offenders = teardownServiceLookupOffenders(sources)
        assertTrue(
            "tearDown/@After 里出现项目服务查找，命中 ${offenders.size} 处：$offenders。" +
                "收尾正是「项目 temporarily-disposed」窗口的高发时段，此刻首次创建订阅 project.messageBus 的" +
                "服务会让平台把 CannotInitialize 缓存下来、该服务在本项目实例上永久重抛（同片后续用例成片变红）。" +
                "需要复原的共享服务请在 setUp 里连**引用**一起存（SqlLogManagerProjectState.capture 自持 manager " +
                "引用，restore(snapshot) 单参），tearDown 全程不碰服务容器；确需收尾探测的加 " +
                "`// project-state: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `R2自证_收尾窗口取服务必红_存快照形态放行`() {
        // 负向自证：违规必报 + 三类合法形态放行 + 豁免标注只豁免被标注的那一行。
        val violating = listOf(
            "src/test/kotlin/x/TearDownLookupSampleTest.kt" to """
                class TearDownLookupSampleTest : BasePlatformTestCase() {
                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(
                            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "tearDown.restore"),
                            null,
                        )
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        val found = teardownServiceLookupOffenders(violating)
        assertEquals("tearDown 里取项目服务必须命中：$found", 1, found.size)
        assertTrue(
            "命中点须锚在**取服务那一行**（行号靠保行掩码回推）：$found",
            found[0].endsWith("TearDownLookupSampleTest.kt:4 → tearDown 里的 projectService("),
        )

        // 合法形态 ①：R2 后的单参 restore（快照自持引用）——tearDown 零服务查找
        val restored = listOf(
            "src/test/kotlin/x/TearDownSnapshotSampleTest.kt" to """
                class TearDownSnapshotSampleTest : BasePlatformTestCase() {
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        state = SqlLogManagerProjectState.capture(project.getService(SqlLogManager::class.java))
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "R2 形态（快照持有引用、tearDown 只 restore）不得误报：${teardownServiceLookupOffenders(restored)}",
            0,
            teardownServiceLookupOffenders(restored).size,
        )

        // 合法形态 ②：@After 形态同样在扫描面内（且它才是不经平台基类的纯 JUnit 类收尾口）
        val afterForm = listOf(
            "src/test/kotlin/x/AfterSampleTest.kt" to """
                class AfterSampleTest {
                    @After
                    fun cleanup() {
                        JoinFkRegistry.clearForTest()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "@After 里不取服务不得误报：${teardownServiceLookupOffenders(afterForm)}",
            0,
            teardownServiceLookupOffenders(afterForm).size,
        )

        // 扫描面自证：@After 形态确实被提取（否则上面那个 0 是「入口没被识别」的空绿）
        assertEquals(
            "@After 入口应被提取：${teardownEntrySites(maskNonCode(afterForm[0].second))}",
            1,
            teardownEntrySites(maskNonCode(afterForm[0].second)).size,
        )

        // 豁免标注：只豁免被标注的那一行
        val annotated = listOf(
            "src/test/kotlin/x/AnnotatedTearDownSampleTest.kt" to """
                class AnnotatedTearDownSampleTest : BasePlatformTestCase() {
                    override fun tearDown() {
                        // project-state: 本类 setUp 已创建该服务，此处只读探测（不发起创建）
                        val m = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "tearDown.probe")
                        m.setCapturingForTest(false)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// project-state: <理由>` 标注的取服务应豁免：${teardownServiceLookupOffenders(annotated)}",
            0,
            teardownServiceLookupOffenders(annotated).size,
        )

        // 空扫描面自证：路径解析失败 = 空绿，主用例的非空断言必须能拦住
        assertEquals("空扫描面下规则恒 0 违规", 0, teardownServiceLookupOffenders(emptyList()).size)
    }

    @Test
    fun `TST02d自证_初始化入口写共享服务不复位必红_边界态放行`() {
        // 负向自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住）：违规必报 + 复位/标注/自建实例/
        // 只读四类放行 + 两条**漏报边界**如实钉住（方法体内写、capture-only）。
        val violating = listOf(
            "src/test/kotlin/x/LeakySampleTest.kt" to """
                class LeakySampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        val found = projectServiceStateOffenders(violating)
        assertEquals(
            "setUp 里改项目服务级状态且类内无复位调用必须命中：$found",
            1,
            found.size,
        )
        assertTrue(
            "命中点须锚在**写入那一行**（行号靠保行掩码回推，不得落在绑定行或入口行）：$found",
            found[0].endsWith("LeakySampleTest.kt:7 → manager.entries.dedupeEnabled ="),
        )

        // 变更类方法调用形态（既有泄漏点里 `setCapturingForTest` / `entries.clear()` 即此形态）
        val violatingCalls = listOf(
            "src/test/kotlin/x/LeakyCallSampleTest.kt" to """
                class LeakyCallSampleTest : BasePlatformTestCase() {
                    override fun setUp() {
                        super.setUp()
                        val manager = project.getService(SqlLogManager::class.java)
                        manager.setCapturingForTest(true)
                        manager.entries.clear()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "变更类方法调用（setXxx / clear）同样是写共享状态：${projectServiceStateOffenders(violatingCalls)}",
            2,
            projectServiceStateOffenders(violatingCalls).size,
        )

        // 复位调用（testutil 存复原，R2 单参形态：快照自持服务引用）→ 放行
        val restored = listOf(
            "src/test/kotlin/x/RestoredSampleTest.kt" to """
                class RestoredSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        state = SqlLogManagerProjectState.capture(manager)
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "类内有 capture/restore 存复原的写不得误报：${projectServiceStateOffenders(restored)}",
            0,
            projectServiceStateOffenders(restored).size,
        )

        // R2 形态的鉴别力（负例 1）：restore 的快照名**未在初始化入口由 capture 绑定** → 不算复位证据
        val unboundSnapshotRestore = listOf(
            "src/test/kotlin/x/UnboundSnapshotSampleTest.kt" to """
                class UnboundSnapshotSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "restore(<未由 capture 绑定的快照名>) 不得算复位证据（否则认令牌就等于一律放行）：" +
                "${projectServiceStateOffenders(unboundSnapshotRestore)}",
            1,
            projectServiceStateOffenders(unboundSnapshotRestore).size,
        )

        // R2 形态的鉴别力（负例 2）：快照是从**别的对象**（自建实例，非容器服务）捕获的 → 不算复位证据
        val snapshotFromOtherObject = listOf(
            "src/test/kotlin/x/SnapshotFromOtherSampleTest.kt" to """
                class SnapshotFromOtherSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        state = SqlLogManagerProjectState.capture(SqlLogManager(project))
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "快照从自建实例（SqlLogManager(project)，不进容器）捕获时不得豁免对共享服务的写：" +
                "${projectServiceStateOffenders(snapshotFromOtherObject)}",
            1,
            projectServiceStateOffenders(snapshotFromOtherObject).size,
        )

        // 自建实例（不进容器）→ 不判为泄漏
        val selfBuilt = listOf(
            "src/test/kotlin/x/SelfBuiltSampleTest.kt" to """
                class SelfBuiltSampleTest : BasePlatformTestCase() {
                    override fun setUp() {
                        super.setUp()
                        val manager = SqlLogManager(project)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "自建实例（SqlLogManager(project) 不进容器）不是共享状态，不得误报：${projectServiceStateOffenders(selfBuilt)}",
            0,
            projectServiceStateOffenders(selfBuilt).size,
        )

        // 只读访问（快照 / size / 属性读）→ 不判
        val readOnly = listOf(
            "src/test/kotlin/x/ReadOnlySampleTest.kt" to """
                class ReadOnlySampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager
                    private var before = 0

                    override fun setUp() {
                        super.setUp()
                        manager = project.getService(SqlLogManager::class.java)
                        before = manager.entries.snapshot().size
                        assertEquals(0, manager.entries.size())
                        assertFalse(manager.isWaitingForConsole)
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "只读访问（snapshot/size/属性读）不得误判为写：${projectServiceStateOffenders(readOnly)}",
            0,
            projectServiceStateOffenders(readOnly).size,
        )

        // `// project-state: <理由>` 标注 → 放行（豁免不得变成「一律放行」：只豁免被标注的那一行）
        val annotated = listOf(
            "src/test/kotlin/x/AnnotatedSampleTest.kt" to """
                class AnnotatedSampleTest : BasePlatformTestCase() {
                    override fun setUp() {
                        super.setUp()
                        val manager = project.getService(SqlLogManager::class.java)
                        // project-state: 该字段写入幂等（置为默认值），且本类不做状态断言
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// project-state: <理由>` 标注的写应豁免：${projectServiceStateOffenders(annotated)}",
            0,
            projectServiceStateOffenders(annotated).size,
        )

        // `@Before` 形态（纯 JUnit4 测试类）也在扫描面内
        val beforeForm = listOf(
            "src/test/kotlin/x/BeforeSampleTest.kt" to """
                class BeforeSampleTest {
                    private lateinit var manager: SqlLogManager

                    @Before
                    fun init() {
                        manager = project.getService(SqlLogManager::class.java)
                        manager.entries.maxEntries = 10
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "@Before 入口的写必须同样命中（初始化入口不只 setUp 一种）：${projectServiceStateOffenders(beforeForm)}",
            1,
            projectServiceStateOffenders(beforeForm).size,
        )

        // ---- 边界如实钉住（漏报方向，KDoc「已知边界」段登记）----
        // ① 测试方法体内的写不在扫描面（收编到「类内任意写」会误判 try/finally 重赋值复位的合法类）
        val methodBodyWrite = listOf(
            "src/test/kotlin/x/MethodBodySampleTest.kt" to """
                class MethodBodySampleTest : BasePlatformTestCase() {
                    fun testX() {
                        val manager = project.getService(SqlLogManager::class.java)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "边界登记：测试方法体内的写不在扫描面（本规则只覆盖初始化入口）：" +
                "${projectServiceStateOffenders(methodBodyWrite)}",
            0,
            projectServiceStateOffenders(methodBodyWrite).size,
        )
        // ② 只 capture 不 restore 是「半成品」——capture 不算复位证据，必须红
        val captureOnly = listOf(
            "src/test/kotlin/x/CaptureOnlySampleTest.kt" to """
                class CaptureOnlySampleTest : BasePlatformTestCase() {
                    private lateinit var state: SqlLogManagerProjectState.Snapshot

                    override fun setUp() {
                        super.setUp()
                        val manager = project.getService(SqlLogManager::class.java)
                        state = SqlLogManagerProjectState.capture(manager)
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "只 capture 不 restore 不算复位证据（存了快照忘了还原正是半成品形态）：" +
                "${projectServiceStateOffenders(captureOnly)}",
            1,
            projectServiceStateOffenders(captureOnly).size,
        )

        // ---- T-ST-02d 独立复核阻断 1：wrapper 取服务形态必须能绑定 ----
        // 复核实证：只认 `getService(` 时，把真泄漏类的 `restore` 行整行删掉规则仍 offenders=0 绿
        // （仓内 5 个已修泄漏类的 getService( 计数全为 0，全走 AlreadyDisposedDiagnostics.projectService）。
        val wrapperBound = listOf(
            "src/test/kotlin/x/WrapperBoundSampleTest.kt" to """
                class WrapperBoundSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.manager")
                        manager.entries.dedupeEnabled = false
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "wrapper 承接（AlreadyDisposedDiagnostics.projectService）必须与 getService 同等绑定——" +
                "否则删掉真泄漏类的 restore 行也不红（复核阻断 1 复核实证）：" +
                "${projectServiceStateOffenders(wrapperBound)}",
            1,
            projectServiceStateOffenders(wrapperBound).size,
        )
        assertTrue(
            "wrapper 形态的命中点须锚在**写入那一行**（不得落在 wrapper 承接行）：" +
                "${projectServiceStateOffenders(wrapperBound)}",
            projectServiceStateOffenders(wrapperBound)[0]
                .endsWith("WrapperBoundSampleTest.kt:7 → manager.entries.dedupeEnabled ="),
        )

        // wrapper 承接 + wrapper 全量复位（R2 单参形态，快照由 wrapper 取服务表达式捕获）→ 绿
        val wrapperRestored = listOf(
            "src/test/kotlin/x/WrapperRestoredSampleTest.kt" to """
                class WrapperRestoredSampleTest : BasePlatformTestCase() {
                    private var state: SqlLogManagerProjectState.Snapshot? = null

                    override fun setUp() {
                        super.setUp()
                        state = SqlLogManagerProjectState.capture(
                            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.capture")
                        )
                        AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.write")
                            .entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        SqlLogManagerProjectState.restore(state)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "wrapper 取服务（换行形态）+ SqlLogManagerProjectState.restore(<快照名>) 全量复位不得误报：" +
                "${projectServiceStateOffenders(wrapperRestored)}",
            0,
            projectServiceStateOffenders(wrapperRestored).size,
        )

        // ---- T-ST-02d 独立复核阻断 2：**别的对象**的复位调用不得洗掉真泄漏 ----
        // 复核实测（旧口径按文件级 contains 判定）：下面四个复位样本各自都能把真泄漏洗成绿。
        val otherObjectResets = listOf(
            "src/test/kotlin/x/OtherObjectResetSampleTest.kt" to """
                class OtherObjectResetSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.manager")
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        PluginSettings.loadState(snapshot)
                        JoinFkRegistry.clearForTest()
                        PluginCacheManager.clearAll(project)
                        FirstCaptureGuide.resetForTest()
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "另一个对象的复位调用（PluginSettings.loadState / JoinFkRegistry.clearForTest / " +
                "PluginCacheManager.clearAll / FirstCaptureGuide.resetForTest）不得豁免本类对共享服务的写" +
                "（旧口径下这四个样本把真泄漏洗成绿——复核阻断 2 复核实证）：" +
                "${projectServiceStateOffenders(otherObjectResets)}",
            1,
            projectServiceStateOffenders(otherObjectResets).size,
        )

        // 承接名自身的复位口仍算复位证据（绑定判定不得把覆盖面一起砍掉）——真实形态：
        // RollbackSnapshotStoreTest 的 `project.getService(RollbackSnapshotStore::class.java)` + clearForTest()
        val boundServiceReset = listOf(
            "src/test/kotlin/x/BoundResetSampleTest.kt" to """
                class BoundResetSampleTest : BasePlatformTestCase() {
                    private lateinit var store: RollbackSnapshotStore

                    override fun setUp() {
                        super.setUp()
                        store = project.getService(RollbackSnapshotStore::class.java)
                        store.markRollbackExecuted(entry)
                    }

                    override fun tearDown() {
                        store.clearForTest()
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "承接名自身的复位口（store.clearForTest()）必须仍算复位证据（② 选型不得丢覆盖面）：" +
                "${projectServiceStateOffenders(boundServiceReset)}",
            0,
            projectServiceStateOffenders(boundServiceReset).size,
        )
        // 防「复位口样本空绿」：同一份样本去掉复位行必须红——证明上面那个 0 是「写被识别 + 复位被认」，
        // 而不是「写形态没命中」的空绿（该样本的写形态与前面几个样本不同，无法靠它们互证）。
        val boundResetMissing = listOf(
            boundServiceReset[0].first to boundServiceReset[0].second.replace("store.clearForTest()", ""),
        )
        assertEquals(
            "同一份样本去掉 store.clearForTest() 必须红（否则上一条 0 是空绿）：" +
                "${projectServiceStateOffenders(boundResetMissing)}",
            1,
            projectServiceStateOffenders(boundResetMissing).size,
        )

        // ---- T-ST-02d 独立复核条件项：部分复位（3/15 字段）不算复位证据 ----
        // SqlLogManager.restoreCaptureStateForTest 只回滚 capturing/userArmed/waitingForConsole，
        // 而 SqlLogManagerProjectState.capture 快照 15 个字段（buffer 字段全不在内）——故已从复位令牌排除。
        val partialReset = listOf(
            "src/test/kotlin/x/PartialResetSampleTest.kt" to """
                class PartialResetSampleTest : BasePlatformTestCase() {
                    private lateinit var manager: SqlLogManager

                    override fun setUp() {
                        super.setUp()
                        manager = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.manager")
                        manager.entries.dedupeEnabled = false
                    }

                    override fun tearDown() {
                        manager.restoreCaptureStateForTest(false, false, false)
                        super.tearDown()
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "部分复位（restoreCaptureStateForTest 只回滚 3/15 字段）不足以豁免对 buffer 字段的写，必须红：" +
                "${projectServiceStateOffenders(partialReset)}",
            1,
            projectServiceStateOffenders(partialReset).size,
        )

        // ---- 空扫描面自证：路径解析失败 = 空绿，主用例的非空断言必须能拦住 ----
        assertEquals(
            "空扫描面下规则恒 0 违规——这正是「路径解析失败 = 静默空绿」的机理",
            0,
            projectServiceStateOffenders(emptyList()).size,
        )
        var emptyScanRed = false
        try {
            assertTestSourcesResolved(emptyList())
        } catch (e: AssertionError) {
            emptyScanRed = true
        }
        assertTrue(
            "扫描面为空时非空断言必须变红（否则 src/test/kotlin 路径解析失败会静默空绿）",
            emptyScanRed,
        )

        // 工具函数自证：初始化入口提取覆盖 setUp / @Before 两种形态，行号与原文对齐（保行掩码）
        val setUpSites = initEntrySites(maskNonCode(violating[0].second))
        assertEquals("setUp 入口应被提取：$setUpSites", 1, setUpSites.size)
        assertEquals("入口行号须与原文对齐（保行掩码）", 4, setUpSites[0].line)
        assertEquals("入口名", "setUp", setUpSites[0].name)
        val beforeSites = initEntrySites(maskNonCode(beforeForm[0].second))
        assertEquals("@Before 入口应被提取：$beforeSites", 1, beforeSites.size)
        assertEquals("入口名取函数名", "init", beforeSites[0].name)
    }
}
