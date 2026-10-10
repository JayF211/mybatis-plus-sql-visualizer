package cn.song.mybatisplus.visualizer.ui.search

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-SearchToggleNativeLook 反向验证（摘除修复必红）：
 * DataProvider 契约——组件必须应答 `SearchSession.KEY`（原生 ToggleRegex/ToggleMatchCase
 * 从 AnActionEvent.getData(SearchSession.KEY) 取会话；不应答 ⇒ 原生开关钮全部禁用，
 * 用户无法翻旗）。两个用例互为反向：
 * - 正向：默认实现应答 KEY 且返回本组件的会话（旗读写同源）；
 * - 反向（摘除修复形态）：getData 恒 null ⇒ 契约破坏（此形态在真实 UI 中即开关钮不可用）。
 *
 * 基类用平台测试件：NativeSearchTextField 构造链经 ActionManager（DefaultActionGroup.addAll），
 * 纯 headless TestCase 无 Application 必炸（20261010 对照实验实证）。
 */
class NativeSearchTextFieldProviderContractTest : BasePlatformTestCase() {

    fun test默认实现应答SearchSessionKey且旗同源() {
        val field = NativeSearchTextField(placeholderText = null, showRegex = true)
        val got = field.getData(com.intellij.find.SearchSession.KEY.name)
        assertTrue("DataProvider 必须应答 SearchSession.KEY（否则原生开关钮禁用）", got != null)
        val session = got as com.intellij.find.SearchSession
        // 旗同源：改会话的 FindModel = 改本组件读的旗
        assertFalse(field.isRegex())
        session.findModel.isRegularExpressions = true
        assertTrue(field.isRegex())
        assertFalse(field.isMatchCase())
        session.findModel.isCaseSensitive = true
        assertTrue(field.isMatchCase())
        // 无关 key 不应答
        assertEquals(null, field.getData("not.a.real.key"))
    }

    fun test摘除修复_DataProvider不再应答KEY_契约破坏() {
        val field = BrokenProviderForRedProbe()
        assertEquals("摘除修复形态：KEY 不应答（原生钮在真实 UI 中即禁用）",
            null, field.getData(com.intellij.find.SearchSession.KEY.name))
    }

    /** 反向标本：getData 恒 null（= 把生产实现摘成 `return null` 的形态）。 */
    private class BrokenProviderForRedProbe : com.intellij.openapi.actionSystem.DataProvider {
        override fun getData(dataId: String): Any? = null
    }
}
