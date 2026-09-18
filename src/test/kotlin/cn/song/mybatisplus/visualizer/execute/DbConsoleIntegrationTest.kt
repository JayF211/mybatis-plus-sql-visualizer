package cn.song.mybatisplus.visualizer.execute

import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DbCredentialManager
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.GuardedRef
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.SQLException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DB 控制台端到端集成测试（M36.5.23）——**模拟 Ultimate 沙盒真实环境**。
 *
 * 与单测（test 任务）的区别：本测试跑在 `testDatabase` 任务（独立沙盒、Database 插件
 * 真实加载、复用 runIde 沙盒已下载的 MySQL 驱动），覆盖此前只能人工沙盒验收的链路：
 *
 *   prepareConsole（数据源静默复用 → session → attachConsole → 原生控制台嵌入）
 *   → executePrepared（原生链 doRunQueryInConsole → 真实 MySQL 连接 → 结果 Tab）
 *   → 模拟参数变更（编辑器文档 setText）→ 再次 executePrepared
 *   → **断言新结果 Tab 出现**（「滞后一轮」端到端回归，M36.5.22/22b 修复的行为级验证）
 *
 * 连接信息来源（全自动，无硬编码）：
 *   - URL/用户名/驱动：解析 demo 项目 `.idea/dataSources.xml` + `dataSources.local.xml`
 *     （系统属性 `mpDemoProjectDir`，默认兄弟目录）
 *   - 密码：系统属性 `mpTestDbPassword`（本机 ~/.gradle/gradle.properties，不进 git）；
 *     缺失时 skip（不 fail）——配置一次后全量可跑。
 *
 * 运行：`./gradlew testDatabase`
 */
class DbConsoleIntegrationTest : BasePlatformTestCase() {

    /** introspect 期间持有的连接守卫（close 即释放连接，见 [introspectDataSource] 内注释）。 */
    private var introspectRef: GuardedRef<*>? = null

    /** 控制台编辑器夹具的持有标记（造/释放见 [DbIntegrationProjectSupport.installExecEditor]）。 */
    private var execEditor: Editor? = null

    /**
     * 稳定 light 工程目录（testDatabase 通道五类共用，根因见 [DbIntegrationProjectSupport] 类注释）：
     * 默认描述符下 `project.basePath` = JVM 里首个用例的 unitTest 临时目录，被属主用例 tearDown
     * 递归删除 → 后续用例的 exec 文件写盘与 VFS 分叉（基线 `refreshVfs: VFS not visible after refresh`）。
     */
    override fun getProjectDescriptor(): LightProjectDescriptor = DbIntegrationProjectSupport.descriptor

    override fun setUp() {
        super.setUp()
        DbIntegrationProjectSupport.allowRootAccess(testRootDisposable)
    }

    private val demoProjectDir: File
        get() = File(System.getProperty("mpDemoProjectDir", "../mybatis-plus-sql-visualizer-demo"))

    private val dbPassword: String
        get() = "root" // M36.5.23：从 demo 项目 application.yml 复制（root/root，本地开发库无敏感性）

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
        // 用户名在 local.xml（<user-name>）
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
    private fun createDataSource(info: DataSourceInfo): LocalDataSource {
        val mgr = LocalDataSourceManager.getInstance(project)
        val ds = mgr.createEmpty()
        ds.name = "mpj_demo@localhost-itest"
        ds.url = info.url
        ds.driverClass = info.driverClass
        ds.username = info.user
        // driverRef="mysql.8" + resolveDriver()：仅 setDriverClass 不解析 DatabaseDriver，
        // getAppropriateDataSourcesForExec 按 driver.dialect 过滤会跳过 driver=null 的数据源
        //（diag 实证：driver=null → runner 列表空）。setDriverRef 包私有——反射调。
        ds.javaClass.getDeclaredMethod("setDriverRef", String::class.java).apply { isAccessible = true }
            .invoke(ds, "mysql.8")
        ds.resolveDriver()
        // T-TestDbDriver：驱动种子文件 + 驱动 artifact 版本钉定（管线全貌见助手 KDoc）
        DbIntegrationSeedSupport.prepareDriverPipeline()
        // M36.5.23d：driver 文件由 DBN 从 config_<task>/jdbc-drivers 加载（build.gradle.kts doFirst
        // 预置）——不再需要手动 setClasspathElements（曾指向错误路径 config/ 无效）。
        // working-dir = 项目根目录：DbImplUtil.getAppropriateDataSourcesForExec(psiFile) 按文件
        // 所在项目目录匹配数据源 working-dir，未设则临时 mpsql-exec.sql 不在范围内 → runner 列表空。
        ds.workingDir = project.basePath
        // Storage.MEMORY：密码仅存内存（不污染沙盒密钥链，避免跨测试串扰）
        ds.passwordStorage = LocalDataSource.Storage.MEMORY
        mgr.addDataSource(ds)
        DbCredentialManager().setPassword(ds, OneTimeString(dbPassword), LocalDataSource.Storage.MEMORY)
        return ds
    }

