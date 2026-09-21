package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * P-05.1：[ParamImportDialog] 行为测试。
 *
 * 覆盖弹窗核心逻辑：确定回调传原文 / 空输入也回调 / 格式化 / 清除 / 预填。
 * 注意：每个测试创建的 dialog 必须 dispose 以释放 Editor。
 *
 * P13（2026-09-20）：`doOKAction` 的 `isNotBlank` 守卫已去掉，空/空白输入**改为回调**——
 * 原「空输入不回调」两条用例编码的是被删掉的旧契约，故同步改写为新契约（不是放宽断言：
 * 真机走查实证旧契约导致「留空点确定」零反馈，属缺陷本身）。末尾一条用例进一步走通
 * 「对话框确定 → 解析 → 行内提示」全链，堵住「单测绿但真机走不通」的缺口——此前 P10 回归
 * 用例（SqlPreviewPanelMetaHintTooltipTest）直接调 `importParamsForTest(emptyMap())`，
 * 绕过了对话框这一环。
 */
class ParamImportDialogTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 确定回调
    // ------------------------------------------------------------------

    fun testDoOKAction_callsOnConfirmWithText() {
        // 输入文本 → 确定 → onConfirm 收到原文
        var received: String? = null
        val dialog = ParamImportDialog(project) { received = it }
        dialog.setTextForTest("name=张三")
        dialog.doOKAction()
        assertEquals("确定应回调原文", "name=张三", received)
        dialog.dispose()
    }

    fun testDoOKAction_jsonInput() {
        // JSON 文本 → 确定 → 回调原文（非解析后 map）
        var received: String? = null
        val dialog = ParamImportDialog(project) { received = it }
        dialog.setTextForTest("""{"name":"张三","age":28}""")
        dialog.doOKAction()
        assertEquals("确定应回调 JSON 原文", """{"name":"张三","age":28}""", received)
        dialog.dispose()
    }

    // ------------------------------------------------------------------
    // 空输入 / 空白输入也回调（P13 新契约）
    // ------------------------------------------------------------------

    fun testDoOKAction_emptyInputStillCallbacks() {
        // P13：不输入 → 确定 → onConfirm 仍被调用，且收到空串
        var received: String? = null
        var called = false
        val dialog = ParamImportDialog(project) {
            called = true
            received = it
        }
        dialog.doOKAction()
        assertTrue("P13：空输入也必须回调（否则留空点确定界面零反馈）", called)
        assertEquals("P13：回调应收到空串原文（由下游解析得空 map 并给出提示）", "", received)
        dialog.dispose()
    }

    fun testDoOKAction_blankInputStillCallbacks() {
        // P13：仅空白字符 → 确定 → onConfirm 仍被调用，且收到原文（空白由下游解析归一）
        var received: String? = null
        var called = false
        val dialog = ParamImportDialog(project) {
            called = true
            received = it
        }
        dialog.setTextForTest("   \n  ")
        dialog.doOKAction()
        assertTrue("P13：空白输入也必须回调", called)
        assertEquals("P13：回调应收到空白原文", "   \n  ", received)
        dialog.dispose()
    }

    // ------------------------------------------------------------------
    // 预填文本
    // ------------------------------------------------------------------

    fun testPreFill_showsInEditor() {
        // preFill 非空 → 编辑器显示预填文本
        val dialog = ParamImportDialog(project, "name=张三\nage=28") {}
        assertEquals("预填文本应显示在编辑器", "name=张三\nage=28", dialog.textForTest())
        dialog.dispose()
    }

    fun testPreFill_null_emptyEditor() {
        // preFill 为 null → 编辑器为空
        val dialog = ParamImportDialog(project, null) {}
        assertEquals("preFill 为 null 时编辑器应为空", "", dialog.textForTest())
        dialog.dispose()
    }

    fun testPreFill_defaultEmpty() {
        // 不传 preFill（默认 null）→ 编辑器为空
        val dialog = ParamImportDialog(project) {}
        assertEquals("默认 preFill 应为空", "", dialog.textForTest())
        dialog.dispose()
    }

    // ------------------------------------------------------------------
    // 格式化 / 清除按钮
    // ------------------------------------------------------------------

    fun testFormat_json_prettyPrint() {
        // JSON 单行 → 格式化后应有多行（含换行缩进）
        val dialog = ParamImportDialog(project) {}
        dialog.setTextForTest("""{"name":"张三","age":28}""")
        dialog.triggerFormatForTest()
        val result = dialog.textForTest()
        assertTrue("格式化后应含换行", result.contains("\n"))
        assertTrue("格式化后应含缩进", result.contains("  \"name\""))
        dialog.dispose()
    }

    fun testFormat_nonJson_normalizes() {
        // 非 JSON 文本 → 格式化后去空行、行级 trim
        val dialog = ParamImportDialog(project) {}
        dialog.setTextForTest("  name=张三  \n\n  age=28  \n")
        dialog.triggerFormatForTest()
        assertEquals("非 JSON 应归一为 trim 去空行", "name=张三\nage=28", dialog.textForTest())
        dialog.dispose()
    }

    fun testFormat_empty_noChange() {
        // 空输入 → 格式化不动作
        val dialog = ParamImportDialog(project) {}
        dialog.setTextForTest("")
        dialog.triggerFormatForTest()
        assertEquals("空输入格式化后仍为空", "", dialog.textForTest())
        dialog.dispose()
    }

    fun testClear_emptiesEditor() {
        // 清除按钮 → 编辑器清空
        val dialog = ParamImportDialog(project) {}
        dialog.setTextForTest("name=张三")
        dialog.triggerClearForTest()
        assertEquals("清除后应为空", "", dialog.textForTest())
        dialog.dispose()
    }

    fun testFormat_jsonWithBooleanAndNumber() {
        // JSON 含布尔和数值 → 所有值统一加引号（参数本质都是字符串）
        val dialog = ParamImportDialog(project) {}
        dialog.setTextForTest("""{"active":true,"count":10}""")
        dialog.triggerFormatForTest()
        val result = dialog.textForTest()
        assertTrue("true 应加引号", result.contains("\"true\""))
        assertTrue("10 应加引号", result.contains("\"10\""))
        dialog.dispose()
    }

    // ------------------------------------------------------------------
    // P13：空提交 → 对话框确定 → 行内提示（堵「绕过对话框」的测试缺口）
    // ------------------------------------------------------------------

    /**
     * 真机路径复刻：预览面板「更多 → 导入参数」→ 对话框留空 → 点确定 → 「未识别到有效参数」行内提示。
     *
     * 与既有 P10 回归用例（[SqlPreviewPanelMetaHintTooltipTest]）的差别：那条直接调
     * `importParamsForTest(emptyMap())`，**跳过了对话框这一环**，所以「单测绿」不代表真机那一步
     * 走得通（真机走查实测：旧守卫把空提交吞掉，界面上什么都没发生）。本条从对话框的 `doOKAction()`
     * 起走完整链——确定 → 回调原文 → `ParamImportParser.parse` → `importParams` →
     * `showEmptyClipboardHint`——因此「对话框吞掉空提交」会让本用例转红。
     *
     * K2J 互操作：k2j 侧 `ParamImportParser` 是 Java 承载的 Kotlin object（实例方法 `parse`），
     * master 的 `ParamImportParser.parse(input)` → `ParamImportParser.INSTANCE.parse(input)`；
     * `PreviewResult` 的具名实参 → Java 7 参便捷构造器位置实参。
     */
    fun test空提交_经对话框确定触发未识别参数行内提示() {
        val panel = SqlPreviewPanel(project)
        val dialog = ParamImportDialog(project) { input ->
            panel.importParams(ParamImportParser.INSTANCE.parse(input))
        }
        try {
            panel.showResult(
                PreviewResult(
                    "SELECT id\nFROM user\nWHERE username = ?",
                    "SELECT id FROM user WHERE username = ?",
                    listOf("username"),
                    null,
                    WrapperType.LAMBDA_QUERY,
                    "user",
                    listOf("id", "username"),
                ),
            )
            UIUtil.dispatchAllInvocationEvents()

            // 留空（不 setText）直接点确定——即真机走查的操作
            dialog.doOKAction()
            UIUtil.dispatchAllInvocationEvents()

            assertEquals(
                "留空点确定应出现「未识别到有效参数」行内提示（P13 修复点）",
                "⚠ ${MpSqlVisualizerBundle.message("panel.param.import.result.empty")}",
                panel.metaLabelForTest(),
            )
        } finally {
            dialog.dispose()
            panel.clear()
        }
    }

    // ------------------------------------------------------------------
    // 弹窗尺寸常量
    // ------------------------------------------------------------------

    fun testDialogDimensions() {
        assertEquals("弹窗宽度", 480, ParamImportDialog.DIALOG_WIDTH)
        assertEquals("弹窗高度", 200, ParamImportDialog.DIALOG_HEIGHT)
    }

    // ------------------------------------------------------------------
    // N3：文档监听随对话框 disposable 处置
    // ------------------------------------------------------------------

    /**
     * N3 回归守护（2026-09-21）：文档监听必须**随对话框自己的 disposable 一并处置**。
     *
     * 缺陷形态：监听此前挂在 `this`（`DialogWrapper` 作为 `Disposable` 的**另一个**节点）上，而平台
     * `close()` 处置的是 `myDisposable` → 那个节点从没进过 Disposer 树，于是以 `ROOT_DISPOSABLE`
     * 子节点身份留到应用退出，被 ObjectTree 报 `Memory leak detected: 'ParamImportDialog@…' … wasn't
     * disposed`（真机复现：开 3 次该对话框 → 退出时报 3 个实例泄漏，**实例数与打开次数严格相等**）。
     *
     * 判据用**外部文档 + 注入计数器**：直接观察对话框自己的占位标签没有判别力——`dispose()` 会把
     * `editor` 置 null，占位标签断言会恒真。故把监听挂到一张测试文档上，处置 disposable 后改文档，
     * 断言回调**不再触发**；并带一条处置前的正对照，证明该监听本来就会响（防「压根没挂上」的空绿）。
     */
    fun test文档监听随对话框disposable处置_处置后不再响应() {
        val dialog = ParamImportDialog(project) {}
        val scratch = com.intellij.openapi.editor.impl.DocumentImpl("")
        var fired = 0
        dialog.attachPlaceholderListener(scratch) { fired++ }

        // 正对照：处置前文档变化必须触发监听（否则本用例会因为「没挂上」而假绿）
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { scratch.setText("first") }
        assertEquals("前提：处置前监听应响应文档变化", 1, fired)

        // 模拟平台 close() 的处置路径：DialogWrapper.close() 走的就是 Disposer.dispose(getDisposable())
        Disposer.dispose(dialog.disposable)

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { scratch.setText("second") }
        assertEquals(
            "disposable 被处置后监听必须一并移除（N3 回归点）——若仍挂在 this 上，这里会变成 2",
            1,
            fired,
        )
        dialog.dispose()
    }
}
