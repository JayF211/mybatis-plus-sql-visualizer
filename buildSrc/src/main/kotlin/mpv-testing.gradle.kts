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
// T-TestPerf3（2026-09-17）4 片复测：3m02s vs 3 片 2m03s——4 片并发超出物理核竞争反而慢，
// 默认 3 片定案维持。
// 顶层声明——分片注册（testIde 块内）与聚合报告（testShards 任务）两处共用。
val testShardCount = providers.gradleProperty("testShardCount").orNull
    ?.toIntOrNull()?.coerceIn(1, 6) ?: 3

// 测试源码单遍扫描（T-TestPerf3 提为顶层：分片分区与 testFast 划桶两处共用；内容提取类名，
// Kotlin 文件名≠类名合法——CrudPreviewFlowAndTypesTest.kt 内是 DbTypeQuoteHintTest 的先例）。
// Triple(包名, 顶层测试类名列表, 原文)。
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

// T-TestPerf3（2026-09-17）：testFast 秒级反馈通道。
// 划桶规则（保守）：源码零 `com.intellij` 引用的测试类才进快桶——凡是 import 平台类的
// 一律留平台桶。第一轮 T-TestPerf-03 实证 68 个「纯逻辑」用例隐性依赖同 worker JVM 平台
// 测试先建立的 Application/LaF 状态（脱离平台桶即红）；本轮基线数据（平台型 1790 用例
// 222.1s = 94.7%、纯逻辑 2531 用例 12.5s = 5.3%）同时证伪了「纯逻辑分桶给全量提速」的
// 价值（12.5s 总量无提速空间，68 例债务清偿亦无收益——正式销账，勿再立项）。
// 本任务的价值重定位：**日常开发的秒级反馈**——改纯逻辑面（parser/converter/tokenizer/
// 守卫测试）时 ./gradlew testFast 半分钟内验证，不必等 4 分钟全量。全量权威仍是 test/testShards。
// classpath 复用默认 test 任务成品（IPGP 把平台 jar 直配任务不进 sourceSets.runtimeClasspath，
// T-TestPerf-03 实验趟通）；DB 集成四类 import 平台 Database 链天然不进桶；forks=1（12s 总量
// 无并行收益，避免新假失败形态）。
val fastBucketClasses: List<String> = testFileEntries
    .filter { (_, _, text) -> !text.contains("com.intellij") }
    .flatMap { (pkg, classNames, _) -> classNames.map { cn -> if (pkg.isEmpty()) cn else "$pkg.$cn" } }
    // 隐性平台依赖排除名单：测试源码无 com.intellij 字面引用，但被测生产类内部走平台 UI/
    // 静态状态，脱离平台 JVM 即红（testFast 首跑 4 类 28 例实证；失败形态=NoClassDefFoundError
    // UIUtil / Application NPE / Must be precomputed）。新类踩同坑时在此追加。
    .filter { it !in setOf(
        "cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanelTest",
        "cn.song.mybatisplus.visualizer.ui.diagram.DiagramRendererTest",
        "cn.song.mybatisplus.visualizer.actions.CrudSqlPreviewFlowTest",
        "cn.song.mybatisplus.visualizer.feedback.ErrorReportDialogLayoutTest",
    ) }

