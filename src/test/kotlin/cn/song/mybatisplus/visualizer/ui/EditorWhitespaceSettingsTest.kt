package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.PreviewResult
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 空白符可视化开关测试（T-PreviewWhitespaceToggle O-44a）。
 *
 * 三条判据（对应计划 §四 验收标准 1/2 与「默认关语义」风险项）：
 *  - **默认关 = 不设置该属性**（不是设 false）：关闭态调用后编辑器原有取值必须**原样保留**
 *    （人为开着的仍是开着——若实现写成 `setWhitespacesShown(false)` 本用例即红）；
 *  - 开启态 = 显示空白符，且 **SQL 文本逐字节不变**（纯显示层，零文档写入）；
 *  - 平台继承机制（未显式设置 → 取 IDE 全局值）的实证探针——计划 §八 待验证前提 2 原登记为
 *    「无法从 jar 侧佐证」；本轮实测 `EditorSettingsExternalizable` 在 243 发行版里**存在**
 *    （`com.intellij.openapi.editor.ex` 包，此前按 `openapi.editor` 包名查找故报「找不到类」），
 *    故该前提由 [test未显式设置时编辑器取平台全局空白符值] 直接实测而非推断。
 */
class EditorWhitespaceSettingsTest : BasePlatformTestCase() {

    /** 含空格、Tab、行尾空格与多行的 SQL——逐字节比对用（任何显示层改写都会破坏它）。 */
    private val sql = "select  id,\tname \nfrom  user\nwhere  age > 1 \n"

