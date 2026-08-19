import java.time.LocalDate
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

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)
    // T-M23.13：真实 MP jar 进测试 classpath（Heavy 语义黄金用例——方法签名/注解元数据可解析；
    // 仅测试依赖，插件运行时零依赖不变；MPJ 1.5.x 不在 Central，暂不加）
    testImplementation(libs.mp.core)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // M17：构建平台 = **最低支持版本 Ultimate 2024.3.7.1**（T-M30：最低兼容降至 IDEA 243/2024.3）。
        // 官方兼容策略：以最低版本为编译基准，公共 API 向后兼容保证 2025.x/2026.x 可运行；
        // Plugin Verifier 按目标 IDE 集合校验。database API 在 com.intellij.database（仅 Ultimate）。
        // Kotlin 规则（T-M30）：多版本支持须以最低捆绑 stdlib 为编译目标——2024.3 捆绑 stdlib 2.0.21，
        // 平台 distro 进编译 classpath 后自动满足（kotlin.stdlib.default.dependency=false 不打包 stdlib）。
        // 注：本机日常 IDE 为 Ultimate 2026.2.1，243 平台需下载（gradle.properties 已配代理）。
        intellijIdeaUltimate("2024.3.7.1")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        // T-M24-04：XML 平台模块（XML PSI 定位语句 + XML gutter；平台 bundled，IDE 恒启用）。
        // T-M30：构建基准降至 2024.3 后 XML PSI 类在平台核心（app.jar，lib/modules 无独立模块），
        // bundledModule("intellij.xml.psi") 在 2024.3 distro 中不存在（253+ 才拆分为该模块）——移除声明，
        // 编译期类来自平台核心，运行期 253+ 由平台 XML 模块提供（com.intellij.modules.xml 依赖已声明）。
        // bundledModule("intellij.xml.psi")
        // M17：Database 插件（数据源/控制台 API，仅 Ultimate 捆绑）。
        // 注：2026.2 中 SQL 语言支持（com.intellij.sql）已并入平台核心，无需/无法单独声明
        bundledPlugin("com.intellij.database")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// 纯 Kotlin 插件：无需 @NotNull 字节码插桩（Kotlin 编译器已处理空安全）。
