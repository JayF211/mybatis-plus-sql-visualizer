import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    // java 插件只为取得 sourceSets/tasks.test 的类型安全访问器（根侧 kotlin.jvm 已应用 java，幂等）
    id("java")
    id("org.jetbrains.intellij.platform")
}

// T-BuildScriptSplit：测试基建分治——块级逐字搬运自根 build.gradle.kts（锚点 dc047bd），
// 注释随迁；验收=分区指纹与基线一致 + testShards 全量绿。

// 测试 classpath 排除 Vue 插件（org.jetbrains.plugins.vue）：
// LightPlatform 测试沙盒会无条件加载所有 bundled 插件，Vue 的 LSP 扩展（VueLspServerSupportProvider）
// 在无 node 的测试环境初始化失败，被 TestLogger 计入断言失败（随机污染测试结果）。
// 已知问题：https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1887（disabledPlugins 对测试无效）。
// 本插件不依赖 Vue，排除其 jar 安全。
// M17：同样排除 Database 插件（com.intellij.database）——本地平台为 Ultimate 时测试沙盒会加载它，
// 使 ExecuteSqlSupport.isAvailable() 恒为 true（无法单测 Community 降级路径）；排除后测试环境
// 稳定模拟「无 database 插件」（按钮降级/提示分支可测）。排除 jar 名含 database/sql 的模块 jar。
// M36.5.23：**testDatabase 任务除外**——DB 集成测试需要 Database 插件真实加载（模拟 Ultimate
// 沙盒环境，见下方 intellijPlatformTesting.testIde.register("testDatabase")）。
tasks.withType<Test>().configureEach {
    val isDbIntegration = name == "testDatabase"
    classpath = classpath.filter {
        !it.name.contains("vue", ignoreCase = true) &&
            (isDbIntegration || (
                !(it.name.contains("database", ignoreCase = true) && it.name.startsWith("intellij.")) &&
                    it.name != "database-plugin.jar" &&
                    !(it.name.contains("intellij.sql", ignoreCase = true))
                ))
    }
    // 默认 test（含平台型测试）保持串行（maxParallelForks=1）：多 fork 共享默认 test 沙盒，
    // PersistentFS 互踩（2026-09-14 T-TestPerf-03 实证 testForks=4 成片 PersistentFSConnector
    // 异常；分片任务注释同根因先例 191 失败）。安全并行走分片（./gradlew testShards 一命令，
    // 各片独立沙盒并发执行），见下方 testShards 定义。
    // T-TestPerf2：测试 JVM 堆可临时覆盖（Gradle Test 默认 maxHeapSize=512m），用法
    // -PtestHeapSize=2g；缺省不动，是否转正由实测收益定（见 T-TestPerf2 计划 P9）。
    providers.gradleProperty("testHeapSize").orNull?.let { maxHeapSize = it }
}

// 并行分片测试（2026-08-15 提速；2026-09-14 T-TestPerf-04 自动分区；2026-09-15 T-TestPerf2
// 时长均衡分区 + 片数可配 + 一命令并行与聚合报告）。
// 用法：./gradlew testShards 一命令即真并行（org.gradle.parallel=true，各片任务并发执行，
// 并发实证与提速数据见 docs/开发计划/20260915-0238-T-TestPerf2 计划 §六）；串行权威全量
// 仍是 ./gradlew test（对照口径保留）。
// 前提：每片独立 sandbox 目录——多 worker 共享 .intellijPlatform/sandbox/.../system-test 会
// PersistentFS 互踩损坏（实证：191 失败 + 卡死），分片隔离后无共享写点。
// 包含清单为配置期扫描 src/test 自动分区（时长权重贪心均衡，详见 testIde 块注释），
// 新增测试类零维护自动进片；DB 集成三类只在 testDatabase。
// M36.5.23：DB 集成测试默认 demo 项目路径（顶层求值，避免 task lambda 捕获 Project 破坏配置缓存）
val mpDemoProjectDirDefault = rootProject.projectDir.parentFile
    .resolve("mybatis-plus-sql-visualizer-demo").absolutePath

