package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.ide.CopyPasteManager
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.psi.TokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton

/**
 * 单击 SQL 预览接线测试（T-ClickPreview）：
 *
 * 用户反馈「SQL 日志列表条目多后鼠标悬停卡顿」——根因 = 渲染器 toolTipText = 完整 SQL，
 * 悬停逐行弹气泡 × 可变行高重排。改造为「单击出预览、双击开完整弹窗、悬停零成本」。
 * 二轮反馈：① 点其他地方后未再单击 SQL，预览又出现 → 排程严格绑定"点到真实行"（点击时
 * 捕获该行 SQL，空白区点击立即停表关预览，不借旧选中复活）；② 预览弹窗要可编辑、带复制/
 * 关闭按钮；③ 弹窗不得遮住被点行（锚点 = 行下缘）。
 * 三轮反馈：预览要 SQL 高亮且可编辑（临时改字段值再复制）→ 内容升级为纯文档 + SQL FileType
 * 驱动词法高亮的可编辑编辑器（无 PSI，规避 EDT read access 陷阱；fileType 缺席降级纯文本）。
 * 四轮反馈：① 预览高亮与列表条目颜色不一致（两套高亮体系并存）→ 编辑器高亮改
 * [SqlListSyntaxHighlighter]（与列表共用 token→配色键映射）；② 编辑器打不了字 → 弹窗
 * setRequestFocus(false) 拿不到键盘焦点，改 true 弹出即聚焦。
 *
 * headless 约束：SqlClickPreview.show 依赖真实屏幕（RelativePoint → getLocationOnScreen），
 * 弹窗「构建/内容」与「交互排程/取消/锚点」分别断言；显示行为由 runIde 真机覆盖。
 */
class SqlClickPreviewTest : BasePlatformTestCase() {

