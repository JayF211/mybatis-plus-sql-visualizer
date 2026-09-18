package cn.song.mybatisplus.visualizer.execute

import com.intellij.database.console.JdbcConsoleRunContext
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.artifacts.DatabaseArtifactLoader
import com.intellij.database.dataSource.artifacts.DatabaseArtifactManager
import com.intellij.database.util.Version
import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 驱动解析管线回归（T-TestDbDriver）：`mysql.8` 的 artifact 版本必须解析得出真实 jar。
 *
 * 背景：四个 DB 集成类的 introspect 曾一律 `ClassNotFoundException: com.mysql.cj.jdbc.Driver`
 * ——子进程拿不到驱动 jar 就显式绿跳，四类等于空跑。根因是 driver 的 artifact ref 版本为 null
 * （平台不读 `options/databaseDrivers.xml`，见 [DbIntegrationSeedSupport] 类注释的逐环实证），
 * 而 `ArtifactRef.getElements()` 在版本为 null 时回退恒空的 legacy 元素。
 *
 * 本类把那条链的每一环都钉成断言：清单有 9.7.0 → 解析出 jar 文件 → driver 的 classpathElements
 * 非空且含 mysql jar → 数据源的 classpathElements（`JdbcConsoleRunContext.addJdbcDrivers` 的直接
 * 输入）非空。任一环回退，本类先红，不再靠四个集成类的静默跳过掩盖。
 *
 * 运行：`./gradlew testDatabase`（默认 test 已排除本类）。
 */
class DbDriverPipelineTest : BasePlatformTestCase() {

    private companion object {
        const val ARTIFACT_ID = "MySQL ConnectorJ"
        const val ARTIFACT_VERSION = "9.7.0"
        const val DRIVER_ID = "mysql.8"
        const val DRIVER_CLASS = "com.mysql.cj.jdbc.Driver"
    }

    /** 解析出的 classpath 里必须出现的驱动 jar 名。 */
    private val expectedJar = "mysql-connector-j-$ARTIFACT_VERSION.jar"

    @Test
    fun testDriverArtifactResolvesToJarsOnClasspath() {
        // ① artifact 清单能解析出钉定版本（清单来自沙盒 jdbc-drivers.xml / options 副本）
        DbIntegrationSeedSupport.seedDriverFiles()
        val artifacts = DatabaseArtifactManager.getArtifacts()
        val resolved = artifacts.resolveVersion(ARTIFACT_ID, Version.of(ARTIFACT_VERSION))
        assertTrue(
            "artifact 清单应能解析 $ARTIFACT_ID@$ARTIFACT_VERSION（实际版本集: " +
                "${artifacts.getArtifact(ARTIFACT_ID)?.versions?.map { it.version }}）",
            resolved != null,
        )

        // ② loader 能把该版本解析成真实存在的 jar 文件
        val loader = DatabaseArtifactLoader.getInstance()
        val jars = loader.getClasspathElements(resolved!!).map { it.absolutePath }
        assertTrue("loader 应解析出非空 classpath，实际: $jars", jars.isNotEmpty())
        assertTrue("解析出的每个 classpath 条目都应存在: $jars", jars.all { File(it).isFile })
        assertTrue("classpath 应含 $expectedJar，实际: $jars", jars.any { it.endsWith(expectedJar) })

        // ③ driver 的 artifact ref 被钉上版本 → classpathElements 非空（本次修复的直接目标）
        DbIntegrationSeedSupport.pinDriverArtifactVersion()
        val driver = DatabaseDriverManager.getInstance().getDriver(DRIVER_ID)
        assertTrue("驱动 $DRIVER_ID 应存在（Database 插件已加载）", driver != null)
        val refs = driver!!.artifacts.map { "${it.id}@${it.artifactVersion}" }
        assertTrue("driver artifact ref 应钉上版本，实际: $refs", refs.contains("$ARTIFACT_ID@$ARTIFACT_VERSION"))
        val driverUrls = JdbcConsoleRunContext.expandPackUrls(driver.classpathElements).toList()
        assertTrue(
            "driver.classpathElements 应非空（版本为 null 时会回退恒空的 legacy 元素），实际: $driverUrls",
            driverUrls.isNotEmpty(),
        )
        assertTrue("driver.classpathElements 应含 $expectedJar，实际: $driverUrls", driverUrls.any { it.endsWith(expectedJar) })

        // ④ 数据源级：JdbcConsoleRunContext.addJdbcDrivers 直接读 ds.getClasspathElements()
        val mgr = LocalDataSourceManager.getInstance(project)
        val ds = mgr.createEmpty()
        ds.name = "mpj_driver-pipeline-probe"
        ds.url = "jdbc:mysql://localhost:3306/mpj_demo"
        ds.driverClass = DRIVER_CLASS
        ds.javaClass.getDeclaredMethod("setDriverRef", String::class.java).apply { isAccessible = true }
            .invoke(ds, DRIVER_ID)
        ds.resolveDriver()
        val dsUrls = JdbcConsoleRunContext.expandPackUrls(ds.classpathElements).toList()
        assertTrue(
            "数据源 classpathElements 应非空（子进程 -Djdbc.classpath 的唯一来源），实际: $dsUrls",
            dsUrls.isNotEmpty(),
        )
        assertTrue("数据源 classpathElements 应含 $expectedJar，实际: $dsUrls", dsUrls.any { it.endsWith(expectedJar) })
    }

    /** 种子文件落点断言：DBN 只认 `<configPath>/options/<name>`（见助手 KDoc ②）。 */
    @Test
    fun testSeedFilesLandWhereDbLooksForThem() {
        DbIntegrationSeedSupport.seedDriverFiles()
        val options = File(PathManager.getConfigPath(), "options")
        assertTrue("options/databaseDrivers.xml 应在 ${options.absolutePath}", File(options, "databaseDrivers.xml").isFile)
        assertTrue(
            "options/test-database-artifacts.xml 应在 ${options.absolutePath}（config 根下的副本 DBN 读不到）",
            File(options, "test-database-artifacts.xml").isFile,
        )
        val testLists = DatabaseArtifactLoader.getInstance().getTestArtifactLists()
        assertTrue(
            "DBN 单测模式 artifact 清单 hook 应非空（existentTestPath 命中 options/），实际: $testLists",
            testLists.isNotEmpty(),
        )
        assertTrue(
            "沙盒 jdbc-drivers 目录应含 $expectedJar",
            File(
                PathManager.getConfigPath(),
                "jdbc-drivers/$ARTIFACT_ID/$ARTIFACT_VERSION/com/mysql/mysql-connector-j/$ARTIFACT_VERSION/$expectedJar",
            ).isFile,
        )
    }
}
