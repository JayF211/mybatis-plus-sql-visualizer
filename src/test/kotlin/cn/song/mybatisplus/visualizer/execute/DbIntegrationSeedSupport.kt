package cn.song.mybatisplus.visualizer.execute

import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.util.Version
import com.intellij.openapi.application.PathManager
import java.io.File

/**
 * DB 集成测试驱动种子 + 驱动 artifact 版本钉定（testDatabase 通道四类共用）。
 *
 * ## 管线全貌（20260918 逐环字节码实证，T-TestDbDriver）
 *
 * 驱动进子进程的链路是：
 * `LocalDataSource.getClasspathElements()` → `myDriver.getClasspathElements()`
 * → 每个 `ArtifactRef.getElements()`：`myVersion == null` 时回退 legacy 元素（恒空），
 * 否则 `DatabaseArtifactManager.getClasspathElements(artifactId, version)`
 * → `DatabaseArtifactList.resolveVersion` 命中清单条目 → `DatabaseArtifactLoaderImpl
 * .getClasspathElements(av)` 从 `<config>/jdbc-drivers/<artifact name>/<version>` 解析 jar
 * → `JdbcConsoleRunContext.addJdbcDrivers` 把这些 jar 写进子进程参数
 * （isolate 关：直接进子进程 classpath；isolate 开：写 `RemoteDriver<classpath>.classpath.txt`
 * 临时文件并以 `-Djdbc.classpath=<绝对路径>` 传参）。
 *
 * 于是「驱动在子进程里可加载」只有一个前提：**driver 的 artifact ref 带具体版本号**。
 *
 * ## 为什么需要本类（两处断点，20260918 探针实测）
 *
 * ① 平台不读 `options/databaseDrivers.xml`：真实 IDE 的驱动→artifact 版本映射存在该持久化
 *    状态文件里（`@State(name = "LocalDatabaseDriverManager", storages = @Storage("databaseDrivers.xml"))`，
 *    javap 实证）。测试沙盒里 `PathManager.getOptionsPath()` 与文件位置都对、内容与 Roaming
 *    实物一致，但 `getDriver("mysql.8").artifacts` 只剩驱动定义里那条 rolling ref
 *    （`id=MySQL Connector/J, version=null`，来自 database-plugin.jar 的
 *    `databaseDrivers/mysql-drivers.xml` 的 `<artifact name="MySQL Connector/J" use="true" rolling="true"/>`）
 *    → version=null → legacy 空 → 子进程 ClassNotFoundException: com.mysql.cj.jdbc.Driver。
 *    探针同时证明：把同一份 XML 手工喂给 `DatabaseDriverManagerImpl.loadState` 立刻生效
 *    （artifacts 变 `MySQL ConnectorJ@9.7.0`，classpathElements 出 2 个 jar）。
 *    故本类走**公有 API**（`DatabaseDriverImpl.setArtifacts` + `createArtifactRef` +
 *    `DatabaseDriverManager.updateDriver`，均 243 javap 实证公有）把状态补上，
 *    不反射平台内部、不碰生产代码。
 * ② `test-database-artifacts.xml` 落点错：`DatabaseArtifactLoaderImpl.getTestArtifactLists()`
 *    经 `DatabaseDriverManagerImpl.existentTestPath(name)` 只认
 *    `<homePath>/dbe/database/testResources/<name>` 与 `<configPath>/options/<name>`
 *    （bytecode 常量池实证），原 buildSrc 种子落在 `<configPath>/` 根 → 探针
 *    `getTestArtifactLists() == []`。落点已改 `options/`（buildSrc 侧）。
 *
 * 本对象仅被 testDatabase 通道类引用。
 */
object DbIntegrationSeedSupport {

    /** 驱动 artifact 版本（与 buildSrc 种子清单、日常 IDE 驱动存储一致）。 */
    private const val ARTIFACT_VERSION = "9.7.0"

    /** DBN 内置驱动 id（demo 项目 dataSources.xml 的 `<driver-ref>mysql.8`）。 */
    private const val DRIVER_ID = "mysql.8"

    /** artifact id（jdbc-drivers.xml 里 `<artifact id="MySQL ConnectorJ">`）。 */
    private const val ARTIFACT_ID = "MySQL ConnectorJ"

    /** dev 工作树 runIde 沙盒的 config 根（清单 xml 常驻；项目根 = config_testDatabase 上溯四级）。 */
    private fun devSandboxConfig(): File? {
        val projectRoot = File(PathManager.getConfigPath()).parentFile?.parentFile?.parentFile?.parentFile
            ?: return null
        return File(
            projectRoot,
            "../mybatis-plus-sql-visualizer-dev/.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/config",
        )
    }

    /** 日常 IDE 驱动存储（jar 按 maven 布局存放；本机 IntelliJIdea2024.3 实存 9.7.0）。 */
    private fun roamingStore(): File =
        File(System.getProperty("user.home"), "AppData/Roaming/JetBrains/IntelliJIdea2024.3/jdbc-drivers")

