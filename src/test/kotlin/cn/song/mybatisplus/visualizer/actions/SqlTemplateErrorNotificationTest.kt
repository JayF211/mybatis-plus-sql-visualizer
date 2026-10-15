package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-SqlTemplateUx P2：快捷模板失败反馈的富入口测试（纯逻辑为主，无真机依赖）。
 *
 * 覆盖判据：
 *  1. 有锚点 → 挂「定位到出错语句」+「复制错误信息」两动作（与预览面板同款）；
 *  2. 无锚点 → 退化为只挂「复制错误信息」（不虚标可点的定位）；
 *  3. 复制动作确实把错误文本写进剪贴板；定位动作确实执行了锚点跳转回调；
 *  4. 通知接线把上述动作真的挂到气泡上、文案与面板同 key；
 *  5. 锚点解析取编辑器选区起始行、编辑器缺失返回 null。
 *
 * 文案复用面板既有 key（`execute.error.locate` / `panel.copyError`），断言据此比对，
 * 保证两条入口（预览面板 / 快捷模板动作）用户可见的措辞一致。
 */
class SqlTemplateErrorNotificationTest : BasePlatformTestCase() {

    fun `test P2_有锚点_挂定位与复制两动作`() {
        val actions = sqlTemplateErrorActions(locate = {}, copy = {})
        assertEquals(2, actions.size)
        assertEquals("execute.error.locate", actions[0].titleKey)
        assertEquals("panel.copyError", actions[1].titleKey)
    }

    fun `test P2_无锚点_退化为只挂复制`() {
        val actions = sqlTemplateErrorActions(locate = null, copy = {})
        assertEquals(1, actions.size)
        assertEquals("panel.copyError", actions.single().titleKey)
    }

    fun `test P2_复制动作写入剪贴板`() {
        val msg = "Table 'mpj_demo.t_order' doesn't exist"
        val copyAction = sqlTemplateErrorActions(locate = null) { UiTextClipboard.copy(msg) }.single()
        copyAction.run()
        assertEquals(msg, UiTextClipboard.readText())
    }

    fun `test P2_定位动作执行锚点跳转回调`() {
        var jumped = false
        val locateAction = sqlTemplateErrorActions(locate = { jumped = true }, copy = {}).first()
        locateAction.run()
        assertTrue("定位动作点击应触发锚点跳转回调", jumped)
    }

    fun `test P2_通知接线_动作数量与文案与面板一致`() {
        val notification = buildSqlTemplateErrorNotification(
            errorMsg = "Table 'mpj_demo.t_order' doesn't exist",
            locate = {},
            copy = {},
        )
        assertEquals(2, notification.actions.size)
        assertEquals(
            MpSqlVisualizerBundle.message("execute.error.locate"),
            notification.actions[0].templatePresentation.text,
        )
        assertEquals(
            MpSqlVisualizerBundle.message("panel.copyError"),
            notification.actions[1].templatePresentation.text,
        )
    }

    fun testRichNotificationContainsAndCopiesFullDriverMetadata() {
        val driver = java.sql.SQLException("Syntax <bad> & detail\nPosition: 12", "42601", 932)
        val info = cn.song.mybatisplus.visualizer.execute.ErrorPositionExtractor.buildInfo(driver, driver.message!!, 80)
        val expected = cn.song.mybatisplus.visualizer.execute.ExecutionErrorFormatter.format(info)
        val notification = buildSqlTemplateErrorNotification(info, null)
        assertEquals(expected, notification.content)
        assertEquals(1, notification.actions.size)
        // NotificationAction 的回调需平台事件；复制共用的捕获文本由此通知构造入口检验。
        val withLocate = buildSqlTemplateErrorNotification(info, {})
        assertEquals(2, withLocate.actions.size)
        assertTrue(expected.contains("\nsqlState: 42601\nerrorCode: 932\nerrorPosition: char 12"))
    }

    fun `test P2_锚点解析_取选区起始行`() {
        myFixture.configureByText(
            "OrderMapper.xml",
            "<mapper>\n  SELECT COUNT(*) FROM t_order\n</mapper>\n",
        )
        val editor = myFixture.editor
        // 选中第二行里的表名（1-based 行号 2 → 0-based line0 = 1）
        val lineStart = editor.document.getLineStartOffset(1)
        editor.selectionModel.setSelection(lineStart + 2, lineStart + 9)
        val anchor = resolveErrorLocateAnchor(editor, myFixture.file.virtualFile)
        assertNotNull(anchor)
        assertEquals(1, anchor!!.line0)
    }

    fun `test P2_锚点解析_编辑器缺失返回null`() {
        assertNull(resolveErrorLocateAnchor(null, null))
    }
}