tasks.register<Test>("testFast") {
    group = "verification"
    description =
        "纯逻辑快测（源码零 com.intellij 引用的测试类，秒级反馈通道；全量权威仍是 test/testShards）"
    testClassesDirs = sourceSets["test"].output.classesDirs
    // classpath 三段构造（T-TestPerf3 实测两轮定案）：
    //  ① main+test output **前置**——PluginBuildInfo 的「开发/测试形态类从 classes 目录加载、
    //     无 jar 可读 → mpvBuild()=null」是测试前提（testMpvBuildNullInTestEnvironment）；
    //     直接复用默认 test 成品 classpath 时插件自身 jar（IPGP/mcpStub 挂载、含构建脚本注入的
    //     MPV-Build manifest）排在可达位，类解析撞 jar 读出真实构建时间戳 → 假失败；
    //  ② 平台 jar 仍须复用默认 test 成品（IPGP 平台 jar 直配任务、不进 sourceSets.runtimeClasspath，
    //     T-TestPerf-03 趟通；生产类 companion 的 Logger.getInstance 等静态初始化在类加载期解析平台类）；
    //  ③ 剔除插件自身 jar——①前置后它只剩干扰面（manifest 读取前提 + 陈旧产物劫持形态）无收益。
    classpath = sourceSets["main"].output + sourceSets["test"].output +
        tasks.named<Test>("test").get().classpath.filter {
            !it.name.startsWith("mybatis-plus-sql-visualizer-")
        }
    filter {
        fastBucketClasses.forEach { includeTestsMatching(it) }
    }
    maxParallelForks = 1
}

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
        // 类发现走顶层 testFileEntries（T-TestPerf3 提升为顶层，与 testFast 划桶共用单遍扫描）；
        // 列 0 的 class 声明含 Kotlin/Java 常见修饰符；无顶层类声明时回退文件名（嵌套类套件等形态）。
        // 新增测试类零维护自动进片。
        // DB 集成三类仍只在 testDatabase 运行（与默认 test 的排除口径一致）。
        val dbIntegrationClasses = setOf(
            "cn.song.mybatisplus.visualizer.execute.DbConsoleIntegrationTest",
            "cn.song.mybatisplus.visualizer.execute.DataSourceSelectorIntegrationTest",
            "cn.song.mybatisplus.visualizer.execute.HeavyNativeDatabaseDiagramJoinChainTest",
            // T-ExecDocGate：执行文档落盘纪律断言（testDatabase 通道，Database 插件链）
            "cn.song.mybatisplus.visualizer.execute.ExecDocGateIntegrationTest",
            // T-TestDbDriver：驱动解析管线回归（artifact 清单/版本解析/classpath 落点）
            "cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest",
        )
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
                // T-Fix-TestDb 20260918：**每类一个测试 JVM**（forkEvery=1）。DB 集成类依赖的 DBN
                // 状态是**应用级**的：数据源（LocalDataSourceManager）、控制台登记
                // （JdbcConsole$ScriptingDatabaseSessionClientHolder 的 project user data 列表）、
                // 引擎/会话、DbPsiFacade 快照——同 JVM 内跨类串扰已实测出多条失败：
                //   ① 前序类残留数据源让「恰好 N 个 runner」契约读到别人建的数据源；
                //   ② 移除数据源触发的引擎终止与后一类的 attachConsole 并发 → DBN 清扫
                //      （getActiveClients 逐条 dispose isValid()=false 的控制台）顺手回收刚建的控制台
                //      （`console DISPOSED right after attach`，插件 T-PreviewExecDs-03 F3 同款现象）；
                //   ③ exec 文件路径在同一个 light 工程里恒定 → 控制台/文档/VFS 状态跨类残留。
                // 测试侧已做的隔离（稳定工程目录 + 用例边界摘控制台/清文档）无法消除①②的竞态，
                // 因为它们来自应用级单例；分 JVM 是这类「共享应用级状态」平台测试的标准隔离手段。
                // 代价：5 个类各一次 JVM 冷启动（约 +2~3 分钟）。
                forkEvery = 1
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
                // T-ExecDocGate 20260917：doFirst 需要项目根绝对路径——相对路径在 doFirst 按
                // daemon CWD 解析（实测为 .gradle/daemon/<ver>，跨构建不一致）；直接捕获
                // project 字符串又是脚本对象引用（配置缓存拒绝）。inputs.property 是官方
                // CC 兼容通道：执行期经 inputs.properties 读回
                inputs.property("mpProjectDirAbs", project.projectDir.absolutePath)
                // 沙盒首次启动前预置 runIde 沙盒已下载的 jdbc-drivers（MySQL Connector/J），
                // 免集成测试联网下驱动；runIde 沙盒不存在时静默跳过（首次连接时 DBN 自行下载）。
                // 注意：doFirst 内不可引用 rootProject/layout（配置缓存不支持序列化 Project），
                // 用相对路径 File（Test 任务工作目录 = 项目根）。
                // M36.5.23d：IPGP testIde 任务的实际 config 目录是 `config_<任务名>`（非 `config`）——
                // DBN 从 config_testDatabase/jdbc-drivers 读 driver 文件，复制到 `config/` 无效
                //（实证：RemoteDriver.classpath.txt 只含 jdbc-console.jar，缺 mysql jar → ClassNotFound）。
                doFirst {
                    // T-ExecDocGate 20260917：种子源三级回退（本地 runIde 沙盒 → dev 工作树沙盒 →
                    // 日常 IDE 驱动存储）。runIde 沙盒 clean 后原版静默跳过 → 驱动缺失 →
                    // RemoteJdbcServer ClassNotFound（四集成类 introspect 全跳，本日实测）。
                    // 一切路径绝对锚定（见上方 inputs.property 注释）。
                    val root = inputs.properties["mpProjectDirAbs"] as String
                    val configRoot = File(root, "build/test-sandbox/database/config_testDatabase")
                    val driverDir = File(configRoot, "jdbc-drivers")
                    val sandboxDrivers = File(
                        root,
                        ".intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/config/jdbc-drivers",
                    )
                    val devSandboxDrivers = File(
                        root,
                        "../mybatis-plus-sql-visualizer-dev/.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/config/jdbc-drivers",
                    )
                    // ① artifact 清单：两级沙盒源（xml；通常不含 jar）
                    if (!File(driverDir, "jdbc-drivers.xml").isFile) {
                        val src = listOf(sandboxDrivers, devSandboxDrivers).firstOrNull { it.isDirectory }
                        if (src != null) {
                            driverDir.mkdirs()
                            File(src, "jdbc-drivers.xml").copyTo(File(driverDir, "jdbc-drivers.xml"))
                            logger.lifecycle("testDatabase: jdbc-drivers.xml pre-seeded from ${src.path}")
                        }
                    }
                    // ② jar 树：日常 IDE 驱动存储（Roaming，maven 布局；本机实存 9.7.0）。
                    //    逐文件 mkdirs——copyRecursively walkBottomUp 深层文件先于父目录落地会
                    //    ERROR_PATH_NOT_FOUND（实测）
                    val artifactVersion = "9.7.0"
                    val roamingVersionDir = File(
                        System.getProperty("user.home"),
                        "AppData/Roaming/JetBrains/IntelliJIdea2024.3/jdbc-drivers/MySQL ConnectorJ/$artifactVersion",
                    )
                    val versionDir = File(driverDir, "MySQL ConnectorJ/$artifactVersion")
                    if (roamingVersionDir.isDirectory && !versionDir.isDirectory) {
                        var copied = 0
                        roamingVersionDir.walkTopDown().filter { it.isFile }.forEach { f ->
                            val out = File(versionDir, f.relativeTo(roamingVersionDir).path)
                            out.parentFile.mkdirs()
                            if (runCatching { f.copyTo(out, overwrite = true) }.isSuccess) copied++
                        }
                        logger.lifecycle("testDatabase: mysql connector jar tree pre-seeded (copied=$copied)")
                    }
                    // ③ driver 就绪状态：必须含 mysql.8 的 artifact 版本条目（真实 IDE 同款；
                    //    空壳文件时 DBN 不知道 mysql.8 解析哪个版本 → classpath 恒空 → CNFE）
                    val driverState = File(configRoot, "options/databaseDrivers.xml")
                    if (!driverState.isFile || driverState.readText().contains("mysql.8").not()) {
                        File(configRoot, "options").mkdirs()
                        driverState.writeText(
                            "<application>\n" +
                                "  <component name=\"LocalDatabaseDriverManager\" version=\"201\">\n" +
                                "    <driver id=\"mysql.8\">\n" +
                                "      <artifact id=\"MySQL ConnectorJ\" name=\"MySQL ConnectorJ\" use=\"true\" version=\"$artifactVersion\" />\n" +
                                "    </driver>\n" +
                                "  </component>\n" +
                                "</application>\n",
                        )
                    }
                    // ④ 单测模式 artifact 清单副本 + ⑤ driver 定义钉版本（T-Embed-08 原逻辑）
                    // T-TestDbDriver 20260918：落点改 config 根下的 options/ ——
                    // DatabaseArtifactLoaderImpl.getTestArtifactLists() 经
                    // DatabaseDriverManagerImpl.existentTestPath(name) 只认
                    // `<homePath>/dbe/database/testResources/<name>` 与 `<configPath>/options/<name>`
                    // （bytecode 常量池 \u0001/options/\u0001 实证）；原落 config 根 → 探针实测
                    // getTestArtifactLists() 恒空（该文件从未被 DBN 读到）。
                    val manifest = File(driverDir, "jdbc-drivers.xml")
                    val dstArtifactList = File(configRoot, "options/test-database-artifacts.xml")
                    if (manifest.isFile && !dstArtifactList.isFile) {
                        dstArtifactList.parentFile.mkdirs()
                        manifest.copyTo(dstArtifactList)
                        logger.lifecycle("testDatabase: test-database-artifacts.xml seeded (options/)")
                    }
                    // ⑤ driver 定义（test-database-drivers.xml）不再由本块落盘：
                    //    ① DBN 在单测模式显式忽略该名——DatabaseDriverManagerImpl.existentTestPath
                    //       bytecode 实证 `isUnitTestMode() && name.endsWith("test-database-drivers.xml")`
                    //       → 直接 return null；② 原落盘用的是相对路径 `build/test-sandbox/...`，
                    //       按 Gradle daemon 进程 CWD 解析（见上方注释实测）。落盘职责归测试侧
                    //       DbIntegrationSeedSupport（PathManager.getConfigPath() 绝对锚定）。
                }
                filter {
                includeTestsMatching("cn.song.mybatisplus.visualizer.execute.DbConsoleIntegrationTest")
                // M36.5.23 续：DataSourceSelector 选择/发现层集成骨架（仅 testDatabase 跑，database jar 在 classpath）
                includeTestsMatching("cn.song.mybatisplus.visualizer.execute.DataSourceSelectorIntegrationTest")
                // T-Embed-03/04/08：B 路径 Database 原生图 + 虚拟外键 JOIN 线真实链路
                includeTestsMatching("cn.song.mybatisplus.visualizer.execute.HeavyNativeDatabaseDiagramJoinChainTest")
                // T-ExecDocGate：执行文档落盘纪律断言（prepare/reuse/手改防抖三断言）
                includeTestsMatching("cn.song.mybatisplus.visualizer.execute.ExecDocGateIntegrationTest")
                // T-TestDbDriver：驱动解析管线回归
                includeTestsMatching("cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest")
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
        // T-ExecDocGate：执行文档落盘纪律断言，同上仅在 testDatabase 运行
        excludeTestsMatching("cn.song.mybatisplus.visualizer.execute.ExecDocGateIntegrationTest")
        // T-TestDbDriver：驱动解析管线回归，同上仅在 testDatabase 运行
        excludeTestsMatching("cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest")
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
