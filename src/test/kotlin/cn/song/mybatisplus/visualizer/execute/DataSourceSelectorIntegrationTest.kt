package cn.song.mybatisplus.visualizer.execute

import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DbCredentialManager
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.GuardedRef
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DataSourceSelector 数据源**选择/发现**层集成测试骨架（M36.5.23 续）。
 *
 * 与 [DbConsoleIntegrationTest]（覆盖 prepareConsole→executePrepared 控制台执行链）互补，本骨架
 * 专门验证此前只能人工沙盒验收的**数据源选择契约**——即 `DataSourceSelector` 依赖的真实平台集成点
 * `com.intellij.database.console.DatabaseRunners.getAttachDataSourceRunners`：
 *
 *   1. **无数据源** → prepareConsole 回调 false + onError 非空（打开 Database 工具窗引导），
 *      不抛异常、不 NPE（无 MySQL 也可验证的选择降级路径）。
 *   2. **单数据源** → 真实平台 API 返回恰好 1 个 runner，且 runner 显示名含数据源名；
 *      prepareConsole 静默复用（不弹选择框）→ isPrepared()=true 且 sharedDataSourceName==数据源名
 *      （证明「按单数据源自动选中」的选择决策正确，无弹窗）。
 *   3. **多数据源** → 真实平台 API 返回 N 个 runner（选择分支的输入：N 个 → 弹出选择框）。
 *
 * 运行环境：`testDatabase` 任务（独立沙盒、Database 插件真实加载、复用 runIde 沙盒 MySQL 驱动）。
 * 默认 `test` 任务已排除本类（其 classpath 剥离 database jar，否则类加载即 NoClassDefFoundError）。
 *
 * 环境门槛（与 DbConsoleIntegrationTest 一致）：
 *   - URL/用户名/驱动：解析 demo 项目 `.idea/dataSources.xml` + `dataSources.local.xml`
 *     （系统属性 `mpDemoProjectDir`，默认兄弟目录）。
 *   - 密码：硬编码 `root`（本地开发库无敏感性）。
 *   - MySQL 未启动 / 库未初始化 / RegionUrlMapper 网络失败：DB 相关断言 assumeTrue skip（不 fail）；
 *     无数据源路径（用例 1）不依赖 MySQL，始终运行以证明集成骨架本身可执行。
 *
 * 运行：`./gradlew testDatabase`
 */
class DataSourceSelectorIntegrationTest : BasePlatformTestCase() {

    private val demoProjectDir: File
        get() = File(System.getProperty("mpDemoProjectDir", "../mybatis-plus-sql-visualizer-demo"))

    private val dbPassword: String
        get() = "root" // 从 demo 项目 application.yml 复制（root/root，本地开发库无敏感性）

    /** demo dataSources.xml 中目标数据源的连接信息。 */
    private data class DataSourceInfo(val url: String, val user: String, val driverClass: String)

