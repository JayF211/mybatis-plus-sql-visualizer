import java.time.LocalDate
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
        // M17：构建平台 = **最低支持版本 Ultimate 2025.3.5**（用户强制要求兼容 IDEA 2025.3+）。
        // 官方兼容策略：以最低版本为编译基准，公共 API 向后兼容保证 2026.x 可运行；
        // Plugin Verifier 按目标 IDE 集合校验。database API 在 com.intellij.database（仅 Ultimate）。
        // 注：本机安装为 Ultimate 2026.2.1，2025.3 平台需下载（gradle.properties 已配代理）。
        intellijIdeaUltimate("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        // T-M24-04：XML 平台模块（XML PSI 定位语句 + XML gutter；平台 bundled，IDE 恒启用）
        bundledModule("intellij.xml.psi")
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
    runIde {
        autoReload = true
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
            <h2>v2026.8.14</h2>
            <ul>
                <li><b>官方 API 覆盖差距清零</b>（MP 3.5.17 / MPJ 1.5.9）：实体单条 CRUD（save/getById/listByIds/deleteByIds）、JoinWrappers/MPJWrappers/ChainWrappers 工厂、Map 条件消费、UpdateJoinWrapper/DeleteJoinWrapper 联表更新删除全链路、allEq/eqOrIsNull/selectFunc 枚举/union/selectSub 等全量 API 补全</li>
                <li><b>批量操作增强</b>（用户实测驱动）：@TableLogic 批量软删除对齐、批量参数面板（列级勾选/全选全不选/saveOrUpdate 主键可选）、字段顺序角色分组</li>
                <li><b>多数据库方言</b>：新增 SQL Server/H2/SQLite/MariaDB/DM 达梦/KingBase 人大金仓（共 9 方言）；@Version 乐观锁注入；Db/SimpleQuery/ActiveRecord 识别</li>
                <li><b>SQL 关键字方言化引用</b>：table 等保留字按方言自动加引号（反引号/双引号/方括号）</li>
                <li><b>多 SQL 选择</b>：选择代码块弹候选框（代码行预览 + 定位跳转）、任意位置触发预览、复制/执行提示一键关闭 SQL 面板</li>
                <li>弹窗关闭按钮文案随插件语言；打包编译警告清零；500 测试全绿</li>
            </ul>
            <h2>v2026.8.13（首发）</h2>
            <ul>
                <li>Wrapper 全覆盖（Lambda/字符串版 QueryWrapper·UpdateWrapper·DeleteWrapper、MPJ 联表 JOIN/selectCollection/selectFunc、服务链 lambdaQuery/lambdaUpdate）实时转可读 SQL；参数替换（? 回填、LIKE 拼百分号、列类型推断引号）；动态条件复选框；SQL 格式化；字段补全 + 注释提示（@Schema/@ApiModelProperty）；表名配置；逻辑删除注入；执行 SQL（Ultimate）；多方言分页；右键/快捷键/gutter 图标三入口；中英文界面</li>
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
                appendLine("  \"downloadUrl\": \"https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/releases/download/v$version/mybatis-plus-sql-visualizer-$version.zip\",")
                appendLine("  \"changeNotes\": \"（手动补充本次发布变更说明，纯文本，\\\\n 换行）\",")
                appendLine("  \"publishDate\": \"${LocalDate.now()}\",")
                appendLine("  \"sinceBuild\": \"253\"")
                appendLine("}")
            }
            outputFile.parentFile.mkdirs()
            outputFile.writeText(manifest)
        }
    }
}