    /**
     * 触发数据源 introspection（连接 + schema 加载）——未 introspection 时
     * `ds.getDelegate()` 返回 LocalDataSource（不实现 SqlDataSource），`lambda$4` 的
     * `checkcast SqlDataSource` 失败 → `containsFile(vf)` 过滤返回 false → runner 列表空
     * （M36.5.23b diag 实证）。建连后 DBN 自动 introspect，delegate 切换为 SqlDataSource 实例。
     *
     * MySQL 未启动 / 库未初始化 / RegionUrlMapper 网络失败：返回失败原因（调用方 assumeTrue skip）。
     *
     * @return null=成功；非 null=失败原因（用于 assumeTrue message）
     */
    private fun introspectDataSource(ds: LocalDataSource): String? = runCatching {
        val proxyHost = System.getProperty("http.proxyHost")
        "proxy=$proxyHost:${System.getProperty("http.proxyPort")}".also {
            // 诊断：确认代理 system property 传入测试 JVM
        }
        val mgr = DatabaseConnectionManager.getInstance()
        val builder = mgr.build(project, ds).apply { setAskPassword(false) }
        val future = ApplicationManager.getApplication().executeOnPooledThread<GuardedRef<*>> {
            // createBlocking 要求线程有 ProgressIndicator——用 runProcess 注入上下文
            val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
                com.intellij.openapi.util.Computable<GuardedRef<*>> { builder.createBlockingNonCancellable() },
                indicator,
            )
        }
        val ref = future.get() ?: return@runCatching "createBlocking future null"
        val loaded = waitUntil(90_000) { !LocalDataSourceManager.getInstance(project).isLoading(ds) }
        // 不在此 close：GuardedRef.close() 释放连接（DatabaseConnectionManager.releaseConnection 语义，
        // 见 Builder.createBlockingNonCancellable 返回 GuardedRef<DatabaseConnection>），引擎随之终止；
        // 控制台/session 是连接的下游——连接先死则控制台 attach 后立刻被 DBN 回收（20260918 实测
        // `console DISPOSED right after attach: disposed=true`）。守卫留到 tearDown 关闭，
        // 等价真机里用户会话持着连接（用例结束即释放，不跨用例泄漏）。
        introspectRef = ref
        if (loaded) null else "isLoading 未结束（90s 超时）"
    }.getOrElse { e ->
        val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
        "introspect 失败 (proxy=${System.getProperty("http.proxyHost")}:${System.getProperty("http.proxyPort")}): ${cause.javaClass.simpleName}: ${cause.message}"
    }

    /** 反射读取 DataSourceSelector 的 object 字段（preparedEditor/preparedUi）。 */
    private fun selectorField(name: String): Any? =
        DataSourceSelector::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(DataSourceSelector)

    /** 当前结果 Tab 名称列表（排除 Output）。 */
    private fun resultTabNames(): List<String> {
        val ui = selectorField("preparedUi") ?: return emptyList()
        val contents = runCatching {
            ui.javaClass.getMethod("getContents").invoke(ui) as? Array<com.intellij.ui.content.Content>
        }.getOrNull() ?: return emptyList()
        return contents.map { it.displayName }
            .filter { !it.equals("Output", ignoreCase = true) && !it.equals("输出", ignoreCase = true) }
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
     * 端到端：准备 → 执行 → 改参 → 再执行 → 断言新结果 Tab。
     * 「滞后一轮」回归：第二次执行后必须出现与第一次**不同**的结果 Tab（M36.5.22b 行为）。
     */
    @Test
    fun testPrepareExecuteAndParameterChangeEndToEnd() {
        // 前置：Database 插件已加载（testDatabase 任务不排 jar）+ demo 项目可达。
        // 注意：密码 assume 放在「执行」前——无密码时 prepare 层（插件/数据源/控制台准备）仍被验证。
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable())
        val info = readDemoDataSource("mpj_demo")
        assumeTrue("demo 项目数据源配置不可读: ${demoProjectDir.absolutePath}", info != null)
        val dsInfo = info!!

        // ⓪ 清空应用级残留数据源：LocalDataSourceManager 跨用例存活，本用例走的是「唯一数据源静默
        //    复用」契约，多一条残留即退化成多 runner 弹窗分支（headless 无 frame → NPE，基线实证）
        DbIntegrationProjectSupport.clearDataSources(project)

        // ① 创建数据源（连接信息来自 demo 项目）
        val ds = createDataSource(dsInfo)

        // ①b 触发 introspection：让 delegate 切换为 SqlDataSource（否则 containsFile 过滤失败）。
        // MySQL 未启动 / 库未初始化 / RegionUrlMapper 网络失败 → skip 余下场景（不 fail）
        val introspectError = introspectDataSource(ds)
        // T-ExecDocGate：显式绿跳——平台 runner 把 AssumptionViolatedException 记为 failure 红任务
        if (introspectError != null) {
            println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: $introspectError")
            return
        }

        // 本用例执行 SQL（诊断 dump 与准备链共用同一文本）
        val execSql = "SELECT id, station_id FROM car_device_info WHERE id = 1"

        // 诊断 dump 只在断言失败时求值（见 [runnerDiag]）：该 dump 自身会写 exec 文件、重建 PSI、
        // 重设方言映射，放在准备链之前会污染被测状态（20260918 实测：前置 dump 让控制台 attach 后
        // 即被 DBN 回收——排查代码本身成了故障源）
        // ② prepareConsole：唯一数据源 → 静默复用 → 控制台就绪回调
        // ②a 前置：发现链收敛到恰好 1 个 runner（本用例契约是「唯一数据源静默复用」，多一条残留即
        //     走弹窗分支、少一条即回调 false；facade 相对 LocalDataSourceManager 有异步窗口，见
        //     DbIntegrationProjectSupport.awaitRunners KDoc）
        val execPsi = DbIntegrationProjectSupport.ensureExecFile(project, execSql)
        assertNotNull("临时执行 PsiFile 应可构造（VFS/方言映射）", execPsi)
        val runners = DbIntegrationProjectSupport.awaitRunners(execPsi!!.second, execSql, expected = 1)
        assertEquals(
            "唯一数据源契约要求发现链恰好 1 个 runner（实际=${runners.map { DbIntegrationProjectSupport.runnerName(it) }}）",
            1,
            runners.size,
        )
        // ②b 控制台编辑器夹具：控制台文档即 exec 文件文档（JdbcConsole.getDocument() 字节码实证），
        //     headless 下无人为它建编辑器 → prepareInConsole 的 getEditors(consoleDocument) 恒空 →
        //     静默返回（基线 idea.log `[M36] editor=null (async wait)`，120s 不就绪且 errors=[]）
        execEditor = DbIntegrationProjectSupport.installExecEditor(project)
        assertNotNull("控制台编辑器夹具应可建（prepareConsole 依赖 getEditors(consoleDocument) 非空）", execEditor)

        var consoleReady = false
        val errors = mutableListOf<String>()
        var prepared = false
        DataSourceSelector.prepareConsole(
            project = project,
            sql = execSql,
            onConsole = { consoleReady = true },
            onEditor = { },
            onError = { errors.add(it) },
            callback = { prepared = it },
        )
        val consoleReadyInTime = DbIntegrationProjectSupport.waitWithPump(120_000) { consoleReady }
        if (!consoleReadyInTime) {
            println("[DB-IT] prepareConsole 未就绪诊断: ${DbIntegrationProjectSupport.diagnoseConsoleState(project, ds)}")
            println("[DB-IT] runner diag: " + runnerDiag(execSql))
        }
        assertTrue("prepareConsole 120s 内未就绪 (errors=$errors)", consoleReadyInTime)
        assertTrue("prepareConsole callback 应返回 true", prepared)
        assertTrue("控制台就绪后 isPrepared() 应为 true", DataSourceSelector.isPrepared())
        assertTrue("prepare 阶段不应有错误 (errors=$errors)", errors.isEmpty())

        // ③ executePrepared：真实 MySQL 执行 → 结果 Tab 出现（需要密码；未配置则 skip 余下场景）
        assumeTrue(
            "未配置 mpTestDbPassword——请在 ~/.gradle/gradle.properties 添加 mpTestDbPassword=<MySQL密码>" +
                "（无密码时仅验证 prepare 层）",
            dbPassword.isNotEmpty(),
        )
        var resultsReady = false
        val execErrors = mutableListOf<String>()
        DataSourceSelector.executePrepared(
            project = project,
            onResultsReady = { resultsReady = true },
            onError = { execErrors.add(it) },
        )
        assertTrue(
            "首次执行 60s 内无结果 Tab (errors=$execErrors)",
            DbIntegrationProjectSupport.waitWithPump(60_000) { resultTabNames().isNotEmpty() },
        )
        assertTrue("onResultsReady 应被回调", resultsReady)
        val firstTabs = resultTabNames()
        assertTrue("首次执行后应有结果 Tab (tabs=$firstTabs)", firstTabs.isNotEmpty())
        val firstTabName = firstTabs.last()

        // ④ 模拟参数变更：编辑器文档改为 id = 2（面板 executeSql 同步文档的等价操作）
        val editor = selectorField("preparedEditor") as com.intellij.openapi.editor.Editor
        com.intellij.openapi.application.WriteAction.runAndWait<Throwable> {
            editor.document.setText("SELECT id, station_id FROM car_device_info WHERE id = 2")
        }

        // ⑤ 再次执行：断言出现**新的**结果 Tab（滞后一轮回归点）
        var secondReady = false
        DataSourceSelector.executePrepared(
            project = project,
            onResultsReady = { secondReady = true },
            onError = { execErrors.add(it) },
        )
        val newTabAppeared = DbIntegrationProjectSupport.waitWithPump(60_000) { resultTabNames().any { it != firstTabName } }
        val secondTabs = resultTabNames()
        assertTrue(
            "第二次执行后未出现新结果 Tab（滞后一轮回归）：first=$firstTabName, second=$secondTabs, errors=$execErrors",
            newTabAppeared,
        )
        assertTrue("第二次执行 onResultsReady 应被回调", secondReady)
        assertTrue("集成链路不应有错误回调 (errors=$execErrors)", execErrors.isEmpty())

        // ⑥ 清理：释放控制台引用（不影响 IDE 管理的 session 生命周期）
        DataSourceSelector.clearPrepared()
        assertEquals("clearPrepared 后 isPrepared() 应为 false", false, DataSourceSelector.isPrepared())
        assertNotNull(dsInfo)
    }


    /**
     * runner 发现链诊断 dump（**惰性**：只在断言失败时求值）。
     *
     * 内容全是「读状态 + 复现 createTempPsiFile 的 4 步」：复现步骤会写 exec 文件、重建 PSI、
     * 重设方言映射——这些写点若发生在 prepareConsole 之前，会与被测准备链互相干扰
     *（20260918 实测：前置 dump 让控制台 attach 后即被 DBN 回收，排查代码成了故障源）。
     * 故改为失败时求值：诊断能力不减，被测状态不再被诊断代码扰动。
     */
    private fun runnerDiag(execSql: String): String {
        val diag = StringBuilder()
            runCatching {
                val mgr = LocalDataSourceManager.getInstance(project)
                val all = mgr.dataSources
                diag.append("LocalDataSourceManager.dataSources count=${all.size}\n")
                all.forEach {
                    diag.append("  ds name=${it.name} uniqueId=${it.uniqueId} driver=${it.databaseDriver?.id} workingDir=${it.workingDir}\n")
                }
                // 直接调 DbImplUtil.getAppropriateDataSourcesForExec 看过滤——psiFile 来自 prepareConsole
                // 内部创建（.idea/.../exec/mpsql-exec.sql），此处无法直接取，但能验证数据源可见性
                diag.append("DataSource visible: ${all.isNotEmpty()}\n")
                // DbPsiFacade（PSI 层）数据源列表——getAppropriateDataSourcesForExec 从这里取
                runCatching {
                    val psiFacade = Class.forName("com.intellij.database.psi.DbPsiFacade")
                        .getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
                        .invoke(null, project)
                    val psiDs = psiFacade.javaClass.getMethod("getDataSources").invoke(psiFacade) as List<*>
                    diag.append("DbPsiFacade.getDataSources count=${psiDs.size}\n")
                    psiDs.forEach { diag.append("  psi ds=$it\n") }
                }.onFailure { diag.append("DbPsiFacade dump failed: ${it.javaClass.simpleName}: ${it.message}\n") }
                // 逐步执行 createTempPsiFile 的 4 步（不走 runCatching.getOrNull 吞异常），
                // 精确定位 null 源
                try {
                    val base = project.basePath ?: error("project.basePath null")
                    val dir = java.io.File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec").apply { mkdirs() }
                    val f = java.io.File(dir, "mpsql-exec.sql").apply { writeText(execSql) }
                    diag.append("step1 file written: ${f.absolutePath}\n")
                    val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
                        ?: error("refreshAndFindFileByIoFile null")
                    diag.append("step2 vf=${vf.path}\n")
                    val mappings = com.intellij.sql.dialects.SqlDialectMappings.getInstance(project)
                    @Suppress("UNCHECKED_CAST")
                    val values = mappings.javaClass.getMethod("getAvailableValues").invoke(mappings) as List<Any?>
                    diag.append("step3 available dialects=${values.map { (it as? com.intellij.lang.Language)?.id }}\n")
                    val mysql = values.firstOrNull {
                        (it as? com.intellij.lang.Language)?.id.equals("MySQL", ignoreCase = true)
                    }
                    diag.append("step3b mysql dialect=${mysql?.let { (it as com.intellij.lang.Language).id }}\n")
                    if (mysql != null) {
                        mappings.javaClass.getMethod("setMapping", com.intellij.openapi.vfs.VirtualFile::class.java, Any::class.java)
                            .invoke(mappings, vf, mysql)
                        diag.append("step3c mapping set OK\n")
                    }
                    val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(vf)
                    diag.append("step4 psi=${psi?.javaClass?.simpleName} dialect=${psi?.language?.id}\n")
                    val appropriate = Class.forName("com.intellij.database.util.DbImplUtil")
                        .getMethod("getAppropriateDataSourcesForExec", com.intellij.psi.PsiFile::class.java)
                        .invoke(null, psi) as Iterable<*>
                    diag.append("step5 getAppropriateDataSourcesForExec=${appropriate.count()}\n")
                    // 复现过滤条件 lambda$1（isValid/SqlDataSource）+ lambda$2（dialect similarTo）
                    val sqlDialect = Class.forName("com.intellij.database.util.DbImplUtil")
                        .getMethod("getSqlLanguageDialectForExec", com.intellij.psi.PsiFile::class.java)
                        .invoke(null, psi)
                    diag.append("step6 sqlDialectForExec=${sqlDialect} (${(sqlDialect as? com.intellij.lang.Language)?.id})\n")
                    val dbDialect = runCatching {
                        sqlDialect?.javaClass?.getMethod("getDatabaseDialect")?.invoke(sqlDialect)
                    }.getOrNull()
                    diag.append("step6b dbDialect=${dbDialect?.javaClass?.simpleName} id=${(dbDialect as? com.intellij.lang.Language)?.id}\n")
                    runCatching {
                        val psiFacade = Class.forName("com.intellij.database.psi.DbPsiFacade")
                            .getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
                            .invoke(null, project)
                        val psiDs = psiFacade.javaClass.getMethod("getDataSources").invoke(psiFacade) as List<*>
                        val dsEl = psiDs.first()
                        val isValid = dsEl?.javaClass?.getMethod("isValid")?.invoke(dsEl)
                        diag.append("step7 psi ds isValid=$isValid class=${dsEl?.javaClass?.simpleName}\n")
                        val dsDialect = Class.forName("com.intellij.database.util.DbImplUtilCore")
                            .getMethod("getDatabaseDialect", Class.forName("com.intellij.database.psi.DbElement"))
                            .invoke(null, dsEl)
                        diag.append("step8 ds dialect=${dsDialect?.javaClass?.simpleName} id=${(dsDialect as? com.intellij.lang.Language)?.id}\n")
                        if (dbDialect != null && dsDialect != null) {
                            val similar = dbDialect.javaClass.getMethod("similarTo", Class.forName("com.intellij.database.dialects.DatabaseDialect"))
                                .invoke(dbDialect, dsDialect)
                            diag.append("step9 similarTo=$similar\n")
                        // lambda$4: SqlDataSource.containsFile(vf) —— 文件-数据源包含检查
                        val sqlDs = dsEl?.javaClass?.getMethod("getDelegate")?.invoke(dsEl)
                        val contains = runCatching {
                            sqlDs?.javaClass?.getMethod("containsFile", com.intellij.openapi.vfs.VirtualFile::class.java)
                                ?.invoke(sqlDs, vf) as? Boolean
                        }.getOrNull()
                        diag.append("step10 SqlDataSource.containsFile(vf)=$contains sqlDsClass=${sqlDs?.javaClass?.simpleName}\n")
                        // 看数据源 workingDir + file mapping
                        val wd = dsEl?.javaClass?.getMethod("getWorkingDir")?.invoke(dsEl)
                        diag.append("step11 ds workingDir=$wd\n")
                        }
                    }.onFailure { diag.append("filter repro FAILED: ${it.javaClass.simpleName}: ${it.message}\n") }
                } catch (e: Throwable) {
                    diag.append("step FAILED: ${e.javaClass.simpleName}: ${e.message}\n")
                    e.stackTrace.take(6).forEach { diag.append("  at $it\n") }
                }
            }.onFailure { diag.append("dump failed: ${it.javaClass.simpleName}: ${it.message}\n") }

        return diag.toString()
    }

    override fun tearDown() {
        introspectRef?.let { runCatching { it.close() } }
        introspectRef = null
        // 夹具编辑器必须先释放：平台 TestApplicationKt.checkEditorsReleased 对每个存活编辑器硬抛
        DbIntegrationProjectSupport.detachDbnConsole(project)
        DbIntegrationProjectSupport.releaseExecEditors(project)
        // 单例的 prepared/shared 槽位跨用例存活——面板关闭语义的清理是 releaseForPanel
        // （clearPrepared + dropSharedConsole + 清 pendingOnConsole），用例边界用它，避免下个用例
        // 经 reuseSharedConsole 命中本用例的控制台/编辑器（详见 DataSourceSelectorIntegrationTest 同款注释）
        runCatching { DataSourceSelector.releaseForPanel() }
        // T-TestDbDriver：object 单例 temp Psi 缓存跨用例存活，fixture 临时目录每用例一换 → 显式清。
        runCatching { DataSourceSelector.clearCachedTempPsiFileForTest() }
        super.tearDown()
    }
}