// T-TestPerf2：Gradle JUnit XML testsuite 根属性解析（只读文件头，避免大文件全读）与结果文件枚举。
// 供配置期时长权重与 testShards 聚合报告两处复用；解析失败静默跳过（半截 XML 不挡分区）。
fun parseTestSuiteAttrs(f: File): Map<String, String>? = runCatching {
    val head = f.useLines { lines -> lines.take(8).joinToString("\n") }
    val tag = head.substringAfter("<testsuite", "").substringBefore(">")
    if (tag.isEmpty()) null
    else Regex("([\\w.-]+)=\"([^\"]*)\"").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }
}.getOrNull()

fun listSuiteXmls(dir: File): List<File> =
    dir.listFiles { f: File -> f.isFile && f.name.startsWith("TEST-") && f.name.endsWith(".xml") }?.toList() ?: emptyList()

// T-TestPerf2：分片数，-PtestShardCount=N（默认 3，上限 6；6C12T 实测 3 片最优，2 片慢 ~40%）。
// 顶层声明——分片注册（testIde 块内）与聚合报告（testShards 任务）两处共用。
val testShardCount = providers.gradleProperty("testShardCount").orNull
    ?.toIntOrNull()?.coerceIn(1, 6) ?: 3

intellijPlatformTesting {
    testIde {
        // T-TestPerf-04（2026-09-14）分片包含清单自动分区 + T-TestPerf2（2026-09-15）时长均衡：
        // 配置期扫描 src/test 推导 FQCN 全集（DB 集成三类仍只在 testDatabase，与默认 test 口径一致），
        // 权重来自 build/test-results 下历史 XML（test/ 与 testShardN/ 全扫，同类取 mtime 最新——
        // 串行与分片互相喂养）；无数据类取已知权重中位数，全新检出零数据时全体同权，
        // 退化为按类名字典序贪心均分，仍确定性。贪心排序键 = (时长降序, 类名升序)，
        // 同输入必得同分区——双开终端两进程与配置缓存复用前后结果一致。
        // 配置缓存语义与类发现一致：新增测试类/时长变化在下一次配置失效后生效，短暂失衡不影响正确性。
        // 片数：-PtestShardCount=N（默认 3）。旧类名哈希两片实测失衡 26%（157.9s/93.7s），
        // 时长均衡两片 125.8s/125.8s——并行墙钟被重片拖死的问题根除（数据见 T-TestPerf2 计划 §二）。
        // 单遍扫描测试源码：Triple(包名, 顶层测试类名列表, 原文)。类名从内容提取而非文件名——
        // Kotlin 文件名≠类名合法（实证 CrudPreviewFlowAndTypesTest.kt 内是 class DbTypeQuoteHintTest，
        // 3 用例曾被「文件名=FQCN」旧法漏掉，分片并集比串行少 3）；列 0 的 class 声明含 Kotlin/Java
        // 常见修饰符；无顶层类声明时回退文件名（嵌套类套件等形态）。新增测试类零维护自动进片。
        // DB 集成三类仍只在 testDatabase 运行（与默认 test 的排除口径一致）。
        val dbIntegrationClasses = setOf(
            "cn.song.mybatisplus.visualizer.execute.DbConsoleIntegrationTest",
            "cn.song.mybatisplus.visualizer.execute.DataSourceSelectorIntegrationTest",
            "cn.song.mybatisplus.visualizer.execute.HeavyNativeDatabaseDiagramJoinChainTest",
        )
        val testFileEntries: List<Triple<String, List<String>, String>> = file("src/test/kotlin").walkTopDown()
            .filter { it.isFile && (it.name.endsWith("Test.kt") || it.name.endsWith("Test.java")) }
            .map { f ->
                val text = f.readText()
                val pkg = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
                    .find(text)?.groupValues?.get(1) ?: ""
                val classNames = Regex(
                    """(?m)^(?:public\s+|internal\s+|open\s+|abstract\s+|final\s+|data\s+|sealed\s+|annotation\s+)*class\s+(\w+)\b""",
                ).findAll(text).mapNotNull { it.groupValues[1].takeIf { cn -> cn.endsWith("Test") } }.toList()
                Triple(pkg, classNames.ifEmpty { listOf(f.name.substringBeforeLast('.')) }, text)
            }.toList()
        val discoveredTestClasses: List<String> = testFileEntries
            .flatMap { (pkg, classNames, _) -> classNames.map { cn -> if (pkg.isEmpty()) cn else "$pkg.$cn" } }
            .filter { it !in dbIntegrationClasses }
            .sorted()
        // 时长权重表：discoveredTestClasses 全集覆盖（已知→实测秒数，未知→已知中位数）
        val durationWeights: Map<String, Double> = run {
            val resultDirs = sequenceOf(file("build/test-results/test")) +
                (1..testShardCount).asSequence().map { file("build/test-results/testShard$it") }
            val best = HashMap<String, Pair<Long, Double>>() // FQCN -> (mtime, seconds)
            for (dir in resultDirs) {
                if (!dir.isDirectory) continue
                for (f in listSuiteXmls(dir)) {
                    val attrs = parseTestSuiteAttrs(f) ?: continue
                    val name = attrs["name"] ?: continue
                    val time = attrs["time"]?.toDoubleOrNull() ?: continue
                    val mtime = f.lastModified()
                    val prev = best[name]
                    if (prev == null || mtime >= prev.first) best[name] = mtime to time
                }
            }
            val known = best.values.map { it.second }.sorted()
            val median = if (known.isEmpty()) 1.0 else known[known.size / 2]
            discoveredTestClasses.associateWith { best[it]?.second ?: median }
        }
        // 机器级全局资源亲和（T-TestPerf2 补丁）：Windows 剪贴板全机唯一（WClipboard.openClipboard
        // 互斥），并发分片 JVM 互抢 → cannot open system clipboard（2026-09-15 冷启动第一跑
        // shard2 实证 1 例，假失败清单「剪贴板 flake」的并行放大版）。测试源码含
        // CopyPasteManager/systemClipboard 的类固定进第 1 片——片内串行天然无争用，不改任何测试代码；
        // 与类发现同源单遍扫描，新增剪贴板用例零维护。
        val machineGlobalAffinityClasses: Set<String> = testFileEntries
            .filter { (_, _, text) -> text.contains("CopyPasteManager") || text.contains("systemClipboard") }
            .flatMap { (pkg, classNames, _) -> classNames.map { cn -> if (pkg.isEmpty()) cn else "$pkg.$cn" } }
            .toSet()
        // 贪心均衡：亲和类先固定第 1 片（discoveredTestClasses 已字典序，分区确定性不变），
        // 其余逐类放入当前最轻片（并列取最小下标，minByOrNull 语义即确定性）
        val shardAssignment: List<List<String>> = run {
            val shards = List(testShardCount) { mutableListOf<String>() }
            val loads = DoubleArray(testShardCount)
            discoveredTestClasses.filter { it in machineGlobalAffinityClasses }.forEach { name ->
                shards[0] += name
                loads[0] += durationWeights[name] ?: 0.0
            }
            durationWeights.entries
                .filter { it.key !in machineGlobalAffinityClasses }
                .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .forEach { entry ->
                    val target = loads.indices.minByOrNull { loads[it] }!!
                    shards[target] += entry.key
                    loads[target] += entry.value
                }
            shards
        }
        logger.lifecycle(
            "testShards: $testShardCount shards duration-balanced, loads=" +
                shardAssignment.indices.joinToString(" ") { i ->
                    "shard${i + 1}=%.1fs/%dclasses".format(
                        shardAssignment[i].sumOf { durationWeights[it] ?: 0.0 },
                        shardAssignment[i].size,
                    )
                }
        )
        for (i in 1..testShardCount) {
            val idx = i - 1
            register("testShard$i") {
                testFramework(TestFrameworkType.Platform)
                sandboxDirectory.set(layout.buildDirectory.dir("test-sandbox/shard$i"))
                task {
                    // IPGP 只给默认 test 任务自动配置 sourceSets 输出，分片任务需补齐
                    testClassesDirs = sourceSets["test"].output.classesDirs
                    classpath = classpath + sourceSets["test"].output
                    // T-McpToolset2：分片同样需要 mcpStub 桩产物（工具级门控测试实例化 Tool 类，
                    // 超类型 McpToolset 来自桩源集；root 侧 test 任务已挂，分片默认不含——
                    // 漏挂致 NoClassDefFoundError 成片）。mcpStub 源集由根脚本在 convention
                    // 应用后创建，此 task{} 块惰性求值（任务实现期）故 named 可解析。
                    classpath = classpath + files(sourceSets.named("mcpStub").map { it.output })
                    filter {
                        shardAssignment[idx].forEach { includeTestsMatching(it) }
                    }
                    // 并发审计锚点：起止毫秒时间戳，验证一命令多片是否真并行（T-TestPerf-05）
                    doFirst { logger.lifecycle("testShard$i START ${System.currentTimeMillis()}") }
                    doLast { logger.lifecycle("testShard$i END ${System.currentTimeMillis()}") }
                }
            }
        }
        register("testDatabase") {
            testFramework(TestFrameworkType.Platform)
            sandboxDirectory.set(layout.buildDirectory.dir("test-sandbox/database"))
            task {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = classpath + sourceSets["test"].output
                // M36.5.23：DB 集成测试配置
                //  - mpDemoProjectDir：demo 项目路径（读 dataSources.xml 连接信息），默认兄弟目录。
                //    密码硬编码在测试里（root/root，从 demo application.yml 复制，本地开发库无敏感性）。
                systemProperty(
                    "mpDemoProjectDir",
                    providers.gradleProperty("mpDemoProjectDir").getOrElse(mpDemoProjectDirDefault),
                )
                // 代理透传（与 runIde/verifyPlugin 一致）——DBN createBlocking 内部 HttpRequests
                // 访问外部（驱动元数据/校验）需要代理；无代理时建连失败 → introspect 返回 false → skip
                systemProperty("http.proxyHost", "127.0.0.1")
                systemProperty("http.proxyPort", "7890")
                systemProperty("https.proxyHost", "127.0.0.1")
                systemProperty("https.proxyPort", "7890")
                // 沙盒首次启动前预置 runIde 沙盒已下载的 jdbc-drivers（MySQL Connector/J），
                // 免集成测试联网下驱动；runIde 沙盒不存在时静默跳过（首次连接时 DBN 自行下载）。
                // 注意：doFirst 内不可引用 rootProject/layout（配置缓存不支持序列化 Project），
                // 用相对路径 File（Test 任务工作目录 = 项目根）。
                // M36.5.23d：IPGP testIde 任务的实际 config 目录是 `config_<任务名>`（非 `config`）——
                // DBN 从 config_testDatabase/jdbc-drivers 读 driver 文件，复制到 `config/` 无效
                //（实证：RemoteDriver.classpath.txt 只含 jdbc-console.jar，缺 mysql jar → ClassNotFound）。
                doFirst {
                    val srcDrivers = File(".intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/config/jdbc-drivers")
                    val dstDrivers = File("build/test-sandbox/database/config_testDatabase/jdbc-drivers")
                    if (srcDrivers.isDirectory && !dstDrivers.isDirectory) {
                        dstDrivers.parentFile.mkdirs()
                        srcDrivers.copyRecursively(dstDrivers)
                        logger.lifecycle("testDatabase: jdbc-drivers pre-seeded to config_testDatabase (${srcDrivers.name})")
                    }
                    // M36.5.23d：driver 就绪状态——databaseDrivers.xml 记录 mysql.8 已配置 artifact
                    // （LocalDatabaseDriverManager）。测试沙盒缺此文件 → DBN 认为 driver 未配置 →
                    // RemoteDriver.classpath.txt 缺 mysql jar → ClassNotFoundException。一并复制。
                    val srcDriverState = File(".intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/config/options/databaseDrivers.xml")
                    val dstOptions = File("build/test-sandbox/database/config_testDatabase/options")
                    val dstDriverState = File(dstOptions, "databaseDrivers.xml")
                    if (srcDriverState.isFile && !dstDriverState.isFile) {
                        dstOptions.mkdirs()
                        srcDriverState.copyTo(dstDriverState)
                        logger.lifecycle("testDatabase: databaseDrivers.xml pre-seeded (driver ready state)")
                    }
                    // T-Embed-08：单元测试模式下 DatabaseArtifactLoader 只认 config 根的
                    // test-database-artifacts.xml 作为 artifact 清单（getTestArtifactLists，
                    // 字节码实证）——缺失时 getArtifactClasspathElements 恒空 → 远程 JDBC 进程
                    // ClassNotFound。把同一份 jdbc-drivers.xml 目录清单复制为测试清单即可。
                    val srcArtifactList = File("build/test-sandbox/database/config_testDatabase/jdbc-drivers/jdbc-drivers.xml")
                    val dstArtifactList = File("build/test-sandbox/database/config_testDatabase/test-database-artifacts.xml")
                    if (srcArtifactList.isFile && !dstArtifactList.isFile) {
                        srcArtifactList.copyTo(dstArtifactList)
                        logger.lifecycle("testDatabase: test-database-artifacts.xml seeded (unit-test artifact list)")
                    }
                    // 同理：单测模式下驱动定义（含已下载版本）走 test-database-drivers.xml——
                    // 缺失时 DatabaseDriver 的 artifact ref 无具体 version → classpath 仍为空。
                    val dstTestDrivers = File("build/test-sandbox/database/config_testDatabase/test-database-drivers.xml")
                    if (!dstTestDrivers.isFile) {
                        dstTestDrivers.writeText(
                            """
                            <drivers>
                              <driver id="mysql.8">
                                <artifact id="MySQL ConnectorJ" name="MySQL Connector/J" use="true" version="9.7.0"/>
                              </driver>
                            </drivers>
                            """.trimIndent(),
                        )
                        logger.lifecycle("testDatabase: test-database-drivers.xml seeded (driver with pinned artifact version)")
                    }
                }
                filter {
                includeTestsMatching("cn.song.mybatisplus.visualizer.execute.DbConsoleIntegrationTest")
                // M36.5.23 续：DataSourceSelector 选择/发现层集成骨架（仅 testDatabase 跑，database jar 在 classpath）
                includeTestsMatching("cn.song.mybatisplus.visualizer.execute.DataSourceSelectorIntegrationTest")
                // T-Embed-03/04/08：B 路径 Database 原生图 + 虚拟外键 JOIN 线真实链路
                includeTestsMatching("cn.song.mybatisplus.visualizer.execute.HeavyNativeDatabaseDiagramJoinChainTest")
            }
            }
        }
    }
}

