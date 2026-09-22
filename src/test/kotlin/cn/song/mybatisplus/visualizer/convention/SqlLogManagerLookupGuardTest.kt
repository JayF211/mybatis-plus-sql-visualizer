package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `SqlLogManager` 取用点守卫（T-ST-02b2 生产侧收口，2026-09-22）。
 *
 * ## 为什么需要它
 *
 * 平台服务容器**一次创建失败即把该服务在该项目实例上永久毒化**：`LazyInstanceHolder` 的异常表是
 * `catch Throwable`（无 `CancellationException` 过滤），catch 后无条件缓存 `CannotInitialize`，
 * 此后每次取用永久重抛。项目处于「关闭中 / 测试框架 temporarily-disposed 窗口」时 `getService` 会抛
 * `AlreadyDisposedException`（服务构造期订阅 `project.messageBus` → `checkOwnerDisposed`）。
 *
 * 关键点：**`runCatching { getService(...) }` 挡不住中毒**——它只吞掉这一次的结果，仍然发起了那次
 * 注定失败的创建。唯一有效做法是**在发起创建之前**先判 `Project.isDisposed`（论证见
 * `log/SqlLogManagerLookupKt.java` 与 `SectionConfigurable` 的 T-ST-02/T-ST-02b2 KDoc）。
 *
 * ## 实测代价（本守卫的立项现场）
 *
 * 2026-09-22 `testShards`：同片内任一取用点在窗口内发起失败创建后，**同片后续所有取用点**都拿到
 * `Container is already disposed` 而成片变红（`AgentManagerIntakeGateTest` 15 例 + 邻居 5 例；
 * 三个受害类隔离跑全绿）。light 测试项目片内跨类复用同一 `ProjectImpl`，故中毒跨测试类传播；
 * 是否发作取决于分片时长权重算出的邻居——**新增测试类即重排、即触发**，红总是「隔离绿、分片红」。
 *
 * ## 规则
 *
 *  1. `src/main` 下**任何** `getService(SqlLogManager.class)` / `getServiceIfCreated(SqlLogManager.class)`
 *     取用，都必须落在**唯一收口文件** `log/SqlLogManagerLookupKt.java`（`sqlLogManagerOrNull` /
 *     `sqlLogManagerIfCreated` 的实现体）内——其余调用点一律改走 `sqlLogManagerOrNull(project)` /
 *     `sqlLogManagerIfCreated(project)`，由它统一先判 `isDisposed`；
 *  2. 自检：该收口文件必须**真的**含 `isDisposed` 守卫（防将来被「优化」成裸取用后守卫仍绿）；
 *  3. 扫描前剥注释（KDoc 里常引旧写法，不剥会误报）；字符串字面量里的 `//` 会连带截断，只可能漏报不会误报。
 *
 * 与姊妹守卫 [BannedApiSourceScanTest] 的分工：那条管「**测试类改了共享状态不复位**」（状态泄漏，
 * 靠 `SqlLogManagerProjectState` 存复原）；本守卫管「**取用点在 disposed 窗口发起创建**」（容器中毒）——
 * 两条机制不同、现场同源（都表现为「隔离绿、分片红」），不可互相替代。
 *
 * ## k2j 移植注（生产树全 Java，扫描根 src/main/java + .java）
 *
 * 1. **收口文件换形态**：master 的收口文件是顶层函数文件 `log/SqlLogManagerLookup.kt`，k2j 按仓内惯例
 *    落成门面类 `log/SqlLogManagerLookupKt.java`（私有构造器 + 静态方法）——白名单与自检断言随之改
 *    指该文件、Java 判据（`sqlLogManagerIfCreated` 方法与 `getServiceIfCreated(SqlLogManager.class)`）。
 * 2. **取用令牌换 Java 语法**：`SqlLogManager::class.java`（Kotlin）在 Java 侧写 `SqlLogManager.class`；
     正则容忍全限定包名前缀（`getService(cn.song.…SqlLogManager.class)` 同样命中）。
 * 3. **master 正则的盲区在本树首次可见**：master 的正则只认 Kotlin 形态，对它自己树里的两个 Java 形态
 *    直取站点（`SqlLogToolWindowFactory.java:49` 直取；`SqlGutterLineMarkerProvider` 在 master 是
 *    全限定 Kotlin 形态直取，Java 移植后成简单名形态）不可见。k2j 全 Java 树上这两点必然命中——
 *    二者均为 **master 同点位**移植件，登记进白名单并留待收敛（应收口到 `sqlLogManagerOrNull` /
 *    `sqlLogManagerIfCreated`），规则本身不撤：**新出现第三处即红**。
 */
