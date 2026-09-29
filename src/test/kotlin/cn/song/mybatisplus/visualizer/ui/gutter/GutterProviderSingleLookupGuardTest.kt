package cn.song.mybatisplus.visualizer.ui.gutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F36（T-PerfSweep16）源码扫描守卫：gutter provider 快速路径的 `PluginSettings.getInstance()`
 * 单次查找。
 *
 * 背景：13 个 provider 原先在到达元素的快速路径上有 3 次 app 级服务查找——`gutterEnabled()`
 * 私有助手内部 1 次 + caller 传给 `GutterGroups.enabledFor` 的 runCatching 实参 1 次 +
 * `enabledFor(Class, Boolean)` 包装内部 1 次。F36 收敛为方法头单次取 state
 * （`val gutterState = runCatching { … }.getOrNull()`）后传给 `(Class, State?)` 重载。
 *
 * 本守卫钉住「每到达元素严格 1 次」不回潮：扫描面 = `getLineMarkerInfo` 方法体 +
 * 其私有门助手 `gutterEnabled()` 体（**两处合计 ≤1**——修复前形态是方法体 1 次、
 * 助手体 1 次，只扫方法体会空绿，故助手必须一并计入；修复后助手删除，仅方法体 1 次）。
 *
 * 豁免：[XmlInlineSqlGutterLineMarkerProvider]（F19 快路径特例，传字面 `true` 走
 * `(Class, Boolean)` 重载，总闸由 XmlInlineSqlDetector 的 gutterFastPathEnabled 自读，
 * 形态保持不动）。
 *
 * 防空绿三件套（对齐 BannedApiSourceScanTest 惯例）：
 *  ① 扫描面保鲜——13 个 provider 文件必须全部存在且 `getLineMarkerInfo` 体必须提取成功
 *     （提取失败按红处理，不静默跳过）；
 *  ② 合成样本自证——双读样本必须被计数器报出、单读样本放行（提取器退化成恒 0 即红）；
 *  ③ 先红后绿落地——本守卫提交前已在未修复的 dev tip 形态上实测红（13 文件各 2 次命中）。
 */
class GutterProviderSingleLookupGuardTest {

    // ------------------------------------------------------------------
    // 规则
    // ------------------------------------------------------------------

    @Test
    fun `gutter provider快速路径_PluginSettings严格单次查找`() {
        val offenders = providerFiles().mapNotNull { (path, source) ->
            val count = fastPathLookupCount(source)
                ?: error("无法在 $path 中提取 getLineMarkerInfo/gutterEnabled 方法体（签名形态漂移？）——守卫扫描面失效按红处理")
            if (count > 1) "$path → $count 次" else null
        }
        assertTrue(
            "gutter provider 快速路径出现多次 PluginSettings.getInstance()（每到达元素 app 级服务查找须严格 1 次，" +
                "F36 定例：方法头单次 `runCatching { PluginSettings.getInstance().state }.getOrNull()` 后传 " +
                "GutterGroups.enabledFor(Class, State?)）。命中 ${offenders.size} 处：\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `守卫自证_合成样本双读必报单读放行`() {
        // 修复前形态（getLineMarkerInfo 体 1 次 + gutterEnabled 助手体 1 次）必须被报出——
        // 防止提取器/计数器退化成恒 0 的空绿。
        val doubleRead = """
            class Sample : LineMarkerProvider {
                override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
                    if (!gutterEnabled()) return null
                    if (!GutterGroups.enabledFor(javaClass, runCatching { PluginSettings.getInstance().state.showGutterIcon }.getOrDefault(true))) return null
                    return null
                }

                private fun gutterEnabled(): Boolean = runCatching {
                    PluginSettings.getInstance().state.showGutterIcon
                }.getOrDefault(true)
            }
        """.trimIndent()
        assertEquals(
            "双读形态必须被计数器识别（否则守卫对真实违规形态失明）：${fastPathLookupCount(doubleRead)}",
            2,
            fastPathLookupCount(doubleRead),
        )

        // 修复后形态（方法头单次取 state，助手已删）必须放行。
        val singleRead = """
            class Sample : LineMarkerProvider {
                override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
                    val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
                    if (gutterState != null && !gutterState.showGutterIcon) return null
                    if (!GutterGroups.enabledFor(javaClass, gutterState)) return null
                    return null
                }
            }
        """.trimIndent()
        assertEquals(
            "单读形态必须放行：${fastPathLookupCount(singleRead)}",
            1,
            fastPathLookupCount(singleRead),
        )
    }

    // ------------------------------------------------------------------
    // 扫描面与提取器
    // ------------------------------------------------------------------

    /** 被守卫的 13 个 provider（XmlInlineSqlGutterLineMarkerProvider 豁免：F19 快路径特例，见类 KDoc）。 */
    private val providerFileNames = listOf(
        "SqlGutterLineMarkerProvider.kt",
        "AnnotationSqlGutterLineMarkerProvider.kt",
        "XmlSqlGutterLineMarkerProvider.kt",
        "PagesXmlGutterLineMarkerProvider.kt",
        "HbmNamedQueryGutterLineMarkerProvider.kt",
        "JpaGutterLineMarkerProvider.kt",
        "JpqlGutterLineMarkerProvider.kt",
        "CriteriaQueryGutterLineMarkerProvider.kt",
        "HqlGutterLineMarkerProvider.kt",
        "CustomDaoHqlGutterLineMarkerProvider.kt",
        "JdbcTemplateSqlGutterLineMarkerProvider.kt",
        "NamedQueryGutterLineMarkerProvider.kt",
        "StringSqlMethodGutterLineMarkerProvider.kt",
    )

    private val gutterDir =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter"

    private fun providerFiles(): List<Pair<String, String>> {
        val root = repoRoot()
        val missing = providerFileNames.filter { !File(File(root, gutterDir), it).isFile }
        assertTrue(
            "守卫扫描面失效：$gutterDir 下缺 $missing——provider 改名/移动后必须同步本守卫清单",
            missing.isEmpty(),
        )
        return providerFileNames.map { name ->
            "$gutterDir/$name" to File(File(root, gutterDir), name).readText()
        }
    }

    /**
     * 快速路径 `PluginSettings.getInstance(` 计数：`getLineMarkerInfo` 体 + `gutterEnabled()`
     * 助手体（修复后助手删除 → 仅方法体计入）。方法体提取失败返回 null（调用方按红处理）。
     */
    private fun fastPathLookupCount(source: String): Int? {
        val stripped = stripComments(source)
        val markerBody = extractBracedBody(stripped, "override fun getLineMarkerInfo") ?: return null
        val helperBody = extractBracedBody(stripped, "fun gutterEnabled(")
        return countToken(markerBody) + (helperBody?.let(::countToken) ?: 0)
    }

    private fun countToken(code: String): Int =
        Regex("PluginSettings\\.getInstance\\(").findAll(code).count()

    /** 取 [marker] 之后第一个配平花括号块（跑在剥注释后的文本上，注释里的花括号不干扰配平）；marker 不存在返回 null。 */
    private fun extractBracedBody(code: String, marker: String): String? {
        val start = code.indexOf(marker)
        if (start < 0) return null
        val open = code.indexOf('{', start + marker.length)
        if (open < 0) return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
        }
        return null
    }

    /** 剥行注释与块注释（这两个 provider 文件族的方法体内只有 // 注释；KDoc 含花括号也不干扰配平）。 */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') i++
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
    }
}
