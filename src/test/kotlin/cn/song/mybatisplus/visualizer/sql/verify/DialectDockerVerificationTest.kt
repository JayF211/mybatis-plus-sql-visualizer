package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.sql.SqlKeywords.MYSQL_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.ORACLE_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.PG_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.SQLSERVER_RESERVED
import cn.song.mybatisplus.visualizer.sql.verify.DialectVerifySupport.UpsertMode
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * Phase 2（Tier 2）：Docker 真实数据库方言验证（P-立项-05）。
 *
 * 使用 Testcontainers 拉起**真实数据库引擎**，执行与 H2/SQLite 测试相同的插件渲染 SQL 套件
 * （[DialectVerifySupport.runTcSuite] + 保留字全量扫描）——这是「真实环境测试」的可自动化部分，
 * 证据级别最高：
 *
 * | 容器 | 验证方言 | UPSERT |
 * |------|----------|--------|
 * | mysql:8.0 | MySQL | ON DUPLICATE KEY UPDATE + VALUES() |
 * | postgres:16 | PostgreSQL | ON CONFLICT (id) DO UPDATE + EXCLUDED（H2 无法模拟，此处补齐） |
 * | mariadb:11 | MariaDB | 同 MySQL 形态 |
 * | mssql/server:2022 | SQL Server | 无原生 → 降级纯 INSERT |
 * | gvenzl/oracle-free:23 | Oracle | 无原生 → 降级纯 INSERT |
 *
 * 运行条件与策略：
 *  - 本机/CI **无 Docker 时自动跳过**（[assumeTrue] 探测 Docker daemon），日常 `./gradlew test`
 *    零影响；有 Docker 时随全量测试自动执行；
 *  - Oracle/SQL Server 镜像体积大（数 GB）首次拉取慢——本地手动跑时可先
 *    `docker pull` 预热，或用 test/resources/docker-compose-dialects.yml 自建环境后
 *    以 -Dmpvs.dialect.jdbcUrl 等系统属性指向外部库执行（见 [externalEngines]）。
 *
 * DM（达梦）/ KingBase（人大金仓）无公开自动化镜像——真实环境验证需用户侧连接实例执行
 * （文档级验证见 [DialectDocGoldenTest]），本测试不支持。
 */
@Suppress("TestClassWithoutTestCases") // 容器用例全部经 Assume 守卫（无 Docker 时静默跳过）
class DialectDockerVerificationTest {

    // ------------------------------------------------------------------
    // Docker 可用性守卫（无 Docker → 测试跳过而非失败）
    // ------------------------------------------------------------------

    private fun assumeDockerAvailable() {
        val available = try {
            DockerClientFactory.instance().client().pingCmd().exec() != null
            true
        } catch (_: Throwable) {
            false
        }
        assumeTrue("Docker 不可用，跳过真实数据库方言验证（H2/SQLite 嵌入式验证已覆盖语法形态）", available)
    }

    // ------------------------------------------------------------------
    // 真实引擎套件
    // ------------------------------------------------------------------

