import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "mybatis-plus-sql-visualizer"

// 子项目统一收拢于 sub-projects/（目录约定：后续每个独立子项目一个同名目录）。
// 采用组合构建（include + projectDir 重定向）而非 Cool Request 式完全独立构建——
// 保住「buildPlugin 自动构建并内嵌 agent jar」的任务依赖链与增量检查。
//
// M56 T-M56-01：agent 字节码增强子项目（Java 8 字节码 fat jar，ASM shade relocate 自包含）。
// 产物 mps-sql-agent.jar 经根项目 embedAgentJar 任务拷入主插件资源 resources/agent/，
// 运行时解压落盘后经 attach loadAgent 挂载——不进插件 classloader，避免污染插件依赖面。
include(":sql-visualizer-agent")
project(":sql-visualizer-agent").projectDir = file("sub-projects/sql-visualizer-agent")

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        // P-立项-05：阿里云公共镜像（Central 全量镜像）置前——本机代理（gradle.properties systemProp
        // 127.0.0.1:7890）失效时 Maven Central 直连失败，新增测试依赖（H2/sqlite-jdbc 等）无法解析；
        // 镜像与 Central 同源同版本，代理恢复后不影响构建正确性。
        maven("https://maven.aliyun.com/repository/public")

        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
