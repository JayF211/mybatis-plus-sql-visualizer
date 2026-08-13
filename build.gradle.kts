import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

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
tasks {
    runIde {
        autoReload = true
    }

    // M18.2 变更说明修复（用户实测：插件详情页「最新变化」显示停留在 M12）——
    // 根因：patchPluginXml 未显式配置时，org.jetbrains.changelog 自动从 CHANGELOG.md [Unreleased]
    // 注入 change-notes；本 CHANGELOG 存在多个同名 ### Added 分区，解析器取到最后一个分区
    // （M12/M10），且自动注入会覆盖 plugin.xml 中手写的 change-notes。
    // 修复：显式配置 changeNotes（单一来源，与发布内容一致；更新时同步修改此处）。
    patchPluginXml {
        changeNotes = """<ul>
            <li>M19：修复设置语言切换保存失效（写穿式持久化，选中即生效，不再依赖 Settings 确定链路）+ 执行 SQL 移除数据源弹框（直接创建临时文件，编辑器右上角自选数据源后 Ctrl+Enter 执行）</li>
            <li>M18.2：修复插件详情页「最新变化」显示（changeNotes 显式配置）</li>
            <li>M18.1：介绍页新增「问题反馈」入口——点击跳转 Gitee 仓库 Issues 页提交反馈</li>
            <li>M18：插件介绍页底部展示作者微信与交流群二维码（IDE 插件管理页与市场页面均可显示）</li>
            <li>M17：「执行 SQL」按钮——预览面板选择数据源后自动创建临时 .sql 文件（Database 控制台绑定数据源），Ctrl+Enter 执行；兼容 IDEA 2025.3+，Community 版自动隐藏</li>
            <li>M16.3：修复设置语言切换保存失效（roaming 契约）、补全注释不显示（builder 返回值丢弃）、SELECT 空列名三处缺陷</li>
            <li>M16.2：SQL 补全三处回归修复 + 实体父类字段参与补全 + 表前缀/表后缀配置</li>
            <li>M16.1：修复中英文切换保存失效（移除 PluginSettings 双注册）</li>
            <li>M16：SQL 面板字段补全 + 注释提示（@Schema/@ApiModelProperty 四级优先级；位置感知：FROM 后提示表名、WHERE 后提示列名）</li>
            <li>M15：官方 API 全覆盖（union/selectSub/子查询条件/逻辑删除控制等）+ 服务链约 264 处真实用法 + 逻辑删除注入 + 字符串版 QueryWrapper/UpdateWrapper</li>
            <li>M14：设置页增强（界面语言切换/快捷键录制/表前缀后缀）+ 弹窗自适应与尺寸记忆 + 插件名统一</li>
            <li>M13：动态条件简化（复选框纯手动）+ LIKE 自动拼百分号 + 字段类型推断引号</li>
        </ul>"""
    }
}