    private fun entry(sql: String) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 1L,
    )

    /** 定高行高列表（getCellBounds/locationToIndex 纯数学计算，headless 可用）。 */
    private fun fixedList(vararg sqls: String): JBList<LogSqlEntry> {
        val model = DefaultListModel<LogSqlEntry>()
        sqls.forEach { model.addElement(entry(it)) }
        return JBList(model).apply {
            fixedCellHeight = 20
            setSize(100, 100)
        }
    }

    private fun releaseEditor(editor: Editor) {
        runCatching { EditorFactory.getInstance().releaseEditor(editor) }
    }

    private fun findButtons(root: java.awt.Container): List<JButton> =
        UIUtil.uiTraverser(root)
            .preOrderDfsTraversal()
            .filterIsInstance<JButton>()
            .toList()

    // ---------- 弹窗构建：SQL 高亮编辑器 + 可编辑 + 复制/关闭按钮 ----------

    fun test预览内容为可编辑SQL编辑器_文本等于还原SQL_带复制关闭按钮() {
        val sql = "SELECT * FROM user WHERE id = 1;"
        val content = SqlClickPreview.buildContent(
            project,
            sql,
            copyText = {},
            close = {},
        )
        try {
            assertEquals("编辑器文档应等于传入 SQL", sql, content.editor.document.text)
            assertFalse("编辑器应可编辑（非 viewer）", content.editor.isViewer)
            assertTrue("长 SQL 应软换行免横向滚动", content.editor.settings.isUseSoftWraps)

            val buttons = findButtons(content.component)
            val tooltips = buttons.map { it.toolTipText }.toSet()
            assertTrue("应含复制按钮（tooltip = i18n 文案）", tooltips.contains(MpSqlVisualizerBundle.message("log.preview.copy")))
            assertTrue("应含关闭按钮（tooltip = i18n 文案）", tooltips.contains(MpSqlVisualizerBundle.message("log.preview.close")))
        } finally {
            releaseEditor(content.editor)
        }
    }

    /** 四轮①：编辑器高亮与列表条目同源——同一 SqlLexer 的 token 覆盖全文，配色键共用同一映射。 */
    fun test编辑器高亮与列表片段同源同色() {
        val sql = "SELECT * FROM user WHERE id = 1;"
        val content = SqlClickPreview.buildContent(
            project,
            sql,
            copyText = {},
            close = {},
        )
        try {
            val highlighter = content.editor.highlighter
            assertTrue(
                "预览编辑器应挂自建词法高亮器（与列表同色链路）",
                highlighter is LexerEditorHighlighter,
            )
            val editorTokens = buildList {
                val iterator = highlighter.createIterator(0)
                while (!iterator.atEnd()) {
                    add(iterator.tokenType to iterator.start..iterator.end)
                    iterator.advance()
                }
            }
            assertTrue("编辑器应产出非平凡 token 序列", editorTokens.isNotEmpty())
            assertEquals("编辑器 token 应从文首覆盖", 0, editorTokens.first().second.first)
            assertEquals("编辑器 token 应覆盖到文末", sql.length, editorTokens.last().second.last)
            // 非空白 token 的配色键走 ATTR_BY_TYPE（与列表片段通道共用唯一映射 → 同色）
            val nonWhitespace = editorTokens.map { it.first }.filter { it != TokenType.WHITE_SPACE }
            assertTrue("应存在非空白 token", nonWhitespace.isNotEmpty())
            nonWhitespace.forEach { type ->
                assertNotNull("非空白 token 应有配色键（$type）", SqlListSyntaxHighlighter.ATTR_BY_TYPE[type])
            }
            // 列表片段通道（同一 SqlLexer）拼回文本与原文一致
            assertEquals(
                "列表片段通道文本应守恒",
                sql,
                SqlListFragmentRenderer.renderFragments(sql).joinToString("") { it.text },
            )
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test复制按钮回调携带当前文本_关闭按钮回调触发() {
        var copied: String? = null
        var closed = false
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = { closed = true },
        )
        try {
            findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
                .doClick()
            assertEquals("复制按钮应回调当前文本", "SELECT 1;", copied)
            findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.close") }
                .doClick()
            assertTrue("关闭按钮应触发关闭回调", closed)
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test复制按钮复制用户编辑后的内容() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = {},
        )
        try {
            // 模拟用户临时改字段值（文档写入须 write action）
            WriteCommandAction.runWriteCommandAction(project) {
                content.editor.document.setText("SELECT edited_price FROM user;")
            }
            findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
                .doClick()
            assertEquals("复制应取编辑后的文本", "SELECT edited_price FROM user;", copied)
        } finally {
            releaseEditor(content.editor)
        }
    }

    /** 六轮：复制按钮点击后图标即时换成对号（复制成功反馈），无 NoneIcon 或其他非对号图标。 */
    fun test复制按钮点击后图标换对号反馈() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = {},
        )
        try {
            val copyButton = findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
            val originalIcon = copyButton.icon
            copyButton.doClick()
            assertEquals("复制应回调当前文本", "SELECT 1;", copied)
            assertEquals(
                "复制后按钮图标应立即换成对号（成功反馈）",
                AllIcons.Actions.Checked,
                copyButton.icon,
            )
            assertNotEquals(
                "对号不应与初始复制图标相同（否则无反馈变化）",
                originalIcon,
                copyButton.icon,
            )
        } finally {
            releaseEditor(content.editor)
        }
    }

    // ---------- 交互语义：命中行才排程，空白区不复活 ----------

    fun test单击行排程预览_点空白区取消并隐藏() {
        val list = fixedList("SELECT 1;")
        val cp = SqlClickPreview(project, list) { i -> withTerminator(list.model.getElementAt(i).restoredSql) }
        cp.onClick(1, Point(5, 10)) // 落在第 0 行（行高 20）内
        assertTrue("命中行的单击应排程预览", cp.isScheduled)
        assertNull("延迟内不应立即弹窗", cp.currentPopup)

        cp.onClick(1, Point(5, 500)) // 列表空白区（无行）
        assertFalse("空白区点击应立即取消排程（杜绝借旧选中复活预览）", cp.isScheduled)
        assertNull("空白区点击不应残留弹窗", cp.currentPopup)

        cp.onClick(1, Point(5, 10))
        cp.onClick(2, Point(5, 10))
        assertFalse("双击应取消单击预览排程", cp.isScheduled)
        assertNull("双击不应残留预览弹窗", cp.currentPopup)
    }

    fun test锚点在被点行下缘不遮行() {
        val list = fixedList("SELECT 1;", "SELECT 2;")
        val cp = SqlClickPreview(project, list) { i -> list.model.getElementAt(i).restoredSql }
        val anchor = cp.anchorPointFor(0)
        assertNotNull("有效行应给出锚点", anchor)
        assertTrue("锚点应在被点行下缘（不遮行）", anchor!!.y >= 20)
        assertEquals(22, anchor.y) // 行底 20 + 2px 缝
        val anchor1 = cp.anchorPointFor(1)
        assertEquals(42, anchor1!!.y) // 第二行下缘
    }

    // ---------- T-ListPreviewCopy：多选守卫 + 预览文本格式化 ----------

    fun test多选进行中单击不排程预览() {
        val list = fixedList("SELECT 1;", "SELECT 2;", "SELECT 3;")
        list.selectedIndices = intArrayOf(0, 2) // Ctrl 多选（真实链路 mousePressed 已更新选中再回调 mouseClicked）
        val cp = SqlClickPreview(project, list) { i -> withTerminator(list.model.getElementAt(i).restoredSql) }
        cp.onClick(1, Point(5, 10)) // 落在第 0 行内
        assertFalse("多选进行中单击不应排程预览", cp.isScheduled)
        assertNull("多选进行中单击不应弹窗", cp.currentPopup)

        // 空白区单击仍走取消语义（守卫不遮蔽既有收表关旧）
        cp.onClick(1, Point(5, 500))
        assertFalse(cp.isScheduled)
        assertNull(cp.currentPopup)
    }

    fun test多选恢复单选后单击恢复排程() {
        val list = fixedList("SELECT 1;", "SELECT 2;", "SELECT 3;")
        val cp = SqlClickPreview(project, list) { i -> withTerminator(list.model.getElementAt(i).restoredSql) }
        try {
            list.selectedIndices = intArrayOf(0, 2)
            cp.onClick(1, Point(5, 10))
            assertFalse("多选中不排程", cp.isScheduled)

            list.selectedIndices = intArrayOf(1) // 收回单选（如点击第三行把多选收敛为该行）
            cp.onClick(1, Point(5, 30)) // 落在第 1 行内
            assertTrue("恢复单选后单击应恢复排程预览", cp.isScheduled)
        } finally {
            // 挂起定时器 300ms 后会在 EDT 真弹带编辑器的预览浮窗——测试内必须停表，防编辑器泄漏
            cp.dispose()
        }
    }

    // ---------- T-ClickPreviewCopyRestore：修饰键守卫（多选第一击）+ 编辑器 Ctrl+C 复制全文 ----------

    /** 模拟快捷键分派：直调当前注册的 copy handler（keymap Ctrl+C / Ctrl+Insert 绑定同一 action 入口）。 */
    private fun dispatchEditorCopy(editor: Editor) {
        // 243 action 管线拒绝裸匿名 DataContext（Utils.reportUnexpectedDataContextKind），
        // 上下文必须是 DataManager/CustomizedDataContext/SimpleDataContext 之一——用 SimpleDataContext 空上下文
        val context = SimpleDataContext.getSimpleContext(emptyMap<String, Any>(), null)
        EditorActionManager.getInstance()
            .getActionHandler(IdeActions.ACTION_EDITOR_COPY)
            .execute(editor, null, context)
    }

    fun test修饰键判定纯函数只认CtrlShift() {
        assertTrue(isMultiSelectIntent(MouseEvent.CTRL_DOWN_MASK))
        assertTrue(isMultiSelectIntent(MouseEvent.SHIFT_DOWN_MASK))
        // 真实转发携带 BUTTON1（左键正按下）：Ctrl+左键组合必须识别，纯左键/Alt 不得误报
        assertTrue(isMultiSelectIntent(MouseEvent.CTRL_DOWN_MASK or MouseEvent.BUTTON1_DOWN_MASK))
        assertFalse(isMultiSelectIntent(0))
        assertFalse(isMultiSelectIntent(MouseEvent.BUTTON1_DOWN_MASK))
        assertFalse(isMultiSelectIntent(MouseEvent.ALT_DOWN_MASK))
    }

    fun testCtrl修饰键单击不排程预览_多选第一击即守卫() {
        val list = fixedList("SELECT 1;", "SELECT 2;", "SELECT 3;")
        val cp = SqlClickPreview(project, list) { i -> withTerminator(list.model.getElementAt(i).restoredSql) }
        try {
            // 多选第一击：无修饰键单击会排程，Ctrl/Shift 单击一律不排程（真实转发含 BUTTON1 位）
            cp.onClick(1, Point(5, 10), MouseEvent.CTRL_DOWN_MASK or MouseEvent.BUTTON1_DOWN_MASK)
            assertFalse("Ctrl 单击（多选第一击）不应排程预览", cp.isScheduled)
            assertNull("Ctrl 单击不应弹窗", cp.currentPopup)

            cp.onClick(1, Point(5, 30), MouseEvent.SHIFT_DOWN_MASK or MouseEvent.BUTTON1_DOWN_MASK)
            assertFalse("Shift 单击（范围选择）不应排程预览", cp.isScheduled)

            // 已有单选时 Ctrl 单击（toggle 到第二行）同样不排程——抢焦点只发生在这一击
            list.selectedIndex = 0
            cp.onClick(1, Point(5, 30), MouseEvent.CTRL_DOWN_MASK or MouseEvent.BUTTON1_DOWN_MASK)
            assertFalse("已有单选时 Ctrl 单击仍不应排程", cp.isScheduled)

            cp.onClick(1, Point(5, 10))
            assertTrue("无修饰键单击仍应排程预览（对照）", cp.isScheduled)
        } finally {
            cp.dispose()
        }
    }

    fun test带修饰键单击停掉已排程的预览() {
        val list = fixedList("SELECT 1;", "SELECT 2;")
        val cp = SqlClickPreview(project, list) { i -> withTerminator(list.model.getElementAt(i).restoredSql) }
        try {
            cp.onClick(1, Point(5, 10)) // 无修饰键单击：排程
            assertTrue(cp.isScheduled)
            cp.onClick(1, Point(5, 30), MouseEvent.CTRL_DOWN_MASK or MouseEvent.BUTTON1_DOWN_MASK) // 随即 Ctrl 单击另一行
            assertFalse("带修饰键单击应停掉已排程预览并关旧", cp.isScheduled)
            assertNull(cp.currentPopup)
        } finally {
            cp.dispose()
        }
    }

    fun test编辑器CtrlC无选中复制全文() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = {},
        )
        try {
            // 复制前先抓按钮引用：flashCopied 会把 tooltip 短暂换成「已复制」，复制后按 tooltip 查找必落空
            val copyButton = findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
            dispatchEditorCopy(content.editor)
            assertEquals("浮层编辑器 Ctrl+C 无选中应复制全文（与头部按钮同源）", "SELECT 1;", copied)
            assertEquals(
                "Ctrl+C 复制后按钮图标应换对号（与按钮复制同反馈）",
                AllIcons.Actions.Checked,
                copyButton.icon,
            )
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test编辑器CtrlC复制用户编辑后的全文() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = {},
        )
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                content.editor.document.setText("SELECT edited_price FROM user;")
            }
            dispatchEditorCopy(content.editor)
            assertEquals("Ctrl+C 应取编辑后文本（与按钮复制同源）", "SELECT edited_price FROM user;", copied)
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test编辑器CtrlC有选中时交原生复制不全文覆盖() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT * FROM user WHERE id = 1;",
            copyText = { copied = it },
            close = {},
        )
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                content.editor.selectionModel.setSelection(0, 6) // 选中 "SELECT"
            }
            dispatchEditorCopy(content.editor)
            assertNull("有选中时不应走全文复制回调（交编辑器原生复制选中片段）", copied)
        } finally {
            releaseEditor(content.editor)
        }
    }

    /**
     * 真实复制链路的**平台调用点**（T-ClickPreviewCopyRestore 二轮）：keymap Ctrl+C 绑 `$Copy`
     * （IdeActions.ACTION_COPY），该 action 从 DataContext 取 `COPY_PROVIDER` 后调
     * `provider.performCopy(ctx)` —— 编辑器的 provider 即 `EditorEx.copyProvider`
     * （243 源码核验：`EditorImpl.MyEditable.performCopy` → `executeAction(EditorCopy)`）。
     * 本用例走这一调用点、并注入生产的真实复制实现（`UiTextClipboard.copy`），
     * 与「直调 handler、注入空回调」的用例相比更贴近用户按键路径。
     *
     * headless 边界：`DataManager.getDataContext(component)` 在无 UI 挂载时取不到 EDITOR/
     * COPY_PROVIDER（实测均为 null），`$Copy.actionPerformed` 那条外部路径无法在 headless 复现
     * ——真实按键行为由 GUI 走查覆盖（计划 §三 S4）。
     */
    fun test编辑器provider直调_无选中_剪贴板得全文() {
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { UiTextClipboard.copy(it) }, // 生产实现：弹窗路径注入的就是它
            close = {},
        )
        try {
            CopyPasteManager.getInstance().setContents(StringSelection("__SENTINEL__"))
            val context = DataManager.getInstance().getDataContext(content.editor.component)
            val provider = (content.editor as EditorEx).copyProvider
            assertTrue("编辑器应提供复制能力", provider.isCopyEnabled(context))
            provider.performCopy(context)
            val clip: String? =
                CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
            assertEquals("无选中时经编辑器 provider 复制应得整条 SQL", "SELECT 1;", clip)
        } finally {
            releaseEditor(content.editor)
        }
    }

    /**
     * 二轮修复（真机反馈后）：**Swing 绑定层**的 Ctrl+C —— 编辑器组件（JTextComponent）自带
     * 的 copy 对无选中是 no-op，而平台 `$Copy` 在编辑器里因取不到 COPY_PROVIDER 不可用，
     * 事件最终落回这一层。断言的正是真实按键解析的落点（InputMap → ActionMap）。
     */
    fun test编辑器CtrlC绑定覆盖默认copy_无选中复制全文() {
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { UiTextClipboard.copy(it) },
            close = {},
        )
        try {
            val comp = content.editor.component
            val actionKey = comp.inputMap.get(javax.swing.KeyStroke.getKeyStroke("control C")) as String
            assertNotEquals("Ctrl+C 应覆盖 JTextComponent 默认 copy 绑定", "copy", actionKey)
            assertEquals("Ctrl+Insert 应与 Ctrl+C 同绑",
                actionKey,
                comp.inputMap.get(javax.swing.KeyStroke.getKeyStroke("control INSERT")))
            CopyPasteManager.getInstance().setContents(StringSelection("__SENTINEL__"))
            comp.actionMap.get(actionKey)
                .actionPerformed(java.awt.event.ActionEvent(comp, java.awt.event.ActionEvent.ACTION_PERFORMED, actionKey))
            val clip: String? = CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
            assertEquals("无选中按 Ctrl+C 应复制全文", "SELECT 1;", clip)
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test编辑器CtrlC有选中只复制选区() {
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT * FROM user WHERE id = 1;",
            copyText = { UiTextClipboard.copy(it) },
            close = {},
        )
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                content.editor.selectionModel.setSelection(0, 6) // "SELECT"
            }
            val comp = content.editor.component
            val actionKey = comp.inputMap.get(javax.swing.KeyStroke.getKeyStroke("control C")) as String
            CopyPasteManager.getInstance().setContents(StringSelection("__SENTINEL__"))
            comp.actionMap.get(actionKey)
                .actionPerformed(java.awt.event.ActionEvent(comp, java.awt.event.ActionEvent.ACTION_PERFORMED, actionKey))
            val clip: String? = CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
            assertEquals("有选中按 Ctrl+C 应只复制选区片段", "SELECT", clip)
        } finally {
            releaseEditor(content.editor)
        }
    }

    /**
     * 列表侧契约钉子：Ctrl+C 绑定**来自平台 LAF**（`List.focusInputMap`），触发后走 JBList 既有
     * copy 语义（ActionMap["copy"] → `doCopyToClipboardAction` → 选中条目逐条 `itemToText` 换行拼接）。
     * 钉住「列表 Ctrl+C 复制纯 SQL」这条用户依赖的既有行为，**不是**二轮修复新增（二轮曾在此加绑定，
     * 反向验证发现摘除后绑定仍在＝冗余，已回退）。
     */
    fun test列表CtrlC绑定在场_多选批量复制纯SQL() {
        val model = DefaultListModel<LogSqlEntry>()
        model.addElement(entry("SELECT 1;"))
        model.addElement(entry("SELECT 2;"))
        val list = SqlEntryList(model)
        list.fixedCellHeight = 20
        list.setSize(200, 100)
        list.selectedIndices = intArrayOf(0, 1)
        assertEquals("列表 Ctrl+C 应绑定到平台既有 copy 动作",
            "copy",
            list.inputMap.get(javax.swing.KeyStroke.getKeyStroke("control C")))
        assertEquals("列表 Ctrl+Insert 应同绑",
            "copy",
            list.inputMap.get(javax.swing.KeyStroke.getKeyStroke("control INSERT")))
        CopyPasteManager.getInstance().setContents(StringSelection("__SENTINEL__"))
        list.actionMap.get("copy")
            .actionPerformed(java.awt.event.ActionEvent(list, java.awt.event.ActionEvent.ACTION_PERFORMED, "copy"))
        val clip: String? = CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
        assertEquals("多选列表 Ctrl+C 应复制逐行拼接的纯 SQL", "SELECT 1;\nSELECT 2;", clip)
    }

    fun testClickPreviewSql关闭格式化为原文加分号() {
        assertEquals("SELECT 1;", clickPreviewSql("SELECT 1", formatSql = false))
        assertEquals("已有分号不重复", "SELECT 1;", clickPreviewSql("SELECT 1;", formatSql = false))
        assertEquals("空 SQL 不追加分号", "", clickPreviewSql("", formatSql = false))
    }

    fun testClickPreviewSql开启格式化为多行加缩进() {
        val formatted = clickPreviewSql("SELECT t.id,t.name FROM t WHERE t.id = 1", formatSql = true)
        assertTrue("开启设置时应多行格式化", formatted.contains('\n'))
        assertTrue("分号收尾", formatted.endsWith(";"))
        assertTrue("关键字换行（FROM 独立成行）", formatted.contains("FROM"))
    }

    // ---------- 三面板接线 ----------

    fun testSqlLogPanel空列表单击不排程_双击关预览() {
        val manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
        val panel = SqlLogPanel(project, manager)
        try {
            val cp = panel.clickPreviewForTest
            cp.onClick(1, Point(5, 5))
            assertFalse("无行可命中时单击不应排程预览", cp.isScheduled)
            assertNull(cp.currentPopup)
            cp.onClick(2, Point(5, 5))
            assertFalse(cp.isScheduled)
            assertNull(cp.currentPopup)
        } finally {
            panel.dispose()
        }
    }

    fun testSqlLogPanelDispose后定时器停止() {
        val manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
        val panel = SqlLogPanel(project, manager)
        panel.clickPreviewForTest.onClick(1, Point(5, 5))
        panel.dispose()
        assertFalse("面板销毁应停掉预览定时器（防泄漏）", panel.clickPreviewForTest.isScheduled)
    }

    fun testHistorySqlLogPanel空列表单击不排程_双击关预览() {
        val panel = HistorySqlLogPanel(project)
        try {
            val cp = panel.sqlClickPreviewForTest
            cp.onClick(1, Point(5, 5))
            assertFalse(cp.isScheduled)
            assertNull(cp.currentPopup)
            cp.onClick(2, Point(5, 5))
            assertFalse(cp.isScheduled)
            assertNull(cp.currentPopup)
        } finally {
            panel.dispose()
        }
    }

    // ---------- T-AutoFold F4-b：buildContent 程序性折叠 ----------

    private fun withAutoFold(enabled: Boolean, block: () -> Unit) {
        val state = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        val oldEnabled = state.autoFoldEnabled
        val oldThreshold = state.autoFoldFieldThreshold
        try {
            state.autoFoldEnabled = enabled
            state.autoFoldFieldThreshold = 3
            block()
        } finally {
            state.autoFoldEnabled = oldEnabled
            state.autoFoldFieldThreshold = oldThreshold
        }
    }

    private fun ownFoldRegions(editor: Editor) =
        editor.foldingModel.allFoldRegions.filter { it.placeholderText.startsWith("…") }

    fun test开启自动折叠后弹窗内容含折叠区域() {
        withAutoFold(enabled = true) {
            val content = SqlClickPreview.buildContent(
                project,
                "SELECT\n    a1,\n    a2,\n    a3,\n    a4\nFROM t",
                copyText = {},
                close = {},
            )
            try {
                val regions = ownFoldRegions(content.editor)
                assertEquals("开启态应有一个折叠区域", 1, regions.size)
                assertFalse("区域应收起", regions[0].isExpanded)
                assertTrue("开启态应补设折叠轮廓", content.editor.settings.isFoldingOutlineShown)
            } finally {
                releaseEditor(content.editor)
            }
        }
    }

    fun test关闭自动折叠后弹窗内容零区域且无轮廓() {
        withAutoFold(enabled = false) {
            val content = SqlClickPreview.buildContent(
                project,
                "SELECT\n    a1,\n    a2,\n    a3,\n    a4\nFROM t",
                copyText = {},
                close = {},
            )
            try {
                assertEquals("关闭态零折叠区域（紧凑外观逐字节不变）", 0, ownFoldRegions(content.editor).size)
                assertFalse("关闭态不补设折叠轮廓", content.editor.settings.isFoldingOutlineShown)
            } finally {
                releaseEditor(content.editor)
            }
        }
    }
}
