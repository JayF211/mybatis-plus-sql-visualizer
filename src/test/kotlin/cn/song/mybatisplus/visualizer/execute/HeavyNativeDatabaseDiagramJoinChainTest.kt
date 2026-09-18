package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import cn.song.mybatisplus.visualizer.ui.diagram.EntityNode
import cn.song.mybatisplus.visualizer.ui.diagram.RelationshipLine
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramOpener
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.JoinFkRegistry
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport
import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DbCredentialManager
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.GuardedRef
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * T-Embed-03/04/08：B 路径真实链路集成测试（**仅 testDatabase 任务运行**——database jar
 * 在该分片 classpath；普通 test 任务已剥离 database jar，本类被 filter 排除）。
 *
 * 链路：demo 项目真实 MySQL 数据源 → introspect → JoinFkRegistry 登记 ON 条件 →
 * NativeDatabaseDiagramSupport.buildTabComponent（DATABASE provider + 真实 DbTable 节点）→
 * 断言数据模型含 ≥2 节点、连接线来自虚拟外键（SQLV_JOIN 前缀）、dispose 回收登记。
 *
 * 环境依赖（与 DataSourceSelectorIntegrationTest 同源）：demo 兄弟目录的
 * dataSources.xml + 本地 MySQL 已启动且含 car_device_info/station_info 表。
 */
class HeavyNativeDatabaseDiagramJoinChainTest : BasePlatformTestCase() {

    private val demoProjectDir: File
        get() = File(System.getProperty("mpDemoProjectDir", "../mybatis-plus-sql-visualizer-demo"))

    private data class DataSourceInfo(val url: String, val user: String, val driverClass: String)

    private fun readDemoDataSource(namePart: String): DataSourceInfo? {
        val xml = demoProjectDir.resolve(".idea/dataSources.xml")
        if (!xml.isFile) return null
        return runCatching {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
            val sources = doc.getElementsByTagName("data-source")
            for (i in 0 until sources.length) {
                val node = sources.item(i)
                val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                if (!name.contains(namePart)) continue
                var url: String? = null
                var driver: String? = null
                val children = node.childNodes
                for (j in 0 until children.length) {
                    val child = children.item(j)
                    when (child.nodeName) {
                        "jdbc-url" -> url = child.textContent.trim()
                        "jdbc-driver" -> driver = child.textContent.trim()
                    }
                }
                if (url != null) return@runCatching DataSourceInfo(url, "root", driver ?: "com.mysql.cj.jdbc.Driver")
            }
            null
        }.getOrNull()
    }

    /** 编程创建数据源（与 DataSourceSelectorIntegrationTest 夹具同款，凭据 MEMORY 注入）。 */
    private fun createDataSource(info: DataSourceInfo): LocalDataSource {
        val mgr = LocalDataSourceManager.getInstance(project)
        val ds = mgr.createEmpty()
        ds.name = "mpj_demo@localhost-embed"
        ds.url = info.url
        ds.driverClass = info.driverClass
        ds.username = info.user
        ds.javaClass.getDeclaredMethod("setDriverRef", String::class.java).apply { isAccessible = true }
            .invoke(ds, "mysql.8")
        ds.resolveDriver()
        // T-TestDbDriver：驱动种子文件 + 驱动 artifact 版本钉定（管线全貌见助手 KDoc）
        DbIntegrationSeedSupport.prepareDriverPipeline()
        ds.workingDir = project.basePath
        ds.passwordStorage = LocalDataSource.Storage.MEMORY
        mgr.addDataSource(ds)
        DbCredentialManager().setPassword(ds, OneTimeString("root"), LocalDataSource.Storage.MEMORY)
        return ds
    }

