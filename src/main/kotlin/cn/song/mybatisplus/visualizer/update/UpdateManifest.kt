package cn.song.mybatisplus.visualizer.update

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 更新清单（M28）：托管于发布仓库固定路径 `update/update.json`（Gitee raw URL，与仓库同源），
 * 发布时由 Gradle 任务 `writeUpdateManifest` 生成骨架、人工补 changeNotes（见 build.gradle.kts）。
 *
 * 字段约定：`version`/`downloadUrl` 必填（缺失即整份清单无效）；`changeNotes`/`publishDate`/
 * `sinceBuild` 可选（sinceBuild 高于当前 IDE 构建时仅提示不阻塞下载）。
 *
 * 解析集中在 [parseManifest]（Gson，平台捆绑库），失败返回 null——调用方降级处理。
 */
data class UpdateManifest(
    /** 新版插件版本号（`YYYY.M.D`，与清单自身语义一致）。 */
    val version: String,
    /** 新版插件包下载地址（Gitee Release 附件，HTTPS）。 */
    val downloadUrl: String,
    /** 变更日志（纯文本，`\n` 多行）；null = 未提供。 */
    val changeNotes: String?,
    /** 发布日期（`YYYY-MM-DD`）；null = 未提供。 */
    val publishDate: String?,
    /** 最低 IDE 构建号（如 `253`）；null = 未声明。 */
    val sinceBuild: String?,
) {
    /** sinceBuild 是否已声明且高于给定 IDE 构建号（不兼容提示用）。 */
    fun warnsIncompatible(currentBuildMajor: Int?): Boolean {
        if (sinceBuild == null || currentBuildMajor == null) return false
        val required = sinceBuild.trim().toIntOrNull() ?: return false
        return required > currentBuildMajor
    }
}

/**
 * 解析清单 JSON 文本；非法 JSON / 缺必填字段 / 类型不符 → null（调用方降级）。
 * 纯函数（内部可见，单测直接覆盖）。
 */
internal fun parseManifest(json: String): UpdateManifest? = runCatching {
    val map: Map<String, String>? = Gson().fromJson(json, object : TypeToken<Map<String, String>>() {}.type)
    val version = map?.get("version")?.takeIf { it.isNotBlank() } ?: return null
    val downloadUrl = map["downloadUrl"]?.takeIf { it.isNotBlank() } ?: return null
    UpdateManifest(
        version = version,
        downloadUrl = downloadUrl,
        changeNotes = map["changeNotes"]?.takeIf { it.isNotBlank() },
        publishDate = map["publishDate"]?.takeIf { it.isNotBlank() },
        sinceBuild = map["sinceBuild"]?.takeIf { it.isNotBlank() },
    )
}.getOrNull()
