package cn.song.mybatisplus.visualizer.update

import com.intellij.util.text.VersionComparatorUtil

/**
 * 版本号比较（M28 → T-VersionUtil）：委托 IDEA 内置
 * [com.intellij.util.text.VersionComparatorUtil]（`lib/util_rt.jar`，243/253/262 三平台
 * javap 实证 `compare(String,String)` 签名稳定），与 Marketplace/IDE 自身"发现新版"的
 * 判定完全同规则。
 *
 * 背景：旧自研点分比较把 `2026.9.10-beta.1` 第三段切成 `"10-beta"` 走字符串兜底，判其
 * **大于** `2026.9.10`——同日「先 beta 后转正」场景下 Gitee 内测通道提示倒挂。平台比较器
 * 识别 `-beta` 预发布段，正确判小（本机 262 util_rt.jar 实证 `beta.N < 正式版`，且
 * `beta.10 > beta.9` 数值段比较仍成立）。
 *
 * null 与异常兜底保留在包装层（与旧行为一致）：null 视为小于非 null；比较异常一律降级为
 * 0（「不更新」，不打扰用户）。插件版本号为 `YYYY.M.D[-beta.N]`（gradle.properties，
 * 一日一版；版本号规范禁止同日数字递增后缀，同日多版走 `-beta.N`）。
 */
object VersionComparator {

    /**
     * 比较两个版本号：`a > b` 返回正数，`a == b` 返回 0，`a < b` 返回负数。
     * null 视为小于非 null；同为 null 相等。委托平台比较器，任何异常返回 0。
     */
    fun compare(a: String?, b: String?): Int {
        if (a == null || b == null) {
            return when {
                a == null && b == null -> 0
                a == null -> -1
                else -> 1
            }
        }
        return runCatching { VersionComparatorUtil.compare(a, b) }.getOrDefault(0)
    }

    /** 版本号是否可识别（非空白且至少一段）。 */
    fun isRecognizable(version: String?): Boolean =
        !version.isNullOrBlank() && version.split('.').any { it.isNotBlank() }
}