    private fun newEditor(text: String = sql): Editor =
        EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(text))

    private fun <T> withEditor(text: String = sql, body: (Editor) -> T): T {
        val editor = newEditor(text)
        try {
            return body(editor)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    // ------------------------------------------------------------------
    // 验收 1：默认关 = 不设置该属性（不是设 false）
    // ------------------------------------------------------------------

    fun test关闭态不设置该属性_人为开启后取值原样保留() {
        withEditor { editor ->
            editor.settings.isWhitespacesShown = true
            EditorWhitespaceSettings.applyTo(editor, showWhitespaces = false)
            assertTrue(
                "关闭态必须「不设置该属性」——人为开启的编辑器设置不得被改写成 false" +
                    "（写成 setWhitespacesShown(false) 即覆盖用户全局偏好，验收 1 明令禁止）",
                editor.settings.isWhitespacesShown,
            )
        }
    }

    fun test关闭态不设置该属性_人为关闭后取值同样保留() {
        withEditor { editor ->
            editor.settings.isWhitespacesShown = false
            EditorWhitespaceSettings.applyTo(editor, showWhitespaces = false)
            assertFalse(
                "关闭态零写入：编辑器取值应与调用前一致（此处为 false，非因被设置而 false）",
                editor.settings.isWhitespacesShown,
            )
        }
    }

    fun test读设置版本_关闭态不改动编辑器() {
        val state = PluginSettings.getInstance().state
        val old = state.editorShowWhitespaces
        try {
            state.editorShowWhitespaces = false
            withEditor { editor ->
                editor.settings.isWhitespacesShown = true
                EditorWhitespaceSettings.applyTo(editor)
                assertTrue(
                    "读设置入口在开关关闭时同样不得触碰编辑器设置（人为开启态须保持）",
                    editor.settings.isWhitespacesShown,
                )
            }
        } finally {
            state.editorShowWhitespaces = old
        }
    }

    fun test默认值为关() {
        assertFalse(
            "出厂默认必须是关（= 不设置该属性、继承 IDE 全局）——默认开会让所有用户被动看到空白符",
            PluginSettings.State().editorShowWhitespaces,
        )
    }

    // ------------------------------------------------------------------
    // 验收 2：开启态显示空白符 + SQL 文本逐字节不变
    // ------------------------------------------------------------------

    fun test开启态显示空白符且SQL文本逐字节不变() {
        withEditor { editor ->
            EditorWhitespaceSettings.applyTo(editor, showWhitespaces = true)
            assertTrue("开启态应显示空白符", editor.settings.isWhitespacesShown)
            assertEquals(
                "纯显示层：开启空白符可视化不得改动文档文本（逐字节不变）",
                sql,
                editor.document.text,
            )
        }
    }

    fun test读设置版本_开启态显示空白符且SQL文本逐字节不变() {
        val state = PluginSettings.getInstance().state
        val old = state.editorShowWhitespaces
        try {
            state.editorShowWhitespaces = true
            withEditor { editor ->
                EditorWhitespaceSettings.applyTo(editor)
                assertTrue("开关开启时编辑器应显示空白符", editor.settings.isWhitespacesShown)
                assertEquals("开关开启不得改动 SQL 文本", sql, editor.document.text)
            }
        } finally {
            state.editorShowWhitespaces = old
        }
    }

    // ------------------------------------------------------------------
    // 接线覆盖：预览面板（主目标）真实构造链上的编辑器
    // ------------------------------------------------------------------

    /**
     * 接线覆盖：预览面板（主目标）真实构造链上的编辑器。
     *
     * 「文本逐字节不变」的判据**不能**拿输入 SQL 当期望值——预览面板本就把 SQL 按显示模式
     * （关键字换行等）重排后再渲染（首跑实证：输入 `select  id,…` 在编辑器里是 `SELECT\n    id,…`）。
     * 开关只影响渲染，故正确判据是**同一输入在开/关两态下产出的文档文本逐字节相等**。
     *
     * 生命周期照 SqlPreviewPanelTest 既有形态（同面板二次 `showResult` 换编辑器 + 收尾
     * `clear()`，不 `dispose()`）——建两个面板各 dispose 一次会撞 `SqlExecutor.releaseForPanel()`
     * 单例的重复释放（首跑实证 `TraceableDisposable$DisposalException`）。
     * 二次 `showResult` **必须先 `clear()`**：`showSql` 在 `editor != null` 时复用既有编辑器
     * 而非重建（`:2456-2470`），不清则第二次渲染读不到新开关值（二跑实证）。
     */
    fun test预览面板接线_开启态显示空白符且开关不改变SQL文本() {
        val state = PluginSettings.getInstance().state
        val old = state.editorShowWhitespaces
        val panel = SqlPreviewPanel(project)
        try {
            state.editorShowWhitespaces = false
            panel.showResult(previewResult())
            val offEditor = panel.editorForTest()
            assertNotNull("预览面板应挂载编辑器", offEditor)
            val offText = offEditor!!.document.text
            panel.clear()

            state.editorShowWhitespaces = true
            panel.showResult(previewResult())
            val onEditor = panel.editorForTest()
            assertNotNull("clear 后二次 showResult 应重建编辑器", onEditor)
            assertTrue(
                "预览面板编辑器应显示空白符（接线点在 SqlPreviewPanel 的 EditorEx.apply 块内）",
                onEditor!!.settings.isWhitespacesShown,
            )
            assertEquals(
                "开关只影响显示：开/关两态下预览面板的 SQL 文本必须逐字节相同",
                offText,
                onEditor.document.text,
            )
        } finally {
            panel.clear()
            state.editorShowWhitespaces = old
        }
    }

    private fun previewResult() = PreviewResult(
        sql = sql,
        compactSql = sql,
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = "user",
    )

    // ------------------------------------------------------------------
    // 平台继承机制实证（计划 §八 待验证前提 2）
    // ------------------------------------------------------------------

    /**
     * 「不设置该属性 → 取 IDE 全局值」的直接实测。
     *
     * 这是验收 1 判据的**机制面**证据：关闭态不调用 setter，编辑器取值来自平台全局偏好。
     * 全局偏好属应用级共享状态，用例内改、finally 复位（不落 setUp，避免跨类串台）。
     */
    fun test未显式设置时编辑器取平台全局空白符值() {
        val global = EditorSettingsExternalizable.getInstance()
        val old = global.isWhitespacesShown
        try {
            global.isWhitespacesShown = true
            withEditor { editor ->
                assertTrue(
                    "未显式设置该属性时编辑器应取平台全局值（不设置=继承）——" +
                        "若此处为 false，则「关闭态继承全局」不成立，验收 1 的实现路线须改为显式回写全局值",
                    editor.settings.isWhitespacesShown,
                )
            }
            global.isWhitespacesShown = false
            withEditor { editor ->
                assertFalse(
                    "全局关时未显式设置的编辑器应为关（同一机制的反向读数）",
                    editor.settings.isWhitespacesShown,
                )
            }
        } finally {
            global.isWhitespacesShown = old
        }
    }
}
