package cn.song.mybatisplus.visualizer.update;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * 更新清单（M28）：托管于发布仓库固定路径 `update/update.json`（Gitee raw URL，与仓库同源），
 * 发布时由 Gradle 任务 `writeUpdateManifest` 生成骨架、人工补 changeNotes（见 build.gradle.kts）。
 *
 * 字段约定：`version`/`downloadUrl` 必填（缺失即整份清单无效）；`changeNotes`/`publishDate`/
 * `sinceBuild` 可选（sinceBuild 高于当前 IDE 构建时仅提示不阻塞下载）。
 *
 * 解析集中在 [parseManifest]（Gson，平台捆绑库），失败返回 null——调用方降级处理。
 *
 * @param version      新版插件版本号（`YYYY.M.D`，与清单自身语义一致）。
 * @param downloadUrl  新版插件包下载地址（Gitee Release 附件，HTTPS）。
 * @param changeNotes  变更日志（纯文本，`\n` 多行）；null = 未提供。
 * @param publishDate  发布日期（`YYYY-MM-DD`）；null = 未提供。
 * @param sinceBuild   最低 IDE 构建号（如 `253`）；null = 未声明。
 */
public record UpdateManifest(
    String version,
    String downloadUrl,
    String changeNotes,
    String publishDate,
    String sinceBuild
) {

    /** sinceBuild 是否已声明且高于给定 IDE 构建号（不兼容提示用）。 */
    public boolean warnsIncompatible(Integer currentBuildMajor) {
        if (sinceBuild == null || currentBuildMajor == null) return false;
        Integer required = toIntOrNull(sinceBuild.trim());
        if (required == null) return false;
        return required > currentBuildMajor;
    }

    /**
     * 解析清单 JSON 文本；非法 JSON / 缺必填字段 / 类型不符 → null（调用方降级）。
     * 纯函数（内部可见，单测直接覆盖）。
     */
    public static UpdateManifest parseManifest(String json) {
        try {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> map = new Gson().fromJson(json, type);
            if (map == null) return null;
            String version = map.get("version");
            if (version == null || version.isBlank()) return null;
            String downloadUrl = map.get("downloadUrl");
            if (downloadUrl == null || downloadUrl.isBlank()) return null;
            String changeNotesRaw = map.get("changeNotes");
            String publishDateRaw = map.get("publishDate");
            String sinceBuildRaw = map.get("sinceBuild");
            return new UpdateManifest(
                version,
                downloadUrl,
                (changeNotesRaw != null && !changeNotesRaw.isBlank()) ? changeNotesRaw : null,
                (publishDateRaw != null && !publishDateRaw.isBlank()) ? publishDateRaw : null,
                (sinceBuildRaw != null && !sinceBuildRaw.isBlank()) ? sinceBuildRaw : null
            );
        } catch (Exception e) {
            return null;
        }
    }

    /** Kotlin `String.toIntOrNull` 等价：无法解析时返回 null。 */
    private static Integer toIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
