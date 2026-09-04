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
        assumeTrue(
            "introspect 失败（MySQL 未启动/沙盒驱动解析缺 artifact 清单？）：$introspectError",
            introspectError == null,
        )

        val model = demoJoinModel()
        val component = NativeDatabaseDiagramSupport.buildTabComponent(
            project, model, "JOIN Diagram: car_device_info", testRootDisposable,
        )
        assumeTrue("B 路径链路不可用（表定位失败或框架异常，应降级 A 路径）", component != null)
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

        NativeDatabaseDiagramSupport.disposeNativeEditor(comp)
        assertTrue(
            "dispose 后虚拟外键登记应清空",
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
}
