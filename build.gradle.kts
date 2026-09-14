import java.time.LocalDate
import java.net.InetSocketAddress
import java.net.Socket
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
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
        // 注：本机日常 IDE 为 Ultimate 2026.2.1，243 平台需下载（gradle.properties 已配代理）。
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

    // T-M30：跨版本 API 兼容校验（官方推荐流程）——`./gradlew verifyPlugin` 对目标 IDE
    // 集合做字节码级检查（插件引用的类/方法在目标版本中必须存在）。目标 = 最低支持
    // 版本 + 中段版本 + 最新版本；首次运行下载各发行版（体积大，gradle.properties 已配代理）。
    // T-M31.1/2 提速：验证耗时与版本数线性（243≈1min / 253≈5.7min / 262≈7.5min，字节码分析不可缓存）——
    // 三态 `-PverifyIdes`：默认全量三版本（发布/里程碑）；minimal 边界 243+262（253 被两头覆盖）；
    // smoke 只 243 构建基准（约 1.5min，日常每次开发后兼容冒烟；高版本 API 移除风险由发布前全量兜底）。
    pluginVerification {
        ides {
            // T-VerifyPlugin：从任务名推断验证模式（./gradlew verifySmoke / verifyMinimal 自动选择对应 IDE 集合）
            val verifyIdes = when {
                gradle.startParameter.taskNames.contains("verifySmoke") -> "smoke"
                gradle.startParameter.taskNames.contains("verifyMinimal") -> "minimal"
                else -> providers.gradleProperty("verifyIdes").orNull
            }
            when (verifyIdes) {
                "smoke" -> create(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3.7.1")
                "minimal" -> {
                    create(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3.7.1")
                    create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.1")
                }
                else -> {
                    create(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3.7.1")
                    create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3.5")
                    create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.1")
                }
            }
        }
        // T-M30.1：失败等级仅保留兼容性问题（IPGP 2.15+ 默认含 INTERNAL_API_USAGES 会失败）——
        // `PluginManagerCore.getPlugin`（M28 更新功能定位已装插件）为平台 Internal API，
        // 无公共替代（官方文档即此用法），刻意使用；报告仍生成、问题仍可见。
        failureLevel = listOf(FailureLevel.COMPATIBILITY_PROBLEMS)
    }
}

// Plugin Verifier 是独立子进程，systemProp 代理不自动透传——显式注入（Clash 7890）
tasks.named<VerifyPluginTask>("verifyPlugin") {
    jvmArgs(
        "-Dhttp.proxyHost=127.0.0.1",
        "-Dhttp.proxyPort=7890",
        "-Dhttps.proxyHost=127.0.0.1",
        "-Dhttps.proxyPort=7890"
    )
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
    // T-M31.9：消除 runIde 启动日志的 RegionUrlMapper SecurityException: setContextClassLoader 噪音。
    // 根因：发行版 vmoptions 的 -Djava.security.manager=CoreBootstrapSecurityManager 使 JDK 21 common
    // pool 在启动早期（SM 卸载前）创建 InnocuousForkJoinWorkerThread（setContextClassLoader 硬抛）。
    // 修复：独立 Java 源编译普通线程工厂 jar（不进入插件产物），追加 runIde 启动 classpath，
    // 经 java.util.concurrent.ForkJoinPool.common.threadFactory 属性生效（JDK 21 common pool 构造
    // 用 getSystemClassLoader 加载该类，无视 SM 状态）。注意：不可改为 -Djava.security.manager=allow
    // ——SM 是 FileSystems 在 initLevel 2 预初始化的触发者（PathClassLoader 构造依赖它），实测会崩。
    val compilePlainFjpf = register<JavaCompile>("compilePlainFjpf") {
        source = fileTree("dev-support/plain-fjpf/src")
        classpath = files()
        destinationDirectory.set(layout.buildDirectory.dir("plain-fjpf/classes"))
        options.release.set(17)
        options.encoding = "UTF-8"
    }
    val plainFjpfJar = register<Jar>("plainFjpfJar") {
        from(compilePlainFjpf.map { it.destinationDirectory })
        destinationDirectory.set(layout.buildDirectory.dir("plain-fjpf"))
        archiveFileName.set("plain-fjpf.jar")
    }
    // 热替换本地服务清理（runIdeHotSwap 的 finalizedBy——IDE 关闭/崩溃/任务失败都执行；
    // 只杀 pid 文件记录的自有进程，用户手动起的服务不接管、不误杀）。也可手动单独执行。
    register("hotswapServerStop") {
        // 配置缓存约束：任务动作内不得引用脚本对象——pid 路径在配置期解析为 File
        val pidFile = layout.buildDirectory.file("hotswap-server.pid").get().asFile
        doLast {
            val pid = pidFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
            if (pid.isNotEmpty()) {
                val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                val cmd = if (isWindows) listOf("taskkill", "/F", "/T", "/PID", pid) else listOf("kill", pid)
                runCatching { ProcessBuilder(cmd).start().waitFor() }
                    .onFailure { logger.warn("hotswap server stop failed: ${it.message}") }
                logger.lifecycle("hotswap local server (pid=$pid) stopped")
            }
            runCatching { pidFile.delete() }
        }
    }
    runIde {
        autoReload = true
        dependsOn(plainFjpfJar)
        classpath += files(layout.buildDirectory.file("plain-fjpf/plain-fjpf.jar"))
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "-Djava.util.concurrent.ForkJoinPool.common.threadFactory=" +
                    "cn.song.mybatisplus.visualizer.support.PlainForkJoinWorkerThreadFactory"
            )
        })
        // T-DynUpdate 职责分离（用户定案）：hotswap 本地服务起停只挂 runIdeHotSwap
        //（Run IDE (Local Manifest) 链路专属）——本任务（Run IDE with Plugin）是纯开发沙盒，
        // 不依赖更新清单服务，曾因无条件起服务 + build/distributions 缺 zip（clean 后）导致
        // 启动即挂（hotswap server exited during startup）。旧 .run 同名配置已删除，无误触入口。
        // -Dmpvs.update.manifest.url 转发保留：显式传参时仍可用（属性缺席为空，无副作用）。
        // 清单 URL 转发必须在执行期读——配置期 System.getProperty 被配置缓存冻结
        //（runIdeHotSwap 同款修复，见该任务注释）
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            System.getProperty("mpvs.update.manifest.url")
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf("-Dmpvs.update.manifest.url=$it") }
                ?: emptyList<String>()
        })
    }

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
        changeNotes = """
            <h2>v2026.9.14</h2>
            <ul>
                <li>SQL 列表 CRUD 类型颜色标记</li>
                <li>gutter 左键直接预览与最近执行反查</li>
                <li>真实 EXPLAIN 执行计划</li>
                <li>错误与反馈一键上报官网需求池</li>
                <li>SQL 列表三种显示模式</li>
                <li>更多 ORM 预览支持</li>
                <li>大日志列表性能优化</li>
                <li>实体类与数据库表右键生成 CRUD SQL</li>
                <li>SQL 预览字段列表自动折叠</li>
                <li>全选全不选双按钮合一为状态切换按钮</li>
                <li>SQL 列表多选批量复制</li>
                <li>多模块注入勾选选择</li>
                <li>SQL 转 Java 选类重渲染异常逃逸修复</li>
                <li>MPJ 括号条件反向转换退化修复</li>
                <li>262 预览控制台静默挂载失败修复</li>
            </ul>
            <h2>v2026.9.11</h2>
            <ul>
                <li>自更新免重启热替换</li>
                <li>SQL 历史页参数识别丢失根治</li>
                <li>无占位符 SQL 字面量参数恢复</li>
                <li>262 平台热替换探测失败回退修复</li>
                <li>更新通知入口去重与卸载警告缓解</li>
            </ul>
            <h2>v2026.9.10</h2>
            <ul>
                <li>双击日志预览弹窗加载卡顿根治</li>
                <li>预览打开 EDT 读权限断言刷屏全库排雷</li>
                <li>Hibernate 双份日志参数分裂根治</li>
                <li>竖向排列 VALUES 行尾列名标注配置项 + IN 列表展开阈值</li>
                <li>预览顶部元信息压按钮行修复</li>
                <li>执行 SQL 首次静默不执行修复 + 预览显示模式持久化</li>
                <li>更新检查版本比较改用 IDEA 内置比较器</li>
                <li>SQL 日志列表行号两处续修</li>
            </ul>
            <h2>v2026.9.9</h2>
            <ul>
                <li>SQL 日志列表增加行号：会话内捕获序号，项目启动清零</li>
                <li>预览刷新后内嵌 Database 丢失根治：多弹窗僵尸监听抢共享控制台</li>
                <li>执行临时文件失败可见化 + 复制错误信息按钮对号反馈</li>
                <li>预览点击侧边栏闪烁根治：日志重放守卫 + EDT 慢操作消解</li>
                <li>三处弃用 API 换现代等价物：三平台兼容性报告再收敛</li>
                <li>单击预览四轮优化：编辑器高亮与列表同色 + 可编辑焦点修复</li>
                <li>单击预览复制按钮点击换对号反馈</li>
                <li>双击预览无数据源时不再抢占侧边栏</li>
                <li>2026.2 平台兼容性修复：插件市场发布受阻解除</li>
                <li>发现 SQL 自动打开日志窗口假开关复活</li>
                <li>dev 已修转换语义/定位误报/导入导出反馈批量移植</li>
                <li>INSERT 租户列子开关接线 + 关闭工具窗停止监听开关转正</li>
                <li>启动项目清除自动展开会话抑制</li>
            </ul>
            <h2>v2026.9.8</h2>
            <ul>
                <li>SQL 日志列表 EDT 冻结 16 秒根治</li>
                <li>SQL 列表悬停卡顿改单击预览：SQL 高亮可编辑编辑器，临时改字段值再复制</li>
                <li>多行 JdbcTemplate 日志漏捕获修复</li>
                <li>SQL 日志去重失效 + MP 分页计数误标双根因修复</li>
                <li>SQL 日志双击预览平台断言硬抛崩溃修复</li>
                <li>自研 DAO 字符串 SQL 预览 + 方法定义处锚点贯通</li>
                <li>「项目启动时自动开始监听」联动失效与手动停止后永不自动捕捉修复</li>
                <li>设置页缓存信息恒 0 B 修复</li>
            </ul>
            <h2>v2026.9.4</h2>
            <ul>
                <li>JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串源码预览</li>
                <li>JOIN 图换用 IDEA 原生图框架</li>
                <li>JOIN 图渲染对齐 Database 原生显示图</li>
                <li>JOIN 图 B 路径：直接内嵌 Database 原生显示图 + 虚拟外键 JOIN 线</li>
                <li>非 Wrapper 预览快捷键贯通 + @NamedQuery 实体级预览</li>
                <li>SQL 日志列表 EDT 冻结修复 + 自动保存历史 SQL 无限递归修复</li>
                <li>SqlFormatter 关键字分派修复 + SQL 预览关键字选择粘连修复</li>
            </ul>
            <h2>v2026.9.2</h2>
            <ul>
                <li>SQL 预览面板原生 Database 控制台内嵌</li>
                <li>SQL 预览原生语法高亮 + 智能补全</li>
                <li>JPA/Hibernate：HQL 关联字段解析 + Hibernate XML 映射支持 + maintainPage XML 预览</li>
                <li>SQL 日志合并主侧边栏 + 精确跳转源码 + 自动保存历史 + 两级导航</li>
                <li>JOIN 关系图自绘 Swing 引擎</li>
                <li>Spring JdbcTemplate SQL 日志捕获</li>
                <li>设置页左侧树导航 + 折叠面板 + 搜索过滤</li>
            </ul>
            <h2>v2026.8.21</h2>
            <ul>
                <li>JPA/Hibernate SQL 预览支持</li>
                <li>Hibernate/JPA SQL 日志捕获支持</li>
                <li>SQL→Java 反向子查询 JOIN 解析 + MConsumer 形态</li>
                <li>JPA 分页修复（Pageable → LIMIT）+ @Query 路由优先</li>
                <li>JPQL IN 子句三层修复 + 复制时剥离注释</li>
            </ul>
            <h2>v2026.8.20</h2>
            <ul>
                <li>参数导入交互体验升级</li>
                <li>内存泄漏修复（面板 Disposer 树注册）</li>
                <li>SQL 监听手动启动无需等待项目运行 + 解析兼容前缀与 SQL 分行形态</li>
                <li>插件自更新静默失败修复</li>
                <li>SQL→Java 调用类校验+ 当前打开类智能识别</li>
                <li>MPJ 工厂链 selectAll 双图标修复</li>
                <li>IN 子句参数多值回填 + 关键字换行格式化</li>
                <li>ReadAction.compute 废弃 API 修复</li>
            </ul>
            <h2>v2026.8.19</h2>
            <ul>
                <li>废弃API全量清理 + 插件更新机制修复</li>
                <li>IService 内置 CRUD 方法预览支持无 Entity 后缀实体类</li>
                <li>功能截图替换 + Marketplace Custom Page</li>
            </ul>
            <h2>v2026.8.18</h2>
            <ul>
                <li>监听启动时机修正</li>
                <li>关闭工具窗口时停止监听设置生效</li>
                <li>SQL 日志列表改为正序 + 工具栏捕捉按钮状态实时刷新</li>
                <li>项目停止后监听不关闭修复</li>
                <li>第一条 SQL 丢失修复 + 手动停止后重启不再自动开启</li>
                <li>SQL→Java 联表反向转换修复</li>
                <li>invocationHasMpj 接口继承 + 泛型超类型误判修复</li>
                <li>selectAs 字符串联接 + VO-only + VO 属性方法引用修复</li>
                <li>实体选择弹窗唯一推断时跳过+ notificationGroup schema 报错修复</li>
            </ul>
            <h2>v2026.8.17</h2>
            <ul>
                <li>SQL→Java 生成代码三改进</li>
                <li>调用方法按调用类真实 API 选择</li>
                <li>UPSERT 按方言渲染与反向分页宽容</li>
                <li>日志五项修复</li>
                <li>三处图标统一优化 + 功能截图 5 张入库</li>
                <li>SQL→Java 右键预览高亮 + 快捷键可配置</li>
                <li>SQL→Java 调用形态按 MPJ 1.5.6 实证重写 + 性能修复</li>
                <li>手动选择实体类 + AUTO 形态 @TableName 反查 + ? 占位符反向识别</li>
            </ul>
            <h2>v2026.8.16</h2>
            <ul>
                <li>SQL 日志工具窗口 + SQL→Java 双向转换</li>
                <li>反向联表修复</li>
                <li>invocationHasMpj 接口继承 + 泛型超类型误判修复</li>
                <li>实体选择弹窗唯一推断时跳过 + notificationGroup schema 报错修复</li>
            </ul>
            <h2>v2026.8.15</h2>
            <ul>
                <li>XML + 注解动态 SQL 预览引擎</li>
                <li>更新链路全面兼容</li>
                <li>SQL→Java 反向 ? 占位符识别缺陷修复</li>
            </ul>
            <h2>v2026.8.14</h2>
            <ul>
                <li>官方 API 全覆盖 + 批量参数增强</li>
                <li>SQL→Java 反向转换增强 + 方言全兼容</li>
            </ul>
            <h2>v2026.8.13</h2>
            <ul>
                <li>首发版本：MP SQL 预览弹窗 + Gutter 图标 + 工具窗口</li>
                <li>基础正向渲染 + 历史参数召回</li>
            </ul>
        """.trimIndent()
    }

    // T-VerifyPlugin：封装 verifyPlugin 三态验证为独立 task（方便 ./gradlew verifySmoke / verifyFull 调用）
    register("verifySmoke") {
        group = "verification"
        description = "日常开发快速兼容冒烟（仅 243，约 1.5min，等同于 verifyPlugin -PverifyIdes=smoke）"
        dependsOn("verifyPlugin")
    }
    register("verifyFull") {
        group = "verification"
        description = "发布前全量验证（三版本 243+253+262，约 14min）"
        dependsOn("verifyPlugin")
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
    // T-DynUpdate 热替换本地验证沙盒（配套 dev-support/hotswap-local-server.py 与
    // .run/runIdeLocalManifest.run.xml）：与开发 runIde 沙盒隔离，验证自更新免重启全链
    //（本地清单 → 下载 → DynamicInstallSupport 卸载旧版+装载新版）。
    // autoReload 必须关闭——插件自动重载器监听 plugins 目录文件变化，会在热替换的
    //「解包中途」触发并发重载，与 EDT 卸载/装载序列竞态（卸载失败 → 测试假阴性）。
    // 沙盒插件是 jar-copy 安装（非 build/classes 直载），dev 守卫正常放行；
    // 验证后重置：删 .intellijPlatform/hotswap-sandbox 或重跑本任务（prepareSandbox 覆盖插件目录）。
    // 本地服务随任务生命周期自动起停：doFirst 起 hotswap-local-server.py（端口 8919 被占则
    // 复用不接管——pid 文件留空，清理任务即无害空转）；finalizedBy 清理保证 IDE 正常关闭、
    // 崩溃、任务失败三条出口都停服务，且只杀自己起的进程（按 pid 文件）。
    runIde {
        register("runIdeHotSwap") {
            task {
                autoReload.set(false)
                dependsOn(tasks.named("plainFjpfJar"))
                classpath += files(layout.buildDirectory.file("plain-fjpf/plain-fjpf.jar"))
                jvmArgumentProviders.add(CommandLineArgumentProvider {
                    listOf(
                        "-Djava.util.concurrent.ForkJoinPool.common.threadFactory=" +
                            "cn.song.mybatisplus.visualizer.support.PlainForkJoinWorkerThreadFactory"
                    )
                })
                // 清单 URL 转发必须在执行期读（jvmArgumentProviders 每次执行求值）——配置期
                // System.getProperty 会被配置缓存冻结：首次无 -D 的运行存下空配置，后续带
                // -D 的运行不再转发（实证：11:21 会话 URL 已转发系旧路径，CC 风险仍在）
                jvmArgumentProviders.add(CommandLineArgumentProvider {
                    System.getProperty("mpvs.update.manifest.url")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { listOf("-Dmpvs.update.manifest.url=$it") }
                        ?: emptyList<String>()
                })
                // 本地服务随任务生命周期自动起停：doFirst 起 hotswap-local-server.py（端口被占
                // 则复用不接管——pid 文件留空，清理任务即无害空转）；finalizedBy 清理保证 IDE
                // 正常关闭、崩溃、任务失败三条出口都停服务，且只杀自己起的进程（按 pid 文件）。
                // 配置缓存约束：路径在配置期解析为 File，doFirst 不引用脚本对象/布局访问器。
                val pidFile = layout.buildDirectory.file("hotswap-server.pid").get().asFile
                val logFile = layout.buildDirectory.file("hotswap-server.log").get().asFile
                val scriptFile = rootProject.file("dev-support/hotswap-local-server.py")
                doFirst {
                    // 端口须与 .run/runIdeLocalManifest.run.xml 清单 URL 及脚本默认值一致
                    val port = 8919
                    fun listening() = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("localhost", port), 500)
                            true
                        }
                    }.getOrDefault(false)
                    if (listening()) {
                        logger.lifecycle("hotswap local server already listening on $port — reusing (not owned, left running at exit)")
                        pidFile.writeText("")
                        return@doFirst
                    }
                    if (!scriptFile.isFile) throw GradleException("hotswap-local-server.py not found: $scriptFile")
                    val proc = ProcessBuilder("python", scriptFile.absolutePath)
                        .redirectOutput(logFile)
                        .redirectErrorStream(true)
                        .start()
                    pidFile.writeText(proc.pid().toString())
                    val deadline = System.currentTimeMillis() + 20_000
                    while (System.currentTimeMillis() < deadline) {
                        if (listening()) {
                            logger.lifecycle("hotswap local server started (pid=${proc.pid()}, log=$logFile)")
                            return@doFirst
                        }
                        if (!proc.isAlive) throw GradleException("hotswap server exited during startup — see $logFile")
                        Thread.sleep(300)
                    }
                    runCatching { proc.destroy() }
                    throw GradleException("hotswap server not listening on $port within 20s — see $logFile")
                }
                finalizedBy("hotswapServerStop")
            }
        }
    }
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

