import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    // T-BuildScriptSplit：构建逻辑分治——convention 插件见 buildSrc/src/main/kotlin/，
    // 块级逐字搬运自本文件（锚点 dc047bd，注释随迁），来源与去向见计划 §2.1。
    // T-SyncK2JMaster309：buildSrc 约定插件整包自 master 直取（k2j 原单体根构建退役），
    // k2j 自有块（Lombok 注释块/test kotlin srcDir/-parameters/临时沙盒行）逐块并回本文件。
    id("mpv-verification")
    id("mpv-testing")
    id("mpv-runide")
    id("mpv-release")
}

// T-GifRecordSwitch（2026-09-11）：演示 GIF 录制环境开关。录制需在 2026.2 沙盒运行（官方同款
// 暗色基准 + record/ 脚本管线），但 262 下 test 源码编译不过——com.intellij.diagram.* 于 262 移入
// Database 插件（测试 classpath 未含），且新版 Kotlin 把 String.toLowerCase() 升为硬错误，导致
// 分支自身无法跑 ./gradlew test。故把录制所需四项配置整体收敛为本开关：
//   默认            -> 243 常规开发态（平台 2024.3.7.1 / toolchain 17 / 无 jcef / 默认沙盒）
//   -PgifRecord=true -> 262 录制态（平台 2026.2.1 / toolchain 24 / jcef / 独立沙盒 MPSV-GifFresh）
// 四项必须整体切换、不可拆：jcef 自 262 起才从平台核心拆为独立模块（243 distro 无此模块，
// 单独声明会导致依赖解析失败）；jvmToolchain(24) 用于读 262 平台 v69 类文件（javac 17/21 报
// 「错误的版本 69.0」）；独立沙盒避免与并行任务互锁。
// 录制用法：./gradlew runIde -PgifRecord=true
val gifRecord = providers.gradleProperty("gifRecord").orNull?.toBoolean() ?: false

// 闸门：录制开关若与发布/打包任务同用，配置期直接失败——防止 262 录制态流入发行产物。
// 与既有 verifyIdes 同款写法（读 startParameter.taskNames），兼容 configuration-cache。
// 局限：仅拦截「命令行直接点名」的发布任务（./gradlew buildPlugin 等）；经依赖间接触达的不拦。
if (gifRecord) {
    val releaseTasks = setOf("buildPlugin", "publishPlugin", "publish")
    val invoked = gradle.startParameter.taskNames
        .map { it.substringAfterLast(':') }
        .filter { it in releaseTasks }
    if (invoked.isNotEmpty()) {
        throw GradleException(
            "gifRecord=true 是演示录制专用（构建基准 2026.2.1），禁止用于发布/打包任务 $invoked。" +
                "请去掉 -PgifRecord=true 后重试。"
        )
    }
}

// T-McpToolset：MCP Server 插件（com.intellij.mcpServer）编译期 API 桩源集。
// 基线平台 243 不含 mcpServer 插件，而本机 262 内置 mcpserver.jar 类文件是 major 69（Java 25），
// JDK 17 编译读不了（javac 17/21 报「错误的版本 69.0」，T-GifRecordSwitch 注释同源实证）——
// 故用桩源码提供与真实 jar 完全一致的编译期签名（签名逐条 javap 自 262 jar，见桩文件头注释）。
// 接线方式（防 task 依赖环）：
//  - 主编译任务 compileKotlin.libraries 挂桩产物目录（task 依赖：compileKotlin → compileKotlinMcpStub）；
//  - 桩源集编译类路径复用 main 的 compileClasspath（平台 jar/stdlib/JSqlParser 等，不含桩产物，
//    天然无环——若桩 classpath 反向含 main.compileClasspath 且 main 含桩产物则成环）；
//  - 桩产物只进编译 classpath，**不进运行时与打包产物**（运行时类型由平台内置插件提供，
//    253+/262 内置；插件 jar 携带 com.intellij.mcpserver.* 会与平台类冲突）。
// k2j 适配（T-SyncK2JMaster309）：主源集是 Java，主编译挂 compileJava.classpath（master 挂
// compileKotlin.libraries 随主语言差异改写，语义一致——桩只进编译 classpath，不进 runtime/打包）。
val mcpStub: SourceSet by sourceSets.creating