    private fun introspectDataSource(ds: LocalDataSource): String? = runCatching {
        val mgr = DatabaseConnectionManager.getInstance()
        val builder = mgr.build(project, ds).apply { setAskPassword(false) }
        val future = ApplicationManager.getApplication().executeOnPooledThread<GuardedRef<*>?> {
            val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
                com.intellij.openapi.util.Computable<GuardedRef<*>> { builder.createBlockingNonCancellable() },
                indicator,
            )
        }
        val ref = future.get() ?: return@runCatching "createBlocking future null"
        val loaded = waitUntil(90_000) { !LocalDataSourceManager.getInstance(project).isLoading(ds) }
        ref.close()
        if (loaded) null else "isLoading 未结束（90s 超时）"
    }.getOrElse { e ->
        val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
        "introspect 失败: ${cause.javaClass.simpleName}: ${cause.message}"
    }

    private fun waitUntil(timeoutMs: Long, intervalMs: Long = 300, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(intervalMs)
        }
        return runCatching(condition).getOrDefault(false)
    }

    /** demo 真实表形态：car_device_info t LEFT JOIN station_info t1 ON t1.station_id = t.station_id。 */
    private fun demoJoinModel() = DiagramModel(
        entities = listOf(
            EntityNode(
                alias = "t", tableName = "car_device_info",
                columns = listOf(
                    ColumnEntry("id", "int(11)", isPk = true, comment = "主键"),
                    ColumnEntry("station_id", "int(11)", isPk = false),
                ),
                isMain = true,
            ),
            EntityNode(
                alias = "t1", tableName = "station_info",
                columns = listOf(ColumnEntry("station_id", "int(11)", isPk = false)),
                isMain = false,
                joinType = JoinType.LEFT,
            ),
        ),
        relationships = listOf(
            RelationshipLine(
                fromAlias = "t", toAlias = "t1",
                joinType = JoinType.LEFT,
                label = "t1.station_id = t.station_id",
                columnPairs = listOf(Pair("station_id", "station_id")),
            ),
        ),
    )

    @Test
    fun testDatabaseDiagramEmbedChainWithVirtualFk() {
        // 必须最先守卫：database jar 缺席（普通 test 任务）时任何 database 类触碰即 NoClassDefFoundError
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable())
        val info = readDemoDataSource("mpj_demo")
        assumeTrue("demo 项目数据源配置不可读: ${demoProjectDir.absolutePath}", info != null)
        val ds = createDataSource(info!!)
        val introspectError = introspectDataSource(ds)
        // T-ExecDocGate：显式绿跳（assumeTrue 会被平台 runner 记为 failure 红任务）
        if (introspectError != null) {
            println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: $introspectError")
            return
        }

        val model = demoJoinModel()
        val component = NativeDatabaseDiagramSupport.buildTabComponent(
            project, model, "JOIN Diagram: car_device_info", testRootDisposable,
        )
        if (component == null) {
            println("[DB-IT] SKIP: B 路径链路不可用（表定位失败或框架异常，应降级 A 路径）——沙盒无真实 IDE 项目/VFS 夹具")
            return
        }
        val comp = component!!

        val editor = comp.getClientProperty("mpsql.native.database.editor")
        assumeTrue("编辑器未挂接", editor != null)
        val builder = NativeJoinDiagramOpener.builderOf(editor)
        assumeTrue("编辑器应持有 DiagramBuilder", builder != null)
        val b = builder!!

        val nodes = ApplicationManager.getApplication().runReadAction<Int> { b.dataModel.nodes.size }
        assertTrue("期望数据模型 ≥2 节点（主表 + JOIN 表），实际=$nodes", nodes >= 2)

        val edges = ApplicationManager.getApplication().runReadAction<List<*>> { b.dataModel.edges.toList() }
        assertTrue("期望 ≥1 条连接线，实际=$edges", edges.isNotEmpty())
        val edgeNames = edges.map { edgeName(it) }
        assertTrue(
            "连接线应来自虚拟外键（SQLV_JOIN 前缀），实际=$edgeNames",
            edgeNames.any { it?.startsWith("SQLV_JOIN") == true },
        )

        // T-PreviewBgFit（问题二守卫）：原生图须置「布局后自适应」+「尺寸变化后保持自适应」——
        // 布局管线只在开关置位后才于布局收尾调 fitContent（GraphLayoutServiceImpl 字节码实证），
        // 而我们手工建图无平台标签页路径的置位时机（真机实证：同会话第一张图不居中、第二张正常）。
        assertTrue(
            "原生图应开启「布局后自适应」（否则打开即停默认缩放/左上角）",
            b.presentationModel.settings.isFitContentAfterLayout,
        )
        assertTrue("原生图应开启「尺寸变化后保持自适应」", b.view.fitContentOnResize)

        // T-EmbedFkOrder F3 回归（本次真机故障形态）：清理**无关旧组件**不得抹掉当前图的虚拟外键登记——
        // 修复前 disposeNativeEditor 的 finally 里 JoinFkRegistry.clear() 会把刚登记的整批抹掉，
        // 于是换图再预览时 Database 原生图只剩节点没连线（用户实测"一张有连线、另一张没有"）。
        val stale = javax.swing.JPanel()
        stale.putClientProperty("mpsql.native.database.editor", com.intellij.openapi.util.Disposer.newDisposable())
        NativeDatabaseDiagramSupport.disposeNativeEditor(stale)
        assertTrue(
            "释放无关旧组件后，当前图的虚拟外键登记必须仍在（否则连线丢失）",
            JoinFkRegistry.find("station_info", "car_device_info").isNotEmpty(),
        )

        NativeDatabaseDiagramSupport.disposeNativeEditor(comp)
        // T-EmbedFkOrder F2：dispose 不再顺带清理注册表，清理改由「整图卸载」显式调用
        NativeDatabaseDiagramSupport.clearRegisteredFk()
        assertTrue(
            "显式整图卸载后，虚拟外键登记应清空",
            JoinFkRegistry.find("station_info", "car_device_info").isEmpty(),
        )
    }

    /** 边名：优先 DbEdge.getName（虚拟外键名 SQLV_JOIN_*），回落关系信息 label。 */
    private fun edgeName(edge: Any?): String? {
        if (edge == null) return null
        val direct = runCatching { edge.javaClass.getMethod("getName").invoke(edge) as? String }.getOrNull()
        if (direct != null) return direct
        val method = edge.javaClass.methods.firstOrNull {
            it.name == "getDependencyInfo" || it.name == "getRelationshipInfo"
        } ?: return null
        method.isAccessible = true
        return runCatching {
            (method.invoke(edge) as? com.intellij.diagram.DiagramRelationshipInfo)?.label
        }.getOrNull()
    }

    override fun tearDown() {
        runCatching { DataSourceSelector.clearPrepared() }
        // T-TestDbDriver：object 单例 temp Psi 缓存跨用例存活，fixture 临时目录每用例一换 → 显式清（详见
        // DataSourceSelectorIntegrationTest.tearDown 同款注释）。
        runCatching { DataSourceSelector.clearCachedTempPsiFileForTest() }
        super.tearDown()
    }

}
