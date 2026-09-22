package cn.song.mybatisplus.visualizer.sql.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 方言 Lab 配方一致性守卫（T-DialectLabMatrix / T-DL-03，借 DBX Database Lab
 * 「不起库即可 CI 校验配方」思路）：纯文本结构校验，不依赖 Docker、随常规测试自动跑。
 *
 * 四硬约束（docker-compose-dialects.yml）：
 *  1. 镜像全部钉版本（无 :latest / 无 tag 缺失）；
 *  2. 宿主端口互不冲突且在 1xxxx 避让段（≥10000）；
 *  3. 各库必填环境变量齐全（密码/库名/EULA）；
 *  4. 每服务 healthcheck 在位。
 *
 * 配套约束：
 *  - database-lab/README.md 引用的端口与 yml 实际端口一致（防文档漂移）；
 *  - 国产库示例片段端口与主配方不冲突（镜像占位不校验钉版本——用户侧 tar 版本由官方渠道决定）。
 *
 * 漂移自证：注入 latest 镜像 / 端口冲突的合成样例必须被检查函数抓住（守卫有效性测试）。
 */
class DockerComposeLabConsistencyTest {

    // ------------------------------------------------------------------
    // 受检文件定位
    // ------------------------------------------------------------------

    private fun mainComposeText(): String =
        javaClass.classLoader.getResourceAsStream("docker-compose-dialects.yml")!!
            .readBytes().decodeToString()

    private fun labFile(name: String): File = File("database-lab", name)

    // ------------------------------------------------------------------
    // 四硬约束
    // ------------------------------------------------------------------

    @Test
    fun `镜像全部钉版本`() {
        val violations = checkImagePinning(parseServices(mainComposeText()))
        assertTrue("未钉版本镜像: $violations", violations.isEmpty())
    }

    @Test
    fun `端口互不冲突且在避让段`() {
        val violations = checkPorts(parseServices(mainComposeText()))
        assertTrue("端口违规: $violations", violations.isEmpty())
    }

    @Test
    fun `必填环境变量齐全`() {
        val violations = checkRequiredEnv(parseServices(mainComposeText()))
        assertTrue("缺失必填 env: $violations", violations.isEmpty())
    }

    @Test
    fun `每服务healthcheck在位`() {
        val violations = checkHealthcheck(parseServices(mainComposeText()))
        assertTrue("缺 healthcheck: $violations", violations.isEmpty())
    }

    // ------------------------------------------------------------------
    // 配套约束
    // ------------------------------------------------------------------

    @Test
    fun `README端口引用与主配方一致`() {
        assertTrue("database-lab/README.md 不存在（工作目录应为项目根）", labFile("README.md").isFile)
        val readme = labFile("README.md").readText()
        val hostPorts = hostPorts(parseServices(mainComposeText()))
        val missing = hostPorts.filter { !readme.contains(it) }
        assertTrue("README 未提及主配方端口 $missing（文档漂移）", missing.isEmpty())
    }

    @Test
    fun `国产库示例片段端口与主配方不冲突`() {
        val example = labFile("docker-compose.dialects-cn.example.yml")
        assertTrue("database-lab/docker-compose.dialects-cn.example.yml 不存在", example.isFile)
        val mainPorts = hostPorts(parseServices(mainComposeText())).toSet()
        val examplePorts = hostPorts(parseServices(example.readText()))
        val clash = examplePorts.filter { it in mainPorts }
        assertTrue("示例片段端口与主配方冲突: $clash", clash.isEmpty())
    }

    // ------------------------------------------------------------------
    // 漂移自证（守卫有效性：注入违规样例必须被抓）
    // ------------------------------------------------------------------

    @Test
    fun `漂移自证_latest镜像被抓`() {
        val bad = parseServices(
            """
            services:
              mysql:
                image: mysql:latest
            """.trimIndent(),
        )
        assertEquals(listOf("mysql: mysql:latest"), checkImagePinning(bad))
    }

    @Test
    fun `漂移自证_端口冲突被抓`() {
        val bad = parseServices(
            """
            services:
              a:
                image: mysql:8.0.36
                ports: ["13306:3306"]
              b:
                image: postgres:16.2
                ports: ["13306:5432"]
            """.trimIndent(),
        )
        val violations = checkPorts(bad)
        assertTrue(violations.isNotEmpty())
        assertTrue(violations.any { it.contains("13306") })
    }

    @Test
    fun `漂移自证_低段端口被抓`() {
        val bad = parseServices(
            """
            services:
              a:
                image: mysql:8.0.36
                ports: ["3306:3306"]
            """.trimIndent(),
        )
        assertTrue(checkPorts(bad).any { it.contains("3306") })
    }