sourceSets {
    named("mcpStub") {
        compileClasspath += sourceSets.main.get().compileClasspath
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // k2j 专属：迁移期 Lombok 已登记于版本目录（备选），但首波转换实际全部采用 record + 手写 POJO，
    // 零 Lombok 依赖可让本机离线构建通过（`--offline` 下 lombok 无缓存会阻塞 compileClasspath）。
    // 后续若确需 Lombok（可变 data class 减样板），取消下两行注释并确保联网解析一次。
    // compileOnly(libs.lombok)
    // annotationProcessor(libs.lombok)
    testImplementation(libs.junit)
    // T-M23.13：真实 MP jar 进测试 classpath（Heavy 语义黄金用例——方法签名/注解元数据可解析；
    // 仅测试依赖，插件运行时零依赖不变；MPJ 1.5.x 不在 Central，暂不加）
    testImplementation(libs.mp.core)
    // P-立项-05：方言真实验证——H2 嵌入式（兼容模式模拟 MySQL/MariaDB/PG/Oracle/MSSQLServer 语法）
    // 与 sqlite-jdbc 内嵌原生 SQLite 引擎。仅测试 classpath，插件运行时零依赖不变。
    testImplementation(libs.h2)
    testImplementation(libs.sqlite.jdbc)
    // P-立项-05 Phase 2：Docker 真实环境验证（Testcontainers + 各库 JDBC 驱动，仅测试 classpath）。
    // 本机无 Docker 时 DialectDockerVerificationTest 自动 Assume 跳过；依赖照常解析不影响 CI。
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mssqlserver)
    testImplementation(libs.testcontainers.oracle.xe)
    testImplementation(libs.mysql.connector)
    testImplementation(libs.postgresql)
    testImplementation(libs.mssql.jdbc)
    testImplementation(libs.mariadb.java.client)
    testImplementation(libs.ojdbc11)
    // T-DialectLabMatrix：配方一致性守卫的 YAML 解析（仅测试 classpath）
    testImplementation(libs.snakeyaml)

    // P-立项-05：jackson 版本约束——testcontainers 传递的 jackson-annotations 2.10.3 缺失
    // JsonProperty.namespace()（2.11+ 才有），在测试 classpath 上遮蔽 IDEA 平台所用的新版
    // jackson，导致平台 XML PSI 代码 NoSuchMethodError。三件套统一对齐 2.17.1
    // （testcontainers 1.19.3 兼容；jackson-annotations 向后兼容）。
    constraints {
        testImplementation("com.fasterxml.jackson.core:jackson-annotations:2.17.1") {
            because("IDEA 平台调用 JsonProperty.namespace()，testcontainers 传递的 2.10.3 缺失该方法")
        }
        testImplementation("com.fasterxml.jackson.core:jackson-core:2.17.1")
        testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    }
    // F-1/F-xx：JOIN 关系可视化图（自绘 Swing Graphics2D 渲染，零外部依赖）
    // F-xx 替代方案：移除 PlantUML 1.2024.8 (21.9MB) + Batik (~8MB)，改用自绘引擎
    // F-2：SQL 性能分析（JSqlParser 5.3 — 零依赖 SQL 解析器，静态分析用）
    implementation("com.github.jsqlparser:jsqlparser:5.3")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // M17：构建平台 = **最低支持版本 Ultimate 2024.3.7.1**（T-M30：最低兼容降至 IDEA 243/2024.3）。
        // 官方兼容策略：以最低版本为编译基准，公共 API 向后兼容保证 2025.x/2026.x 可运行；
        // Plugin Verifier 按目标 IDE 集合校验。database API 在 com.intellij.database（仅 Ultimate）。
        // Kotlin 规则（T-M30）：多版本支持须以最低捆绑 stdlib 为编译目标——2024.3 捆绑 stdlib 2.0.21，
        // 平台 distro 进编译 classpath 后自动满足（kotlin.stdlib.default.dependency=false 不打包 stdlib）。
        // 注：本机日常 IDE 为 Ultimate 2026.2.3（2026-09-17 起，T-VerifyIde2623），243 平台需下载（gradle.properties 已配代理）。
        intellijIdeaUltimate(if (gifRecord) "2026.2.1" else "2024.3.7.1")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        // T-GifRecordSwitch：JCEF 自 262 起才从平台核心拆为独立模块（243 distro 无此模块），
        // 故仅录制态声明；常规态声明会导致依赖解析失败。
        if (gifRecord) {
            bundledPlugin("com.intellij.modules.jcef")
        }
        // T-M24-04：XML 平台模块（XML PSI 定位语句 + XML gutter；平台 bundled，IDE 恒启用）。
        // T-M30：构建基准降至 2024.3 后 XML PSI 类在平台核心（app.jar，lib/modules 无独立模块），
        // bundledModule("intellij.xml.psi") 在 2024.3 distro 中不存在（253+ 才拆分为该模块）——移除声明，
        // 编译期类来自平台核心，运行期 253+ 由平台 XML 模块提供（com.intellij.modules.xml 依赖已声明）。
        // bundledModule("intellij.xml.psi")
        // M17：Database 插件（数据源/控制台 API，仅 Ultimate 捆绑）。
        // 注：2026.2 中 SQL 语言支持（com.intellij.sql）已并入平台核心，无需/无法单独声明
        bundledPlugin("com.intellij.database")
        // T-Dgm-01：Diagrams 插件（类图/关系图底座，仅 Ultimate 捆绑）——JOIN 图原生渲染编译期类。
        // 与 database 同为运行期可选依赖（plugin.xml optional depends + diagram-optional.xml EP 注册）。
        bundledPlugin("com.intellij.diagram")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// F-xx：已移除 Batik/PlantUML 依赖，无需 xml-apis 排除规则。

// 纯 Kotlin 插件：无需 @NotNull 字节码插桩（Kotlin 编译器已处理空安全）。
// 禁用后可绕开 JDK 环境下 instrumentCode 的 jrt 模块解析问题（Windows 中文环境实测报
// "java.home/Packages does not exist"）。若后续引入 Java 源码或 GUI Forms 再评估开启。
intellijPlatform {
    instrumentCode = false
    // Windows 中文环境实测 buildSearchableOptions 报 "Locale must be default"（IDEA
    // 搜索索引器要求 JVM locale 为默认值）。禁用后 Settings 页面不进入 IDE 设置搜索
    // 索引（其余功能不受影响）；在非中文 locale 环境可改回 true 以获得设置搜索支持。
    buildSearchableOptions = false

    // T-GifRecordSwitch：录制态用独立沙盒，避免与并行任务（record/ 脚本、其他会话）
    // 的沙盒/索引互锁；常规态用默认沙盒。
    if (gifRecord) {
        sandboxContainer = layout.buildDirectory.dir("MPSV-GifFresh")
    } else {
        // T-SyncK2JMaster309 实施期临时隔离沙盒（并行登记表 T-TS-* 在册，勿互踩；
        // 收官提交前还原为无条件默认沙盒——任务专属沙盒名禁随提交入库，T-ListParamCollect 先例）。
        sandboxContainer = layout.buildDirectory.dir("test-sandbox-k2jsync309")
    }
    // 注：并行会话临时隔离请在本块内临时加自己的 sandboxContainer（用后还原并立即提交），
    // 勿让任务专属沙盒名随提交入库（T-ListParamCollect/T-HqlInhCols 两轮实证）。
}

kotlin {
    // T-GifRecordSwitch：常规态 243 平台用 17；录制态 262 平台类文件为 v69，
    // 须 javac 24 才能读（javac 17/21 报「错误的版本 69.0」）。
    jvmToolchain(if (gifRecord) 24 else 17)
}

// T-McpToolset：主编译 classpath 追加 MCP 桩产物（详见文件头 mcpStub 源集注释）。
// 只挂编译任务 classpath，不动 runtime classpath/打包——桩不进插件 jar。
// T-McpToolset2：test 编译同样需要桩（工具级门控测试直接实例化 Tool 类——其超类型
// McpToolset 来自桩源集）；依旧不进 runtime/打包。
// k2j 适配：主源集 compileJava（JavaCompile.classpath）+ compileTestJava；Kotlin 测试编译
// 仍走 compileTestKotlin.libraries（与 master 同形态）。
tasks.named<JavaCompile>("compileJava") {
    classpath += files(sourceSets.named("mcpStub").map { it.output })
}
tasks.named<JavaCompile>("compileTestJava") {
    classpath += files(sourceSets.named("mcpStub").map { it.output })
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    libraries.from(sourceSets.named("mcpStub").map { it.output })
}
tasks.named<Test>("test") {
    classpath += files(sourceSets.named("mcpStub").map { it.output })
}

// k2j 专属：测试树 .java 与 .kt 同目录混放（src/test/kotlin 下 92 件 .java），显式并入 Java
// 测试源集（SourceDirectorySet 默认只收 *.java，与 Kotlin 编译互不干扰）。master 无此块
//（其 src/test/java 只放非 Test 的检查器类，不参与源集编译语义差异）。
// 前史（2026-09-04 移植同步修复）：c100f1f 把 92 个 .kt 测试转成 .java 后仍放在 src/test/kotlin，
// 该目录默认只属于 Kotlin 测试源集，compileTestJava 恒为 NO-SOURCE——这批 Java 测试从此从未被
// 编译执行（历史 1421/1426 全绿仅统计 .kt 测试类）。
sourceSets {
    test {
        java.srcDir("src/test/kotlin")
    }
}

// Java 源文件使用 UTF-8 编码（与 Kotlin 一致）；Windows 默认 GBK 会导致中文注释/字符串编译失败
tasks.withType<JavaCompile>().configureEach {
    // k2j 专属：转换期既定编译参数（master 无）。
    // T-M36 转换：保留 Java 方法形参名，使 Kotlin 测试调用方可用具名参数语义核对签名。
    options.compilerArgs.add("-parameters")
    options.encoding = "UTF-8"
}

// M14.7：开发期热重载——runIde 沙盒中修改代码即时生效（要求插件支持动态加载）
// T-M23.10 内部构建号（用户确认保留，独立于已回退的检测功能）：jar manifest 注入
// MPV-Build（执行期毫秒时间戳，每次构建唯一）——对外版本号一日一版（同日多次构建相同），
// 构建号用于区分同日构建与问题定位；对所有 Jar 任务注入（jar + composedJar——
// buildPlugin 最终产物由 composedJar 生成，只配 jar 会导致 zip 内 jar 丢失该属性），
// 强制每次执行（刷新时间戳）。
tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doFirst {
        manifest.attributes["MPV-Build"] = System.currentTimeMillis().toString()
    }
    outputs.upToDateWhen { false }
}