class SqlLogManagerLookupGuardTest {

    /** 收口文件（k2j：顶层函数落门面类，见类 KDoc 移植注 1）。 */
    private val lookupFacade = "src/main/java/cn/song/mybatisplus/visualizer/log/SqlLogManagerLookupKt.java"

    /**
     * 既有直取站点白名单（**master 同点位**，待收敛——见类 KDoc 移植注 3；新出现站点不进这里，直接红）。
     */
    private val legacyDirectSites = setOf(
        // master 同文件同点位（master src/main/java 树本就是 Java 文件，直取 getService(SqlLogManager.class)）
        "src/main/java/cn/song/mybatisplus/visualizer/log/SqlLogToolWindowFactory.java",
        // master 为全限定 Kotlin 形态直取（其正则盲区）；k2j 移植后为简单名 Java 形态
        "src/main/java/cn/song/mybatisplus/visualizer/ui/gutter/SqlGutterLineMarkerProvider.java",
    )

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 剥注释：块注释整体移除、行注释截到行尾（与 [BannedApiSourceScanTest] 同口径）。 */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    /** k2j 生产树：src/main/java 全 Java（master 双根 kotlin+java，本树 kotlin 根不存在）。 */
    private fun mainSources(root: File): List<File> =
        File(root, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .toList()

    /** Java 形态取用令牌（容忍全限定前缀，见类 KDoc 移植注 2）。 */
    private val lookupRegex = Regex("""getService(IfCreated)?\((?:[A-Za-z0-9_.]+\.)?SqlLogManager\.class\)""")

    @Test
    fun 生产侧不得直接取用SqlLogManager_一律经统一入口() {
        val root = repoRoot()
        val offenders = mutableListOf<String>()
        var scanned = 0
        mainSources(root).forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            val code = stripComments(f.readText())
            if (!lookupRegex.containsMatchIn(code)) return@forEach
            scanned++
            if (rel != lookupFacade && rel !in legacyDirectSites) {
                code.lines().forEachIndexed { i, line ->
                    if (lookupRegex.containsMatchIn(line)) offenders += "$rel:${i + 1}  ${line.trim()}"
                }
            }
        }
        assertTrue(
            "SqlLogManager 取用必须经 log/SqlLogManagerLookupKt.java 的 sqlLogManagerOrNull / " +
                "sqlLogManagerIfCreated（取服务前先判 Project.isDisposed；runCatching 挡不住服务容器永久中毒，" +
                "见该文件 KDoc）。既有白名单仅收口文件与两个 master 同点位待收敛站点（见类 KDoc 移植注 3），" +
                "新站点不在豁免之列。违规点：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        assertTrue("扫描面应至少覆盖收口文件自身（否则本守卫失去目标站点）", scanned >= 1)
    }

    @Test
    fun 统一入口自身必须真的判isDisposed_防被掏空() {
        val root = repoRoot()
        val f = File(root, lookupFacade)
        assertTrue("收口文件应存在：$lookupFacade", f.isFile)
        val code = stripComments(f.readText())
        assertTrue(
            "统一入口必须在发起 getService 之前判 project.isDisposed——" +
                "该判定一旦被删，本守卫会变成空绿（毒化机制原样复活）",
            Regex("""isDisposed""").containsMatchIn(code) &&
                Regex("""getService\(SqlLogManager\.class\)""").containsMatchIn(code),
        )
        // T-ST-02b2 第二层：读路径必须走「永不创建」的 getServiceIfCreated —— isDisposed 判据对测试框架
        // 的 temporarily-disposed 窗口不可达（窗口内 isDisposed=false 而 getService 仍失败并永久毒化），
        // 只有结构上不发起创建才真正消除该机制。缺这一支即说明读路径被改回创建式取用。
        assertTrue(
            "读路径入口 sqlLogManagerIfCreated 必须存在且基于 getServiceIfCreated（不发起创建）——" +
                "缺它则 disposed 窗口内的读路径仍会毒化服务容器",
            Regex("""sqlLogManagerIfCreated\s*\(""").containsMatchIn(code) &&
                Regex("""getServiceIfCreated\(SqlLogManager\.class\)""").containsMatchIn(code),
        )
    }
}
