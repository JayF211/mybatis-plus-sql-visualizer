package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.FoldRegion
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * SqlAutoFolding 公共应用器平台测试（T-AutoFold F0/F6）。
 *
 * 覆盖：开启 + 宽表 → 区域存在且 collapsed、占位符正确；默认关 → 零区域；
 * 开→关二次 applyTo 清除旧区域；重复 applyTo 幂等（无双层嵌套）；
 * 只摘本方清单（预置非本方区域，applyTo 后仍在）。
 */
class SqlAutoFoldingTest : BasePlatformTestCase() {

    private val wideSql = "SELECT\n    a1,\n    a2,\n    a3,\n    a4\nFROM t"

    private var oldEnabled: Boolean = false
    private var oldThreshold: Int = 0

    override fun setUp() {
        super.setUp()
        val state = PluginSettings.getInstance().state
        oldEnabled = state.autoFoldEnabled
        oldThreshold = state.autoFoldFieldThreshold
        state.autoFoldEnabled = false
    }

    override fun tearDown() {
        val state = PluginSettings.getInstance().state
        state.autoFoldEnabled = oldEnabled
        state.autoFoldFieldThreshold = oldThreshold
        super.tearDown()
    }

    private fun enable() {
        val state = PluginSettings.getInstance().state
        state.autoFoldEnabled = true
        state.autoFoldFieldThreshold = 3
    }

    private fun newEditor(sql: String): Editor =
        EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(sql))

    private fun releaseEditor(editor: Editor) {
        runCatching { EditorFactory.getInstance().releaseEditor(editor) }
    }

    private fun ownRegions(editor: Editor): List<FoldRegion> =
        editor.foldingModel.allFoldRegions.filter { it.placeholderText.startsWith("…") }

    fun test开启且宽表时区域存在且收起占位符正确() {
        enable()
        val editor = newEditor(wideSql)
        try {
            SqlAutoFolding.applyTo(editor, wideSql)
            val regions = ownRegions(editor)
            assertEquals("应有一个本方折叠区域", 1, regions.size)
            val region = regions[0]
            assertFalse("区域应收起", region.isExpanded)
            assertEquals("… 4 列", region.placeholderText)
            assertEquals(wideSql.indexOf("a1"), region.startOffset)
            assertEquals(wideSql.indexOf("FROM") - 1, region.endOffset)
        } finally {
            releaseEditor(editor)
        }
    }

    fun test默认关时零区域() {
        val editor = newEditor(wideSql)
        try {
            SqlAutoFolding.applyTo(editor, wideSql)
            assertEquals("默认关闭不应产生任何区域", 0, editor.foldingModel.getAllFoldRegions().size)
        } finally {
            releaseEditor(editor)
        }
    }

    fun test开关切换后二次applyTo清除旧区域() {
        enable()
        val editor = newEditor(wideSql)
        try {
            SqlAutoFolding.applyTo(editor, wideSql)
            assertEquals(1, ownRegions(editor).size)
            PluginSettings.getInstance().state.autoFoldEnabled = false
            SqlAutoFolding.applyTo(editor, wideSql)
            assertEquals("关闭后二次 applyTo 应清除本方区域", 0, editor.foldingModel.getAllFoldRegions().size)
        } finally {
            releaseEditor(editor)
        }
    }

    fun test重复applyTo幂等无双层嵌套() {
        enable()
        val editor = newEditor(wideSql)
        try {
            SqlAutoFolding.applyTo(editor, wideSql)
            SqlAutoFolding.applyTo(editor, wideSql)
            SqlAutoFolding.applyTo(editor, wideSql)
            assertEquals("重复应用应幂等", 1, ownRegions(editor).size)
        } finally {
            releaseEditor(editor)
        }
    }

    fun test只摘本方清单不越权清除他人区域() {
        enable()
        val editor = newEditor(wideSql)
        try {
            // 预置一个「他人」区域（模拟平台 ConsoleFolding 等），覆盖首行
            editor.foldingModel.runBatchFoldingOperation {
                editor.foldingModel.addFoldRegion(0, wideSql.indexOf("SELECT") + 6, "[other]")!!
                    .isExpanded = false
            }
            SqlAutoFolding.applyTo(editor, wideSql)
            val others = editor.foldingModel.getAllFoldRegions().filter { it.placeholderText == "[other]" }
            assertEquals("他人区域应保留", 1, others.size)
            assertEquals("本方区域照常添加", 1, ownRegions(editor).size)
        } finally {
            releaseEditor(editor)
        }
    }

    // ============ T-PreviewEdtGuard S1：同文本幂等短路（重渲染链 EDT 降噪） ============
    //
    // renderSql→showSql 每轮渲染都调 applyTo；参数防抖后的常态是渲染文本未变——全量重建
    // （2×N 个 fold region 增删 + 两次批操作）是纯冗余 EDT 工作。修复=同文本 + 本方区域仍有效
    // → 短路返回。两例配对：上例钉「短路生效」（摘掉修复即红——区域被重建、实例换代），
    // 下例钉「防御语义保留」（短路判据含 isValid 检查，平台抹除后照常重建）。

    fun test同文本二次applyTo短路区域实例不重建() {
        enable()
        val editor = newEditor(wideSql)
        try {
            SqlAutoFolding.applyTo(editor, wideSql)
            val first = ownRegions(editor).single()
            SqlAutoFolding.applyTo(editor, wideSql)
            val second = ownRegions(editor).single()
            assertSame(
                "同文本二次 applyTo 必须短路（区域实例不重建——每轮渲染全量重建是重渲染链 EDT 噪音主体）",
                first, second,
            )
        } finally {
            releaseEditor(editor)
        }
    }

    fun test同文本但区域被外部抹除后照常重建() {
        enable()
        val editor = newEditor(wideSql)
        try {
            SqlAutoFolding.applyTo(editor, wideSql)
            // 外部抹除（模拟平台重算清折叠）——短路判据的 isValid 检查必须放行重建
            editor.foldingModel.runBatchFoldingOperation {
                ownRegions(editor).forEach { editor.foldingModel.removeFoldRegion(it) }
            }
            SqlAutoFolding.applyTo(editor, wideSql)
            assertEquals(
                "区域被外部抹除后同文本 applyTo 必须照常重建（T-AutoFold 防御语义保留）",
                1, ownRegions(editor).size,
            )
        } finally {
            releaseEditor(editor)
        }
    }

    fun test同文本但开启态翻转后不短路照常收敛() {
        enable()
        val editor = newEditor(wideSql)
        try {
            SqlAutoFolding.applyTo(editor, wideSql)
            assertEquals(1, ownRegions(editor).size)
            PluginSettings.getInstance().state.autoFoldEnabled = false
            SqlAutoFolding.applyTo(editor, wideSql)
            assertEquals(
                "关闭态不受短路影响（短路判据含开启态检查）——本方区域照常清除",
                0, editor.foldingModel.getAllFoldRegions().size,
            )
        } finally {
            releaseEditor(editor)
        }
    }
}
