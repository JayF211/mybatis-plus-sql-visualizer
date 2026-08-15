package cn.song.mybatisplus.visualizer.update

/**
 * 版本号比较（M28）。
 *
 * 插件对外版本号为 `YYYY.M.D`（gradle.properties，一日一版；版本号规范禁止同日递增后缀）——
 * 点分数字逐段比较即可。非数字段按字符串比较兜底；任何异常不抛出（比较异常一律降级为
 * 「不更新」，不打扰用户）。
 */
object VersionComparator {

    /**
     * 比较两个版本号：`a > b` 返回正数，`a == b` 返回 0，`a < b` 返回负数。
     * null 视为小于非 null；同为 null 相等。非数字段（如 `1.a` vs `1.b`）按字符串比较。
     */
    fun compare(a: String?, b: String?): Int {
        if (a == null || b == null) {
            return when {
                a == null && b == null -> 0
                a == null -> -1
                else -> 1
            }
        }
        val aSegments = a.split('.')
        val bSegments = b.split('.')
        val max = maxOf(aSegments.size, bSegments.size)
        for (i in 0 until max) {
            val x = aSegments.getOrNull(i)
            val y = bSegments.getOrNull(i)
            if (x == y) continue
            val xn = x?.toIntOrNull()
            val yn = y?.toIntOrNull()
            val c = if (xn != null && yn != null) {
                xn.compareTo(yn)
            } else {
                // 非数字段（或缺失段按空串）——字符串比较兜底
                (x ?: "").compareTo(y ?: "")
            }
            if (c != 0) return c
        }
        return 0
    }

    /** 版本号是否可识别（非空白且至少一段）。 */
    fun isRecognizable(version: String?): Boolean =
        !version.isNullOrBlank() && version.split('.').any { it.isNotBlank() }
}