tasks.register("testShards") {
    group = "verification"
    description =
        "分片全量测试（时长均衡自动分区 $testShardCount 片，一命令真并行；-PtestShardCount 调片数）"
    dependsOn(*(1..testShardCount).map { "testShard$it" }.toTypedArray())
    // 聚合报告（T-TestPerf2）：分片跑完直接打印各片与总数四元组，与串行口径的计数 parity 目测即得。
    // 配置缓存约束：结果目录在配置期解析为 File，doLast 只做文件 IO 不触达 Project/layout，
    // 且解析辅助函数须定义在 doLast 体内——引用脚本顶层函数会整段不可序列化（实证报错）。
    val shardResultDirs = (1..testShardCount).map { file("build/test-results/testShard$it") }
    doLast {
        fun suiteXmls(dir: File): List<File> =
            dir.listFiles { f: File -> f.isFile && f.name.startsWith("TEST-") && f.name.endsWith(".xml") }?.toList() ?: emptyList()
        fun suiteAttrs(f: File): Map<String, String>? = runCatching {
            val head = f.useLines { lines -> lines.take(8).joinToString("\n") }
            val tag = head.substringAfter("<testsuite", "").substringBefore(">")
            if (tag.isEmpty()) null
            else Regex("([\\w.-]+)=\"([^\"]*)\"").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }
        }.getOrNull()
        var total = 0
        var failed = 0
        var errored = 0
        var skipped = 0
        var execSeconds = 0.0
        shardResultDirs.forEachIndexed { idx, dir ->
            var n = 0
            var f = 0
            var e = 0
            var s = 0
            var t = 0.0
            val xmls = suiteXmls(dir)
            if (xmls.isEmpty()) {
                logger.lifecycle("testShard${idx + 1}: $dir 下无结果 XML（该分片未跑？）")
            }
            for (xml in xmls) {
                val attrs = suiteAttrs(xml) ?: continue
                fun num(key: String) = attrs[key]?.toDoubleOrNull() ?: 0.0
                n += num("tests").toInt()
                f += num("failures").toInt()
                e += num("errors").toInt()
                s += num("skipped").toInt()
                t += num("time")
            }
            logger.lifecycle(
                "testShard${idx + 1}: tests=$n failures=$f errors=$e skipped=$s time=%.1fs".format(t),
            )
            total += n; failed += f; errored += e; skipped += s; execSeconds += t
        }
        logger.lifecycle(
            "SHARD TOTAL: tests=$total failures=$failed errors=$errored skipped=$skipped " +
                "(parity 口径 = 串行 ./gradlew test 计数；两者同出一份自动分区清单，DB 集成三类都在 testDatabase)"
        )
    }
}

