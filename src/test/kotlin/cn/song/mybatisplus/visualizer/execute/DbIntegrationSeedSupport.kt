package cn.song.mybatisplus.visualizer.execute

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.application.PathManager
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import java.io.File

/**
 * T-ExecDocGate：DB 集成测试驱动种子 + 数据源驱动 classpath 助手（testDatabase 通道四类共用）。
 *
 * 背景（20260917 实测）：testDatabase 沙盒的驱动种子原由 buildSrc doFirst 承担，但
 * ① doFirst 相对路径按 **Gradle daemon 进程 CWD** 解析（实测为 C:\Users\USER\.gradle\daemon\<版本>，
 *   跨构建不一致；「Test 任务工作目录=项目根」只对 Test worker JVM 成立）；
 * ② 本地 runIde 沙盒 clean 后种子源消失，DBN 单测模式不会联网拉驱动 → RemoteJdbcServer
 *   ClassNotFound → 四集成类 introspect 全跳。
 * 故种子逻辑移入测试 JVM 本类（PathManager.getConfigPath() 天然指向 config_testDatabase，
 * 无 daemon CWD 问题），数据源再显式携带驱动 jar classpath——DBN 243 单测模式 artifact 解析
 * 实测不生效（idea.log 零 loader 活动、RemoteDriver.classpath.txt 仅 jdbc-console.jar，
 * 与 T-Embed-08 清单机制的字节码结论冲突，留档不深究），改走
 * `SimpleClasspathElementFactory.createElements(String...)` + `LocalDataSource.setClasspathElements`
 *（243 javap 实证公有 API；M36.5.23d「setClasspathElements 无效」实为当年路径指错 config/）。
 *
 * **20260917 实测结论（探针逐环实据，留档）**：种子三件套齐备后，`DatabaseDriverManager
 * .getDriver("mysql.8")` 已能解析出 `DatabaseDriverImpl`（driver 状态文件修复生效），但
 * `driver.classpathElements.size == 0`——断点在 `DatabaseArtifactLoader.getTestArtifactLists()`
 * 返回空列表：其内部调用的 `com.intellij.database.util.DatabaseDriverManagerUtil` 在 243 测试
 * classpath 上**不存在**（探针实测 ClassNotFoundException），异常被 DBN 的 runCatching 吞掉
 * → 清单为空 → 驱动 artifact 解析不出 classpath → RemoteDriver 进程 CNFE。属平台/测试框架
 * 侧限制，非种子可修；另实测 `LocalDataSource.setClasspathElements` 是死路（getter 只回
 * resolved driver 的 elements，myClasspathElements 仅用于持久化，roundTrip 恒 0）。
 * 故 testDatabase 通道四类在本机走显式绿跳；驱动管线修复（候选路线：注册
 * ConsoleRunContextParametersTuner EP 向启动参数追加驱动 jar）另立基建债。
 *
 * 本对象仅被 testDatabase 通道四类引用 */
object DbIntegrationSeedSupport {

    /** test-database-drivers.xml 钉死的驱动 artifact 版本（与 buildSrc 种子清单一致）。 */
    private const val ARTIFACT_VERSION = "9.7.0"

    /** 驱动 jar 相对 config/jdbc-drivers/MySQL ConnectorJ/<ver> 的 maven 路径（remote server classpath 需要）。 */
    private val driverJarRelativePaths = listOf(
        "com/mysql/mysql-connector-j/$ARTIFACT_VERSION/mysql-connector-j-$ARTIFACT_VERSION.jar",
        "com/google/protobuf/protobuf-java/4.31.1/protobuf-java-4.31.1.jar",
    )

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
     * ③ options/databaseDrivers.xml（driver 就绪状态，dev 沙盒取或合成最小态）；
     * ④ test-database-artifacts.xml（= ①的副本，DBN 单测模式只认 config 根此名）；
     * ⑤ test-database-drivers.xml（driver→artifact 版本钉死，buildSrc 原版字面）。
     */
    fun seedDriverFiles() {
        val configRoot = File(PathManager.getConfigPath())
        val driverDir = File(configRoot, "jdbc-drivers")
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
        val driverState = File(configRoot, "options/databaseDrivers.xml")
        // databaseDrivers.xml 必须含 mysql.8 的 artifact 版本条目——真实 IDE 同款（Roaming
        // IntelliJIdea2024.3 实物对照）：空壳文件（仅 LocalDatabaseDriverManager 组件声明）时
        // DBN 不知道 mysql.8 该解析哪个版本的 jar → getClasspathElements 恒空 → CNFE。
        // 文件缺失或无 mysql.8 条目时写入带条目版本
        if (!driverState.isFile || driverState.readText().contains("mysql.8").not()) {
            File(configRoot, "options").mkdirs()
            driverState.writeText(
                "<application>\n" +
                    "  <component name=\"LocalDatabaseDriverManager\" version=\"201\">\n" +
                    "    <driver id=\"mysql.8\">\n" +
                    "      <artifact id=\"MySQL ConnectorJ\" name=\"MySQL ConnectorJ\" use=\"true\" version=\"$ARTIFACT_VERSION\" />\n" +
                    "    </driver>\n" +
                    "  </component>\n" +
                    "</application>\n",
            )
        }
        val artifactsList = File(configRoot, "test-database-artifacts.xml")
        if (!artifactsList.isFile && manifest.isFile) {
            manifest.copyTo(artifactsList)
        }
        val testDrivers = File(configRoot, "test-database-drivers.xml")
        if (!testDrivers.isFile) {
            testDrivers.writeText(
                """
                <drivers>
                  <driver id="mysql.8">
                    <artifact id="MySQL ConnectorJ" name="MySQL Connector/J" use="true" version="$ARTIFACT_VERSION"/>
                  </driver>
                </drivers>
                """.trimIndent(),
            )
        }
    }

    /**
     * 数据源显式携带驱动 jar classpath（绕过 DBN 243 单测模式 artifact 解析）。调用方在
     * `resolveDriver()` 之后调用；内部先幂等种子。
     */
    fun wireDriverClasspath(ds: LocalDataSource) {
        seedDriverFiles()
        val driverBase = File(
            File(PathManager.getConfigPath(), "jdbc-drivers"),
            "MySQL ConnectorJ/$ARTIFACT_VERSION",
        )
        val jars = driverJarRelativePaths
            .map { File(driverBase, it) }
            .filter { it.isFile }
            // getClassesRootUrls 原样返回字符串（javap 实证）——必须是 file URI，
            // 裸文件路径下游按 URL 解析静默失败（M36.5.23d「无效」的同款真因）
            .map { it.toURI().toString() }
        if (jars.isEmpty()) return
        ds.setClasspathElements(SimpleClasspathElementFactory.createElements(*jars.toTypedArray()))
    }
}
