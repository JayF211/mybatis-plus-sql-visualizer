package cn.song.mybatisplus.visualizer.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * P-05.1：[ParamImportDialog] 行为测试。
 *
 * 覆盖弹窗核心逻辑：确定回调传原文 / 空输入不回调 / 格式化 / 清除 / 预填。
 * 注意：每个测试创建的 dialog 必须 dispose 以释放 Editor。
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
    // 空输入 / 空白输入不回调
    // ------------------------------------------------------------------

    fun testDoOKAction_emptyInputDoesNotCallback() {
        // 不输入 → 确定 → onConfirm 不被调用
        var called = false
        val dialog = ParamImportDialog(project) { called = true }
        dialog.doOKAction()
        assertFalse("空输入不应触发回调", called)
        dialog.dispose()
    }

    fun testDoOKAction_blankInputDoesNotCallback() {
        // 仅空白字符 → 确定 → onConfirm 不被调用
        var called = false
        val dialog = ParamImportDialog(project) { called = true }
        dialog.setTextForTest("   \n  ")
        dialog.doOKAction()
        assertFalse("空白输入不应触发回调", called)
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
    // 弹窗尺寸常量
    // ------------------------------------------------------------------

    fun testDialogDimensions() {
        assertEquals("弹窗宽度", 480, ParamImportDialog.DIALOG_WIDTH)
        assertEquals("弹窗高度", 200, ParamImportDialog.DIALOG_HEIGHT)
    }
}
