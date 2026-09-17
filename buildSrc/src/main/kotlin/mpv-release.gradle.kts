import java.time.LocalDate

plugins {
    id("org.jetbrains.intellij.platform")
}

// T-BuildScriptSplit：发版链路分治——块级逐字搬运自根 build.gradle.kts（锚点 dc047bd），
// 注释随迁；验收=buildPlugin 产物 plugin.xml 与基线字节比对。

tasks {
    // M18.2 变更说明修复（用户实测：插件详情页「最新变化」显示停留在 M12）——
    // 根因：patchPluginXml 未显式配置时，org.jetbrains.changelog 自动从 CHANGELOG.md [Unreleased]
    // 注入 change-notes；本 CHANGELOG 存在多个同名 ### Added 分区，解析器取到最后一个分区
    // （M12/M10），且自动注入会覆盖 plugin.xml 中手写的 change-notes。
    // 修复：显式配置 changeNotes（单一来源，与发布内容一致；更新时同步修改此处）。
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
