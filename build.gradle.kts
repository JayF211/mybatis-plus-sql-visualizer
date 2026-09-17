import java.time.LocalDate
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    // T-BuildScriptSplit：构建逻辑分治——convention 插件见 buildSrc/src/main/kotlin/，
    // 块级逐字搬运自本文件（锚点 dc047bd，注释随迁），来源与去向见计划 §2.1。
    id("mpv-verification")
    id("mpv-testing")
    id("mpv-runide")
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

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
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
    }
    // T-ListParamCollect：并行会话隔离（临时——用后还原本行）
    sandboxContainer = layout.buildDirectory.dir("test-sandbox-listparam")
}

kotlin {
    // T-GifRecordSwitch：常规态 243 平台用 17；录制态 262 平台类文件为 v69，
    // 须 javac 24 才能读（javac 17/21 报「错误的版本 69.0」）。
    jvmToolchain(if (gifRecord) 24 else 17)
}

// Java 源文件使用 UTF-8 编码（与 Kotlin 一致）；Windows 默认 GBK 会导致中文注释/字符串编译失败
tasks.withType<JavaCompile>().configureEach {
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

tasks {

    // 本地清单测试集成见 dev-support/run-ide-local-manifest.sh（自动起/停 Python 服务器）

    // M18.2 变更说明修复（用户实测：插件详情页「最新变化」显示停留在 M12）——
    // 根因：patchPluginXml 未显式配置时，org.jetbrains.changelog 自动从 CHANGELOG.md [Unreleased]
    // 注入 change-notes；本 CHANGELOG 存在多个同名 ### Added 分区，解析器取到最后一个分区
    // （M12/M10），且自动注入会覆盖 plugin.xml 中手写的 change-notes。
    // 修复：显式配置 changeNotes（单一来源，与发布内容一致；更新时同步修改此处）。
    // 结构：每个发行版本一个 <h2>v{版本号}</h2> 标题块（版本号在详情页直接可见），
    // 保留全部版本标题，按时间倒序排列（最新在前）。
    // 内测系列：对外只保留**最新一个** -beta.N 标题块，后一发 beta 替换前一发的块
    // （内容为全系列累计）；转正时改为纯数字标题并将累计内容并入正式版块。
    patchPluginXml {
        // T-BuildScriptSplit：changeNotes 外置 gradle/plugin-change-notes.html（发版只编辑该文件）。
        // fileContents 是执行期懒读（不落配置缓存，无陈旧问题）；但 asText 按 JVM 平台默认字符集
        // 解码——本机 GBK daemon 实测把 UTF-8 读成乱码（「集合」→「闆嗗悎」），必须 asBytes +
        // UTF-8 显式解码。结构约定不变（原 M18.2 注释）：每个发行版本一个 <h2>v{版本号}</h2>
        // 标题块（版本号在详情页直接可见），保留全部版本标题按时间倒序；内测系列对外只保留
        // 最新一个 -beta.N 标题块（内容为全系列累计），转正时改为纯数字标题并入累计内容。
        changeNotes.set(
            providers.fileContents(layout.projectDirectory.file("gradle/plugin-change-notes.html"))
                .asBytes.map { String(it, Charsets.UTF_8).trim() },
        )
    }

    // M28（内测通道改造）：生成更新清单 update/update.json（Gitee 抢先体验通道数据源）——
    // 版本号/下载地址/发布日期自动生成，changeNotes 生成后**人工补充**本次发布说明（纯文本，
    // \n 换行）。发布流程：改 gradle.properties 版本号（内测加 -beta.N 后缀）→ buildPlugin 并
    // 上传 Gitee Release 附件 → 执行本任务 → 补 changeNotes → 提交推送（清单 URL 固定，
    // 见 update/UpdateSource.kt）。稳定版由 JetBrains Marketplace 推送，本通道不参与。
    // 草稿请写 update/preview.update.json（不参与检查），发布时复制到 update/update.json。
    register("writeUpdateManifest") {
        group = "publishing"
        description = "生成更新清单 update/update.json（Gitee 抢先体验通道数据源，版本号带 -beta.N 后缀）"
        // 配置期捕获（configuration cache 兼容：doLast 不触达 project/providers）
        val version = providers.gradleProperty("version").get()
        val outputFile = layout.projectDirectory.file("update/update.json").asFile
        doLast {
            val manifest = buildString {
                appendLine("{")
                appendLine("  \"version\": \"$version\",")
                appendLine("  \"downloadUrl\": \"https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/releases/download/$version/mybatis-plus-sql-visualizer-$version.zip\",")
                appendLine("  \"changeNotes\": \"（手动补充本次发布变更说明，纯文本，\\\\n 换行）\",")
                appendLine("  \"publishDate\": \"${LocalDate.now()}\",")
                appendLine("  \"sinceBuild\": \"243\"")
                appendLine("}")
            }
            outputFile.parentFile.mkdirs()
            outputFile.writeText(manifest)
        }
    }
}

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