// 禁用后可绕开 JDK 环境下 instrumentCode 的 jrt 模块解析问题（Windows 中文环境实测报
// "java.home/Packages does not exist"）。若后续引入 Java 源码或 GUI Forms 再评估开启。
intellijPlatform {
    instrumentCode = false
    // Windows 中文环境实测 buildSearchableOptions 报 "Locale must be default"（IDEA
    // 搜索索引器要求 JVM locale 为默认值）。禁用后 Settings 页面不进入 IDE 设置搜索
    // 索引（其余功能不受影响）；在非中文 locale 环境可改回 true 以获得设置搜索支持。
    buildSearchableOptions = false

    // T-M30：跨版本 API 兼容校验（官方推荐流程）——`./gradlew verifyPlugin` 对目标 IDE
    // 集合做字节码级检查（插件引用的类/方法在目标版本中必须存在）。目标 = 最低支持
    // 版本 + 中段版本 + 最新版本；首次运行下载各发行版（体积大，gradle.properties 已配代理）。
    // T-M31.1/2 提速：验证耗时与版本数线性（243≈1min / 253≈5.7min / 262≈7.5min，字节码分析不可缓存）——
    // 三态 `-PverifyIdes`：默认全量三版本（发布/里程碑）；minimal 边界 243+262（253 被两头覆盖）；
    // smoke 只 243 构建基准（约 1.5min，日常每次开发后兼容冒烟；高版本 API 移除风险由发布前全量兜底）。
    pluginVerification {
        ides {
            val verifyIdes = providers.gradleProperty("verifyIdes").orNull
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
    jvmToolchain(17)
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
tasks.withType<Test>().configureEach {
    classpath = classpath.filter {
        !it.name.contains("vue", ignoreCase = true) &&
            !(it.name.contains("database", ignoreCase = true) && it.name.startsWith("intellij.")) &&
            it.name != "database-plugin.jar" &&
            !(it.name.contains("intellij.sql", ignoreCase = true))
    }
}

// M14.7：开发期热重载——runIde 沙盒中修改代码即时生效（要求插件支持动态加载）
// T-M23.10 内部构建号（用户确认保留，独立于已回退的检测功能）：jar manifest 注入
// MPV-Build（执行期毫秒时间戳，每次构建唯一）——对外版本号一日一版（同日多次构建相同），
// 构建号用于区分同日构建与问题定位；对所有 Jar 任务注入（jar + composedJar——
// buildPlugin 最终产物由 composedJar 生成，只配 jar 会导致 zip 内 jar 丢失该属性），
// 强制每次执行（刷新时间戳）。
tasks.withType<Jar>().configureEach {
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
    }

    // M18.2 变更说明修复（用户实测：插件详情页「最新变化」显示停留在 M12）——
    // 根因：patchPluginXml 未显式配置时，org.jetbrains.changelog 自动从 CHANGELOG.md [Unreleased]
    // 注入 change-notes；本 CHANGELOG 存在多个同名 ### Added 分区，解析器取到最后一个分区
    // （M12/M10），且自动注入会覆盖 plugin.xml 中手写的 change-notes。
    // 修复：显式配置 changeNotes（单一来源，与发布内容一致；更新时同步修改此处）。
    // 结构：每个发行版本一个 <h2>v{版本号}</h2> 标题块（版本号在详情页直接可见），
    // 保留最近两个版本，更早历史见 CHANGELOG.md。
    patchPluginXml {
        changeNotes = """
            <h2>v2026.8.19</h2>
            <ul>
                <li>废弃API全量清理（T-M51 + T-M52）：三版本 0 Internal + 0 Scheduled-removal + Deprecated 9/11/27→3/4/20</li>
                <li>IService 内置 CRUD 方法预览支持无 Entity 后缀实体类</li>
                <li>PluginManager.getPlugin() 完全消除（ProtectionDomain.codeSource.location + DeleteCommand 替代）</li>
                <li>ProjectListenerRegistrar 删除（projectOpened() 无公开替代）</li>
                <li>ToolWindowFactory Kotlin→Java 改写消除 synthetic bridge</li>
                <li>功能截图 5 张替换真实截图 + Marketplace Custom Page + plugin.xml 清理内嵌图</li>
            </ul>
            <h2>v2026.8.18</h2>
            <ul>
                <li>监听启动时机修正（打开项目不监听，启动运行才监听；停止运行自动停止）</li>
                <li>关闭工具窗口时停止监听设置生效（监听 ToolWindow 可见性变化）</li>
                <li>SQL 日志列表改为正序（最早在前）+ 工具栏捕捉按钮状态实时刷新</li>
                <li>项目停止后监听不关闭修复（进程存活检查 + 移除 processTerminated 误触发）</li>
                <li>第一条 SQL 丢失修复 + 手动停止后重启不再自动开启</li>
                <li>SQL→Java 联表反向转换修复（反引号列/换行转义/三参联表/selectAs/副表列归属）</li>
                <li>invocationHasMpj 接口继承 + 泛型超类型误判修复</li>
                <li>selectAs 字符串联接 + VO-only + VO 属性方法引用修复（T-M48~M50）</li>
                <li>实体选择弹窗唯一推断时跳过（T-M49）+ notificationGroup schema 报错修复</li>
                <li>插件描述与最新变化优化 + README 去 AI 味重写</li>
            </ul>
            <h2>v2026.8.17</h2>
            <ul>
                <li>SQL→Java 生成代码三改进（T-M45：复制纯代码/格式化代码按钮/行尾注释）</li>
                <li>调用方法按调用类真实 API 选择（T-M44）</li>
                <li>UPSERT 按方言渲染与反向分页宽容（T-M43）</li>
                <li>日志五项修复（T-M42：弹窗自动识别参数/Ctrl+C 复制/EDT 报错/this·baseMapper/serialVersionUID 忽略）</li>
                <li>三处图标统一优化 + 功能截图 5 张入库（T-M40）</li>
                <li>SQL→Java 右键预览高亮 + 快捷键可配置（T-M41）</li>
                <li>SQL→Java 调用形态按 MPJ 1.5.6 实证重写 + 性能修复</li>
                <li>手动选择实体类 + AUTO 形态 @TableName 反查 + ? 占位符反向识别</li>
            </ul>
        """.trimIndent()
    }

    // M28：生成更新清单 update/update.json（插件设置页「检查更新」的数据源；发布时执行）——
    // 版本号/下载地址/发布日期自动生成，changeNotes 生成后**人工补充**本次发布说明（纯文本，
    // \n 换行）。发布流程：改 gradle.properties 版本号 → buildPlugin 并上传 Gitee Release 附件 →
    // 执行本任务 → 补 changeNotes → 提交推送（清单 URL 固定，见 update/UpdateSource.kt）。
    register("writeUpdateManifest") {
        group = "publishing"
        description = "生成更新清单 update/update.json（M28 检查更新数据源）"
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

// 并行分片测试（2026-08-15 提速）：782 个测试按方法数均衡分 4 片（~195/片）。
// 用法：./gradlew testShards（4 片并行）或单跑某片 ./gradlew testShard1；权威串行兜底仍是 ./gradlew test。
// 前提：每片独立 sandbox 目录——多 worker 共享 .intellijPlatform/sandbox/.../system-test 会
// PersistentFS 互踩损坏（实证：191 失败 + 卡死），分片隔离后无共享写点。
// 新增测试类时加入最小编号片以保持均衡。
intellijPlatformTesting {
    testIde {
        register("testShard1") {
            testFramework(TestFrameworkType.Platform)
            sandboxDirectory.set(layout.buildDirectory.dir("test-sandbox/shard1"))
            task {
                // IPGP 只给默认 test 任务自动配置 sourceSets 输出，分片任务需补齐
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = classpath + sourceSets["test"].output
                filter {
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.SqlPreviewServiceTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.xml.AnnotationSqlParserTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.AnnotationPreviewServiceTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.config.UpdateSectionTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.XmlPreviewServiceTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.xml.XmlEwExpanderTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.converter.EdgeCaseTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProviderTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.SqlPreviewDialogTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.heavy.UpdateFlowHeavyTest")

                    includeTestsMatching("cn.song.mybatisplus.visualizer.converter.QueryWrapperParserTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetectorTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.xml.OgnlEvaluatorTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.sql.SqlTextAnalysisTest")
                    // P0 SQL 反向转换（T-M36）：解析器单测（~30 用例，最大片）
                    includeTestsMatching("cn.song.mybatisplus.visualizer.reverse.SqlReverseParserTest")
                    // T-M36.2 实体查找 Heavy 用例（与 HeavyAnchorSemanticsTest 同片，分散重类）
                    includeTestsMatching("cn.song.mybatisplus.visualizer.heavy.EntityClassFinderHeavyTest")
                    // T-M31 日志还原：解析/还原纯逻辑单测
                    includeTestsMatching("cn.song.mybatisplus.visualizer.log.*")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.update.PluginUpdateRunnerTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.xml.ForeachSqlNodeTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.psi.EntityResolverTableConfigTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.converter.MpjJoinExtConverterTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributorTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatterTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolverTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.update.UpdateManifestTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.util.PluginBuildInfoTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.update.PluginVersionTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.SqlPreviewDialogSizeTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.heavy.HeavyAnchorSemanticsTest")
                }
            }
        }
        register("testShard2") {
            testFramework(TestFrameworkType.Platform)
            sandboxDirectory.set(layout.buildDirectory.dir("test-sandbox/shard2"))
            task {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = classpath + sourceSets["test"].output
                filter {
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelTest")
                    // T-M36.4 SQL→Java 面板 fixture 测试
                    includeTestsMatching("cn.song.mybatisplus.visualizer.log.SqlToJavaPanelTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.psi.WrapperLocatorTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.psi.EntityResolverCommentTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.xml.TrimSqlNodeTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.sql.DialectTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.XmlPreviewPanelTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.DbActiveRecordCoverageTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.converter.UpdateJoinDeleteJoinConverterTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.highlight.SqlLexerTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.xml.XmlScriptBuilderTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.config.PluginDistributionSelfCheckTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.gutter.SqlGutterLineMarkerProviderTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.sql.SqlKeywordsTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.XmlEwResolverTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupportTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.heavy.HeavySqlCompletionContextResolverTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.config.PluginSettingsPanelBuildInfoTest")

                    includeTestsMatching("cn.song.mybatisplus.visualizer.converter.UpdateDeleteMpjConverterTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.ConsumerMethodsCoverageTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.xml.XmlSqlRendererTest")
                    // P0 SQL 反向转换（T-M36）：代码生成单测 + round-trip 闭环；T-M36.3 Wrapper 形态分发
                    includeTestsMatching("cn.song.mybatisplus.visualizer.reverse.JavaCodeGeneratorTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.reverse.SqlToJavaRoundTripTest")
                    // T-M36.4 共享编排纯逻辑测试
                    includeTestsMatching("cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverterTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.reverse.WrapperFormatTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.sql.SqlGeneratorTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.highlight.AnnotationSqlCompletionContributorTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.highlight.XmlSqlCompletionContributorTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.model.BatchModelTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.config.PluginSettingsPersistenceTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.xml.XmlPreviewModelTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.xml.XmlIncludeExpanderTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.update.VersionComparatorTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.sql.ParameterReplacerTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.psi.EntityResolverColumnOrderTest")
                    // T-M42：serialVersionUID 忽略（实体解析源头过滤）
                    includeTestsMatching("cn.song.mybatisplus.visualizer.psi.EntityResolverSerialVersionUidTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.update.StartupUpdateCheckTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.ui.gutter.AnnotationSqlGutterLineMarkerProviderTest")
                    includeTestsMatching("cn.song.mybatisplus.visualizer.util.NamingStrategyTest")
                }
            }
        }
    }
}

tasks.register("testShards") {
    group = "verification"
    description = "并行分片全量测试（4 片同时执行，各片独立沙盒）"
    dependsOn("testShard1", "testShard2")
}