    /**
     * 幂等种子（各步独立守卫，已存在即跳过）：
     * ① jdbc-drivers/jdbc-drivers.xml（artifact 清单，dev 沙盒取）；
     * ② jdbc-drivers/MySQL ConnectorJ/[ver] 目录下全量 jar（日常 IDE 驱动存储取）；
     * ③ options/databaseDrivers.xml（driver 就绪状态，真实 IDE 同款形态）；
     * ④ options/test-database-artifacts.xml（= ①的副本，DBN 单测模式只认 config 根 options/ 此名）；
     * ⑤ test-database-drivers.xml（driver 定义，buildSrc 原版字面；单测模式 DBN 显式忽略该名）。
     */
    fun seedDriverFiles() {
        val configRoot = File(PathManager.getConfigPath())
        val driverDir = File(configRoot, "jdbc-drivers")
        val optionsDir = File(configRoot, "options")
        val devConfig = devSandboxConfig()

        val manifest = File(driverDir, "jdbc-drivers.xml")
        if (!manifest.isFile) {
            devConfig?.let { dev ->
                val src = File(dev, "jdbc-drivers/jdbc-drivers.xml")
                if (src.isFile) {
                    driverDir.mkdirs()
                    src.copyTo(manifest)
                }
            }
        }
        val versionDir = File(driverDir, "MySQL ConnectorJ/$ARTIFACT_VERSION")
        val roamingVersionDir = File(roamingStore(), "MySQL ConnectorJ/$ARTIFACT_VERSION")
        if (!versionDir.isDirectory && roamingVersionDir.isDirectory) {
            // 逐文件 mkdirs——File.copyRecursively walkBottomUp 深层文件先于父目录事件落地，
            // 深路径直接 ERROR_PATH_NOT_FOUND（20260917 实测）
            roamingVersionDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val out = File(versionDir, f.relativeTo(roamingVersionDir).path)
                out.parentFile.mkdirs()
                runCatching { f.copyTo(out, overwrite = true) }
            }
        }
        val driverState = File(optionsDir, "databaseDrivers.xml")
        // databaseDrivers.xml 必须含 mysql.8 的 artifact 版本条目——真实 IDE 同款（Roaming
        // IntelliJIdea2024.3 实物对照）：空壳文件（仅 LocalDatabaseDriverManager 组件声明）时
        // DBN 不知道 mysql.8 该解析哪个版本的 jar → getClasspathElements 恒空 → CNFE。
        if (!driverState.isFile || driverState.readText().contains("mysql.8").not()) {
            optionsDir.mkdirs()
            driverState.writeText(
                "<application>\n" +
                    "  <component name=\"LocalDatabaseDriverManager\" version=\"201\">\n" +
                    "    <driver id=\"$DRIVER_ID\">\n" +
                    "      <artifact id=\"$ARTIFACT_ID\" name=\"$ARTIFACT_ID\" use=\"true\" version=\"$ARTIFACT_VERSION\" />\n" +
                    "    </driver>\n" +
                    "  </component>\n" +
                    "</application>\n",
            )
        }
        val artifactsList = File(optionsDir, "test-database-artifacts.xml")
        if (!artifactsList.isFile && manifest.isFile) {
            optionsDir.mkdirs()
            manifest.copyTo(artifactsList)
        }
        val testDrivers = File(configRoot, "test-database-drivers.xml")
        if (!testDrivers.isFile) {
            testDrivers.writeText(
                """
                <drivers>
                  <driver id="$DRIVER_ID">
                    <artifact id="$ARTIFACT_ID" name="MySQL Connector/J" use="true" version="$ARTIFACT_VERSION"/>
                  </driver>
                </drivers>
                """.trimIndent(),
            )
        }
    }

    /**
     * 把 [DRIVER_ID] 的 artifact ref 钉到 [ARTIFACT_VERSION]（幂等）。
     *
     * 平台在测试环境不读 `options/databaseDrivers.xml`（类注释 ①），这里用公有 API 补同一件事：
     * `setArtifacts([createArtifactRef(ARTIFACT_ID, 9.7.0, null)])` 让
     * `ArtifactRef.getElements()` 走 `DatabaseArtifactManager.getClasspathElements` 而非恒空的
     * legacy 回退。驱动定义里原有的 rolling ref（version=null）被整体替换——它本来就贡献不了
     * classpath（`myVersion == null` → legacy 空），保留只会多一条空 ref。
     *
     * @return 生效后的 artifact ref 摘要（供调用方/测试打印诊断）
     */
    fun pinDriverArtifactVersion(): String {
        val manager = DatabaseDriverManager.getInstance()
        val driver = manager.getDriver(DRIVER_ID)
            ?: return "driver $DRIVER_ID 不存在（Database 插件未加载？）"
        val pinned = driver.artifacts.orEmpty().any {
            it.id == ARTIFACT_ID && it.artifactVersion == Version.of(ARTIFACT_VERSION)
        }
        if (!pinned && driver is DatabaseDriverImpl) {
            driver.setArtifacts(
                listOf(DatabaseDriverImpl.createArtifactRef(ARTIFACT_ID, Version.of(ARTIFACT_VERSION), null)),
            )
            manager.updateDriver(driver)
        }
        return driver.artifacts.orEmpty().joinToString("|") { "${it.id}@${it.artifactVersion}" }
    }

    /**
     * 数据源建连前的驱动管线准备：种子文件 + 驱动 artifact 版本钉定。
     * 调用点在 `ds.resolveDriver()` 之后；`myDriver` 与 `DatabaseDriverManager.getDriver` 返回
     * 同一实例，故钉定对已解析的数据源即时可见。
     */
    fun prepareDriverPipeline() {
        seedDriverFiles()
        pinDriverArtifactVersion()
    }
}