// P-立项-05 真机验证：-Dmpvs.* 透传到测试 JVM（渲染导出 DialectRenderDumpTest / 外部实例入口）
tasks.test {
    System.getProperties().forEach { (k, v) ->
        if (k is String && k.startsWith("mpvs.") && v is String) systemProperty(k, v)
    }
    filter {
        // M36.6.1：DB 集成测试仅在 testDatabase 任务运行（其 classpath 含 database jar）；
        // 默认 test 已排除 database jar（上方 withType<Test>），该测试类加载即 NoClassDefFoundError，
        // 必须排除——否则 "./gradlew test 不受影响" 的约定（M36.5.23）被破坏
        excludeTestsMatching("cn.song.mybatisplus.visualizer.execute.DbConsoleIntegrationTest")
        // M36.5.23 续：DataSourceSelector 选择/发现层集成骨架同样依赖 database jar（默认 test 已剥离）
        excludeTestsMatching("cn.song.mybatisplus.visualizer.execute.DataSourceSelectorIntegrationTest")
        // T-Embed-03/04/08：B 路径 Database 原生图嵌入链路（虚拟外键注入），同上仅在 testDatabase 运行
        excludeTestsMatching("cn.song.mybatisplus.visualizer.execute.HeavyNativeDatabaseDiagramJoinChainTest")
    }
}

// T-TestPerf-03（2026-09-14）实验结论：不引入纯逻辑并行分桶任务。
// 三点实证：① 多 fork 并行平台型测试共享默认 test 沙盒 → PersistentFS 互踩成片失败；
// ② 独立纯逻辑桶 task 可行（classpath 须复用默认 test 任务成品、排除须统一走 filter 机制——
//    legacy exclude 与 filter 混用时 filter 优先级覆盖 exclude、159 条 exclude 全被忽略）；
// ③ 但 2246 个纯逻辑分类用例中 68 个（forks=1 对照同败）隐性依赖同 worker JVM 里
//    平台测试先建立的 Application/LaF 状态，脱离平台桶即红——作为快测任务会误报。
// 结论：全量权威=串行 ./gradlew test；真并行=./gradlew testShards 一命令（T-TestPerf2 收编，
// 时长均衡自动分区；旧「双开终端」用法仍可用但已非必需）；
// 日常提速最大头=关闭 build cache 根除假失败整轮重跑（gradle.properties）。
