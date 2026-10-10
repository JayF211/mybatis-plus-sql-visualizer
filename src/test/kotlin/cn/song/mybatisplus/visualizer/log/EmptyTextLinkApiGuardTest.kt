package cn.song.mybatisplus.visualizer.log

import junit.framework.TestCase
import java.io.File

/**
 * 空态链接渲染 API 守卫（T-SettingsFocusFromLink 二修，20261010 真机缺陷驱动）。
 *
 * 坑：平台 `StatusText.appendSecondaryText` 内部**固定写 row=1**（第二行/副列语义，
 * 243 源码 `appendText(true, 1, …)`）——多行空态文案下用它追加链接会把链接叠印进第二行中段
 * （真机实证「三个链接贴在一起」）。空态链接必须经 [appendLinkRow]（`appendLine("")` 起行 +
 * `appendText` 追加）。
 *
 * 判别力：生产码出现**实际调用**（非注释提及）即红；`appendLinkRow` 自身的 KDoc 里出现该词
 * 属注释行（行首 `*`/`//`），不误报。
 */
class EmptyTextLinkApiGuardTest : TestCase() {

    fun test生产码无appendSecondaryText实际调用() {
        val root = File("src/main/kotlin")
        assertTrue("源码根目录应存在（工作目录=项目根）", root.isDirectory)
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, raw ->
                val trimmed = raw.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                if (raw.contains("appendSecondaryText")) {
                    offenders += "${file.path}:${index + 1}: ${raw.trim()}"
                }
            }
        }
        assertTrue(
            "空态链接不得用 appendSecondaryText（固定写第二行会叠印；改用 appendLinkRow）：$offenders",
            offenders.isEmpty(),
        )
    }
}