    @Test
    fun `漂移自证_缺必填环境变量被抓`() {
        // 独立复核㉗：本检查此前无合成样本（被元守卫登记为「受机器保护的豁免」），此处关闭该缺口。
        val bad = parseServices(
            """
            services:
              mysql80:
                image: mysql:8.0.36
                environment:
                  MYSQL_ROOT_PASSWORD: root
            """.trimIndent(),
        )
        assertTrue(
            "缺 MYSQL_DATABASE 必须被抓，实际 ${checkRequiredEnv(bad)}",
            checkRequiredEnv(bad).any { it == "mysql80: 缺 MYSQL_DATABASE" },
        )
        // 正对照：补齐该服务必填项后不得再报「缺」（检查不得退化成「一律放行」）。只筛 mysql80 项——
        // 单服务样例必然同时命中「其余四服务不在配方中」那一条。
        val fixed = parseServices(
            """
            services:
              mysql80:
                image: mysql:8.0.36
                environment:
                  MYSQL_ROOT_PASSWORD: root
                  MYSQL_DATABASE: demo
            """.trimIndent(),
        )
        assertTrue(
            "补齐必填项后不得再报 mysql80 缺项，实际 ${checkRequiredEnv(fixed)}",
            checkRequiredEnv(fixed).none { it.startsWith("mysql80: 缺") },
        )
    }

    @Test
    fun `漂移自证_缺healthcheck被抓`() {
        // 独立复核㉗：同上，本检查此前无合成样本。
        val bad = parseServices(
            """
            services:
              mysql80:
                image: mysql:8.0.36
            """.trimIndent(),
        )
        assertEquals("缺 healthcheck 的服务必须被抓", listOf("mysql80: 缺 healthcheck"), checkHealthcheck(bad))
        val ok = parseServices(
            """
            services:
              mysql80:
                image: mysql:8.0.36
                healthcheck:
                  test: ["CMD", "mysqladmin", "ping"]
            """.trimIndent(),
        )
        assertTrue("有 healthcheck 的服务不得被报缺：${checkHealthcheck(ok)}", checkHealthcheck(ok).isEmpty())
    }

    // ------------------------------------------------------------------
    // 检查函数（纯函数，合成样例可注入）
    // ------------------------------------------------------------------

    private fun parseServices(yamlText: String): Map<String, Map<String, Any>> {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml().load<Map<String, Any>>(yamlText)
        @Suppress("UNCHECKED_CAST")
        return root["services"] as? Map<String, Map<String, Any>> ?: emptyMap()
    }

    /** 镜像钉版本：须有 tag 且非 latest（registry 前缀含 `/` 与端口的不误伤）。 */
    internal fun checkImagePinning(services: Map<String, Map<String, Any>>): List<String> =
        services.mapNotNull { (name, svc) ->
            val image = svc["image"]?.toString() ?: return@mapNotNull "$name: 无 image"
            val lastPart = image.substringAfterLast('/')
            when {
                !lastPart.contains(':') -> "$name: $image（无 tag）"
                lastPart.substringAfterLast(':').equals("latest", ignoreCase = true) -> "$name: $image"
                else -> null
            }
        }

    /** 宿主端口：≥10000 避让段且互不冲突。 */
    internal fun checkPorts(services: Map<String, Map<String, Any>>): List<String> {
        val violations = ArrayList<String>()
        val seen = HashMap<String, String>()
        for ((name, svc) in services) {
            @Suppress("UNCHECKED_CAST")
            val ports = svc["ports"] as? List<Any> ?: emptyList()
            for (port in ports) {
                val host = port.toString().substringBefore(':')
                val number = host.toIntOrNull()
                if (number == null) {
                    violations += "$name: 端口形态异常 '$port'"
                    continue
                }
                if (number < 10000) violations += "$name: 宿主端口 $number 不在 1xxxx 避让段"
                seen.putIfAbsent(number.toString(), name)?.let {
                    violations += "端口 $number 冲突：$it 与 $name"
                }
            }
        }
        return violations
    }

    /** 各服务必填 env（密码/库名/EULA——按官方镜像要求）。 */
    internal fun checkRequiredEnv(services: Map<String, Map<String, Any>>): List<String> {
        val required = mapOf(
            "mysql80" to listOf("MYSQL_ROOT_PASSWORD", "MYSQL_DATABASE"),
            "postgres16" to listOf("POSTGRES_PASSWORD", "POSTGRES_DB"),
            "mariadb11" to listOf("MARIADB_ROOT_PASSWORD", "MARIADB_DATABASE"),
            "sqlserver2022" to listOf("ACCEPT_EULA", "MSSQL_SA_PASSWORD"),
            "oracle23free" to listOf("ORACLE_PASSWORD", "APP_USER", "APP_USER_PASSWORD"),
        )
        val violations = ArrayList<String>()
        for ((name, svc) in services) {
            val need = required[name] ?: continue
            @Suppress("UNCHECKED_CAST")
            val env = (svc["environment"] as? Map<String, Any>)?.keys ?: emptySet()
            need.filter { it !in env }.forEach { violations += "$name: 缺 $it" }
        }
        // 主配方五服务必须全部在位（服务名漂移也要抓住）
        required.keys.filter { it !in services }.forEach { violations += "服务 $it 不在配方中（服务名漂移）" }
        return violations
    }

    /** 每服务 healthcheck 在位。 */
    internal fun checkHealthcheck(services: Map<String, Map<String, Any>>): List<String> =
        services.filterValues { it["healthcheck"] == null }.keys.map { "$it: 缺 healthcheck" }

    /** 全部宿主端口字符串（README 一致性用）。 */
    private fun hostPorts(services: Map<String, Map<String, Any>>): List<String> =
        services.values.flatMap { svc ->
            @Suppress("UNCHECKED_CAST")
            (svc["ports"] as? List<Any> ?: emptyList()).map { it.toString().substringBefore(':') }
        }
}
