import java.time.LocalDate
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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

kotlin {
    jvmToolchain(17)
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
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
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
            <h2>v2026.8.15</h2>
            <ul>
                <li><b>侧边栏帮助入口</b>（T-M39）：工具窗口工具栏「?」按钮打开帮助弹窗——核心功能/使用方法/默认快捷键一览（i18n 双语、主题化渲染）；「日志转 SQL」tab 改名「日志 → SQL」</li>
                <li><b>日志转 SQL Tab</b>（T-M38）：侧边栏工具窗口「SQL 预览」改造为「日志转 SQL」——粘贴 MyBatis 日志（Preparing/Parameters 行）批量还原可执行 SQL（多组语句分隔/默认多行格式化 + 单行切换/参数不足警告/一键复制）；日志列表右键一键发送转换</li>
                <li><b>MyBatis XML mapper 动态 SQL 预览</b>（M24~M26）：mapper XML / 注解 SQL（@Select 等含 &lt;script&gt;）gutter 图标 + 右键预览——复刻 MyBatis 3.5.19 渲染算法（if/choose/trim/where/set/foreach/bind/sql+include 全兼容、受限 OGNL 求值、动态条件勾选/foreach 次数/bind 覆盖/参数回填）；${'$'}{ew.customSqlSegment} 等 MP 占位按调用处 Wrapper 全链路展开；文件级多注解候选选择</li>
                <li><b>XML/注解 SQL 代码补全</b>（M27）：mapper XML 与 Java SQL 注解字符串内 Ctrl+Space——SQL 关键字、表名/列名位置感知、别名前缀列、#{} 参数名、XML 动态标签与 OGNL；预览面板输入自动弹补全（含 @TableName 联表候选）</li>
                <li><b>设置页检查更新与自动安装</b>（M28）：设置页「更新」分区——检查更新/变更日志展示/下载并安装一键完成 + 重启提示；「启动时自动检查更新」开关（默认开）发现新版弹通知后台安装；构建号一键复制</li>
                <li><b>预览 SQL 修复</b>（M29）：分组布尔条件重载不再静默丢弃（and/or/nested/not/func 开关化）、分页 LIMIT 渲染（9 方言）、格式化逗号/空格修复（幂等）、动态条件面板组行合并、XML 参数回填默认引号</li>
            </ul>
            <h2>v2026.8.14</h2>
            <ul>
                <li><b>官方 API 覆盖差距清零</b>（MP 3.5.17 / MPJ 1.5.9）：实体单条 CRUD（save/getById/listByIds/deleteByIds）、JoinWrappers/MPJWrappers/ChainWrappers 工厂、Map 条件消费、UpdateJoinWrapper/DeleteJoinWrapper 联表更新删除全链路、allEq/eqOrIsNull/selectFunc 枚举/union/selectSub 等全量 API 补全</li>
                <li><b>批量操作增强</b>（用户实测驱动）：@TableLogic 批量软删除对齐、批量参数面板（列级勾选/全选全不选/saveOrUpdate 主键可选）、字段顺序角色分组</li>
                <li><b>多数据库方言</b>：新增 SQL Server/H2/SQLite/MariaDB/DM 达梦/KingBase 人大金仓（共 9 方言）；@Version 乐观锁注入；Db/SimpleQuery/ActiveRecord 识别</li>
                <li><b>SQL 关键字方言化引用</b>：table 等保留字按方言自动加引号（反引号/双引号/方括号）</li>
                <li><b>多 SQL 选择</b>：选择代码块弹候选框（代码行预览 + 定位跳转）、任意位置触发预览、复制/执行提示一键关闭 SQL 面板</li>
                <li>弹窗关闭按钮文案随插件语言；</li>
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