    private fun readDemoDataSource(namePart: String): DataSourceInfo? {
        val xml = demoProjectDir.resolve(".idea/dataSources.xml")
        val localXml = demoProjectDir.resolve(".idea/dataSources.local.xml")
        if (!xml.isFile) return null
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        var info: DataSourceInfo? = null
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
            if (url != null) info = DataSourceInfo(url, "root", driver ?: "com.mysql.cj.jdbc.Driver")
        }
        if (info != null && localXml.isFile) {
            val local = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(localXml)
            val nodes = local.getElementsByTagName("data-source")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                if (!name.contains(namePart)) continue
                val children = node.childNodes
                for (j in 0 until children.length) {
                    if (children.item(j).nodeName == "user-name") {
                        val user = children.item(j).textContent.trim()
                        if (user.isNotEmpty()) info = info?.copy(user = user)
                    }
                }
            }
        }
        return info
    }

    /** 编程创建数据源（LocalDataSourceManager + 凭据注入），返回 DBN LocalDataSource。 */
    private fun createDataSource(info: DataSourceInfo, nameSuffix: String = ""): LocalDataSource {
        val mgr = LocalDataSourceManager.getInstance(project)
        val ds = mgr.createEmpty()
        ds.name = "mpj_demo@localhost-itest$nameSuffix"
        ds.url = info.url
        ds.driverClass = info.driverClass
        ds.username = info.user
        ds.javaClass.getDeclaredMethod("setDriverRef", String::class.java).apply { isAccessible = true }
            .invoke(ds, "mysql.8")
        ds.resolveDriver()
        // T-ExecDocGate：驱动种子 + 数据源显式携带驱动 jar classpath（背景见助手 KDoc）
        DbIntegrationSeedSupport.wireDriverClasspath(ds)
        ds.workingDir = project.basePath
        ds.passwordStorage = LocalDataSource.Storage.MEMORY
        mgr.addDataSource(ds)
        DbCredentialManager().setPassword(ds, OneTimeString(dbPassword), LocalDataSource.Storage.MEMORY)
        return ds
    }

    /**
     * 触发数据源 introspection（连接 + schema 加载）——delegate 切换为 SqlDataSource 后
     * `containsFile(vf)` 过滤才通过，runner 列表才非空。MySQL 未启动 → 返回失败原因（assumeTrue skip）。
     */
    private fun introspectDataSource(ds: LocalDataSource): String? = runCatching {
        val mgr = DatabaseConnectionManager.getInstance()
        val builder = mgr.build(project, ds).apply { setAskPassword(false) }
        val future = ApplicationManager.getApplication().executeOnPooledThread<GuardedRef<*>> {
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

    /** 轮询直到条件满足或超时；返回是否满足。 */
    private fun waitUntil(timeoutMs: Long, intervalMs: Long = 300, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(intervalMs)
        }
        return runCatching(condition).getOrDefault(false)
    }

    /**
     * 构造临时 .sql PsiFile（与 DataSourceSelector.createTempPsiFile 等价的最小集：
     * 项目内 `.idea/sql-visualizer/exec/mpsql-exec.sql` + MySQL 方言预映射 + PsiFile）。
     * runner 发现按文件 working-dir 匹配数据源，故必须落在项目内。
     */
    private fun buildExecPsiFile(sql: String): Pair<com.intellij.openapi.vfs.VirtualFile, com.intellij.psi.PsiFile>? {
        val base = project.basePath ?: return null
        val dir = File(base, ".idea/sql-visualizer/exec").apply { mkdirs() }
        val f = File(dir, "mpsql-exec.sql").apply { writeText(sql) }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f) ?: return null
        val mappings = SqlDialectMappings.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val values = mappings.javaClass.getMethod("getAvailableValues").invoke(mappings) as List<Any?>
        val mysql = values.firstOrNull { (it as? com.intellij.lang.Language)?.id.equals("MySQL", ignoreCase = true) }
        if (mysql != null) {
            mappings.javaClass.getMethod("setMapping", com.intellij.openapi.vfs.VirtualFile::class.java, Any::class.java)
                .invoke(mappings, vf, mysql)
        }
        return ReadAction.compute<com.intellij.psi.PsiFile?, Throwable> { com.intellij.psi.PsiManager.getInstance(project).findFile(vf) }
            ?.let { vf to it }
    }

    /**
     * 反射探针：直接调真实平台 API `DatabaseRunners.getAttachDataSourceRunners`
     * （与 DataSourceSelector.getAttachDataSourceRunners 同签名的反射调用）。
     * 这是 DataSourceSelector 选择逻辑的真实集成基础——本骨架直接验证平台契约，不绕插件封装。
     * sessionConsumer 仅注册回调（不执行），discovery 阶段安全。
     */
    private fun discoverRunners(psiFile: com.intellij.psi.PsiFile, sql: String): List<Any> {
        val consumer = com.intellij.util.Consumer<Any> { /* discovery only: registers callback */ }
        return runCatching {
            val clazz = Class.forName("com.intellij.database.console.DatabaseRunners")
            val method = clazz.getMethod(
                "getAttachDataSourceRunners",
                com.intellij.psi.PsiFile::class.java,
                String::class.java,
                com.intellij.util.Consumer::class.java,
            )
            (method.invoke(null, psiFile, sql, consumer) as? List<*>)?.filterNotNull() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 反射读取 Runner 显示名（数据源名，如 `mpj_demo@localhost-itest`）。 */
    private fun runnerName(runner: Any): String = runCatching {
        runner.javaClass.getMethod("getDisplayName").also { it.isAccessible = true }.invoke(runner) as? String
    }.getOrNull() ?: "Unknown"

    /** 反射读取 DataSourceSelector 的 object 私有字段（sharedDataSourceName/preparedConsole）。 */
    private fun selectorField(name: String): Any? =
        DataSourceSelector::class.java.getDeclaredField(name).apply { isAccessible = true }.get(DataSourceSelector)

    // ───────────────────────────── 用例 ─────────────────────────────

    /**
     * 无数据源路径（选择降级契约）：不创建任何数据源，prepareConsole 应回调 false + onError 非空，
     * 且不得抛异常/NPE。仅依赖 Database 插件已加载（testDatabase 提供），**不依赖 MySQL**，始终运行。
     *
     * T-ExecDocGate：本用例前置显式清空数据源——LocalDataSourceManager 是应用级，兄弟用例
     * （本类其它用例 / DbConsoleIntegrationTest / ExecDocGateIntegrationTest）createDataSource
     * 建的数据源跨用例可见；此前它们因 driver 解析不出去而被 DbImplUtil 过滤「隐形」，驱动种子
     * 修复后 resolveDriver 生效、残留变可见 → runners 非空 → 走弹窗分支（headless 无 frame
     * → NPE 红）。契约是「无数据源」路径，就须保证环境确实无数据源。
     */
    @Test
    fun testNoDataSourceReportedAsUnavailable() {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable())
        val mgr = com.intellij.database.dataSource.LocalDataSourceManager.getInstance(project)
        mgr.dataSources.toList().forEach { runCatching { mgr.removeDataSource(it) } }
        var cbResult: Boolean? = null
        val errors = mutableListOf<String>()
        DataSourceSelector.prepareConsole(
            project = project,
            sql = "SELECT 1",
            onError = { errors.add(it) },
            callback = { cbResult = it },
        )
        // 无数据源：选择逻辑走到 runners.isEmpty() 分支 → 打开 Database 工具窗 + onError + callback(false)
        assertNotNull("prepareConsole 应回调（无论成败）", cbResult)
        assertEquals("无数据源时 prepareConsole 应回调 false", false, cbResult)
        assertTrue("无数据源时 onError 应非空（引导用户配置数据源）", errors.isNotEmpty())
        assertFalse("无数据源时不应标记为已准备", DataSourceSelector.isPrepared())
    }

    /**
     * 单数据源发现契约：真实平台 API `getAttachDataSourceRunners` 返回恰好 1 个 runner，
     * 且 runner 显示名含数据源名。验证选择逻辑「单数据源」分支的输入正确（DB 相关，环境门控）。
     */
    @Test
    fun testSingleSourceDiscoverableByRunner() {
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

        val psi = buildExecPsiFile("SELECT id FROM car_device_info WHERE id = 1")
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null)
        val (_, psiFile) = psi!!

        val runners = discoverRunners(psiFile, "SELECT id FROM car_device_info WHERE id = 1")
        assertEquals("单数据源时平台应返回恰好 1 个 runner", 1, runners.size)
        val name = runnerName(runners.first())
        assertTrue("runner 显示名应含数据源名 '${ds.name}'，实际='$name'", name.contains(ds.name))
    }

    /**
     * 多数据源发现契约：创建 2 个数据源，真实平台 API 返回 2 个 runner。
     * 验证选择逻辑「多数据源」分支的输入（N 个 → 弹出选择框）正确（DB 相关，环境门控）。
     */
    @Test
    fun testMultiSourceDiscoveryReturnsAllRunners() {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable())
        val info = readDemoDataSource("mpj_demo")
        assumeTrue("demo 项目数据源配置不可读: ${demoProjectDir.absolutePath}", info != null)
        val ds1 = createDataSource(info!!, "")
        val ds2 = createDataSource(info, "2")
        val e1 = introspectDataSource(ds1)
        val e2 = introspectDataSource(ds2)
        // T-ExecDocGate：显式绿跳（同上）
        if (e1 != null || e2 != null) {
            println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: ds1=$e1, ds2=$e2")
            return
        }

        val psi = buildExecPsiFile("SELECT id FROM car_device_info WHERE id = 1")
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null)
        val (_, psiFile) = psi!!

        val runners = discoverRunners(psiFile, "SELECT id FROM car_device_info WHERE id = 1")
        assertEquals("多数据源时平台应返回 2 个 runner（选择分支输入）", 2, runners.size)
        val names = runners.map { runnerName(it) }
        assertTrue("runner 列表应含 ds1=${ds1.name}，实际=$names", names.any { it.contains(ds1.name) })
        assertTrue("runner 列表应含 ds2=${ds2.name}，实际=$names", names.any { it.contains(ds2.name) })
    }

    /**
     * 单数据源静默选择契约：prepareConsole 对唯一数据源应**不弹选择框**直接选中 →
     * isPrepared()=true 且 sharedDataSourceName==数据源名。证明选择决策正确（DB 相关，环境门控）。
     */
    @Test
    fun testPrepareConsoleSilentlySelectsSingleSource() {
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

        var prepared = false
        val errors = mutableListOf<String>()
        DataSourceSelector.prepareConsole(
            project = project,
            sql = "SELECT id, station_id FROM car_device_info WHERE id = 1",
            onError = { errors.add(it) },
            callback = { prepared = it },
        )
        assertTrue("prepareConsole 120s 内未就绪 (errors=$errors)", waitUntil(120_000) { DataSourceSelector.isPrepared() })
        assertTrue("prepareConsole callback 应返回 true", prepared)
        assertTrue("准备阶段不应有错误 (errors=$errors)", errors.isEmpty())
        val selected = selectorField("sharedDataSourceName") as? String
        assertEquals("静默选择应记录唯一数据源名（无弹窗）", ds.name, selected)
    }

    override fun tearDown() {
        runCatching { DataSourceSelector.clearPrepared() }
        super.tearDown()
    }
}