// T-BuildScriptSplit：root 收口——tasks{} 块已全部分治（patchPluginXml/writeUpdateManifest →
// mpv-release；runide 四块 → mpv-runide；verify 三任务 → mpv-verification），此块撤销。

// T-ReleasePerf-02（P0-2）：releaseGate 的显式串行顺序——dependsOn 只保证「都执行」不保证先后，
// mustRunAfter 才钉死时序（buildPlugin → verifyGate → testShards）。放 root body（各 convention
// 应用之后执行）——verifyGate/testShards 分属 mpv-verification/mpv-testing 注册，此处天然晚于两者。
tasks.named<Task>("verifyGate") { mustRunAfter("buildPlugin") }
tasks.named<Task>("testShards") { mustRunAfter("verifyGate") }

// ---------- M56 T-M56-01：agent fat jar 内嵌主插件资源（resources/agent/mps-sql-agent.jar） ----------
// 运行时 AgentManager 解压落盘 + deleteOnExit 后经 attach loadAgent 挂载；
// 落盘失败显式上报（禁静默吞）。跨项目任务经路径引用，配置缓存兼容。
evaluationDependsOn(":sql-visualizer-agent")
val agentEmbedDir = layout.buildDirectory.dir("agent-embed")
val embedAgentJar = tasks.register<Copy>("embedAgentJar") {
    from(tasks.getByPath(":sql-visualizer-agent:agentFatJar"))
    into(agentEmbedDir.map { it.dir("agent") })
    rename { "mps-sql-agent.jar" }
}
sourceSets.named("main") {
    resources.srcDir(agentEmbedDir)
}
tasks.named("processResources") {
    dependsOn(embedAgentJar)
}