    @Test
    fun `真实MySQL80_方言套件与保留字扫描`() {
        assumeDockerAvailable()
        MySQLContainer<Nothing>("mysql:8.0.36").apply {
            withUrlParam("useSSL", "false")
            withUrlParam("allowPublicKeyRetrieval", "true")
        }.use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "MySQL 8.0 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.MySqlDialect.INSTANCE,
                upsertMode = UpsertMode.REAL,
            )
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                DialectVerifySupport.runTcSuite(conn, spec)
                val failures = DialectVerifySupport.sweepReservedWords(conn, spec, MYSQL_RESERVED)
                assertSweepClean("MySQL 8.0", MYSQL_RESERVED.size, failures)
            }
        }
    }

    @Test
    fun `真实PostgreSQL16_方言套件与保留字扫描`() {
        assumeDockerAvailable()
        PostgreSQLContainer<Nothing>("postgres:16.2").use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "PostgreSQL 16 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect.INSTANCE,
                upsertMode = UpsertMode.REAL,
            )
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                DialectVerifySupport.runTcSuite(conn, spec)
                val failures = DialectVerifySupport.sweepReservedWords(conn, spec, PG_RESERVED)
                assertSweepClean("PostgreSQL 16", PG_RESERVED.size, failures)
            }
        }
    }

    @Test
    fun `真实MariaDB11_方言套件与保留字扫描`() {
        assumeDockerAvailable()
        MariaDBContainer<Nothing>("mariadb:11.2").use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "MariaDB 11 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.MariaDbDialect.INSTANCE,
                upsertMode = UpsertMode.REAL,
            )
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                DialectVerifySupport.runTcSuite(conn, spec)
                val failures = DialectVerifySupport.sweepReservedWords(conn, spec, spec.dialect.reservedWords)
                assertSweepClean("MariaDB 11", spec.dialect.reservedWords.size, failures)
            }
        }
    }

    @Test
    fun `真实SQLServer2022_方言套件与保留字扫描`() {
        assumeDockerAvailable()
        MSSQLServerContainer<Nothing>("mcr.microsoft.com/mssql/server:2022-CU12-ubuntu-22.04").use { container ->
            container.acceptLicense()
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "SQL Server 2022 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.SqlServerDialect.INSTANCE,
                upsertMode = UpsertMode.DEGRADE_ONLY,
            )
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                DialectVerifySupport.runTcSuite(conn, spec)
                val failures = DialectVerifySupport.sweepReservedWords(conn, spec, SQLSERVER_RESERVED)
                assertSweepClean("SQL Server 2022", SQLSERVER_RESERVED.size, failures)
            }
        }
    }

    @Test
    fun `真实Oracle23free_方言套件与保留字扫描`() {
        assumeDockerAvailable()
        OracleContainer("gvenzl/oracle-free:23.5-slim").use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "Oracle 23ai Free (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.OracleDialect.INSTANCE,
                upsertMode = UpsertMode.DEGRADE_ONLY,
                multiRowValues = false, // Oracle 不支持多行 VALUES
            )
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                DialectVerifySupport.runTcSuite(conn, spec)
                val failures = DialectVerifySupport.sweepReservedWords(conn, spec, ORACLE_RESERVED)
                assertSweepClean("Oracle 23ai", ORACLE_RESERVED.size, failures)
            }
        }
    }

    // ------------------------------------------------------------------
    // 外部实例接入（用户侧真实环境：DM / KingBase 及任意自建库）
    // ------------------------------------------------------------------

    /**
     * 外部实例验证入口（无容器依赖，连不上即跳过）：
     *
     * ```
     * ./gradlew test --tests '*DialectDockerVerificationTest*' \
     *   -Dmpvs.dialect.jdbcUrl="jdbc:dm://host:5236" \
     *   -Dmpvs.dialect.user=SYSDBA -Dmpvs.dialect.password=... \
     *   -Dmpvs.dialect.name="DM（达梦）"
     * ```
     *
     * 方言取 [cn.song.mybatisplus.visualizer.sql.DialectRegistry.INSTANCE.byName]（名称与 Settings 下拉一致）；
     * 执行与容器/嵌入式完全相同的 TC 套件 + 保留字扫描——DM/KingBase 真实环境验证的标准流程。
     */
    @Test
    fun `外部实例_方言套件与保留字扫描`() {
        val url = System.getProperty("mpvs.dialect.jdbcUrl")
        assumeTrue("未提供 -Dmpvs.dialect.jdbcUrl，跳过外部实例验证", !url.isNullOrBlank())
        val user = System.getProperty("mpvs.dialect.user")
        val password = System.getProperty("mpvs.dialect.password")
        val dialectName = System.getProperty("mpvs.dialect.name") ?: "MySQL"
        val dialect = cn.song.mybatisplus.visualizer.sql.DialectRegistry.INSTANCE.byName(dialectName)
        val spec = DialectVerifySupport.EngineSpec(
            label = "$dialectName (external)",
            jdbcUrlTemplate = url,
            dialect = dialect,
            upsertMode = UpsertMode.REAL,
            multiRowValues = dialectName.startsWith("Oracle") || dialectName.startsWith("DM"),
        )
        val conn: Connection = if (user != null) {
            DriverManager.getConnection(url, user, password)
        } else {
            DriverManager.getConnection(url)
        }
        conn.use {
            DialectVerifySupport.runTcSuite(it, spec)
            val failures = DialectVerifySupport.sweepReservedWords(it, spec, dialect.reservedWords)
            assertSweepClean(dialectName, dialect.reservedWords.size, failures)
        }
    }

    private fun assertSweepClean(label: String, total: Int, failures: List<String>) {
        org.junit.Assert.assertTrue(
            "$label 方言保留字扫描失败 ${failures.size}/$total 个词:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
