package cn.song.mybatisplus.visualizer.update;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本忽略判断与操作（内测通道改造）：纯函数，单测直接覆盖。
 *
 * 用户点通知「忽略此版本」后，该版本仅抑制自动启动通知（[StartupUpdateCheck]），
 * 手动在设置页检查更新时不受影响。忽略列表持久化到 PluginSettings.State.ignoredUpdateVersions。
 */
public final class VersionIgnore {

    private VersionIgnore() {}

    /** 判断版本是否被忽略（忽略列表以 `;` 分隔）。空白 version 一律视为未忽略。 */
    public static boolean isVersionIgnored(String version, String ignoredVersions) {
        if (version == null || version.isBlank()) return false;
        for (String it : ignoredVersions.split(";")) {
            String trimmed = it.trim();
            if (!trimmed.isEmpty() && trimmed.equals(version)) return true;
        }
        return false;
    }

    /** 加入忽略列表（纯函数，返回新字符串；重复忽略幂等）。 */
    public static String ignoreVersion(String version, String ignoredVersions) {
        if (version == null || version.isBlank()) return ignoredVersions;
        List<String> current = new ArrayList<>();
        for (String it : ignoredVersions.split(";")) {
            String trimmed = it.trim();
            if (!trimmed.isEmpty()) current.add(trimmed);
        }
        if (!current.contains(version)) current.add(version);
        return String.join(";", current);
    }
}
