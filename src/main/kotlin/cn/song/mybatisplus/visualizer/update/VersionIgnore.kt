package cn.song.mybatisplus.visualizer.update

/**
 * 版本忽略判断与操作（内测通道改造）：纯函数，单测直接覆盖。
 *
 * 用户点通知「忽略此版本」后，该版本仅抑制自动启动通知（[StartupUpdateCheck]），
 * 手动在设置页检查更新时不受影响。忽略列表持久化到 [PluginSettings.State.ignoredUpdateVersions]。
 */
internal fun isVersionIgnored(version: String, ignoredVersions: String): Boolean {
    if (version.isBlank()) return false
    return ignoredVersions.split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .contains(version)
}

/** 加入忽略列表（纯函数，返回新字符串；重复忽略幂等）。 */
internal fun ignoreVersion(version: String, ignoredVersions: String): String {
    if (version.isBlank()) return ignoredVersions
    val current = ignoredVersions.split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toMutableList()
    if (!current.contains(version)) current.add(version)
    return current.joinToString(";")
}
