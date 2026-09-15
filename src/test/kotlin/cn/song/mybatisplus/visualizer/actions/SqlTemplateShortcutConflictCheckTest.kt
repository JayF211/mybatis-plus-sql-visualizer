package cn.song.mybatisplus.visualizer.actions

import com.intellij.openapi.actionSystem.KeyboardShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import javax.swing.KeyStroke

/**
 * T-PreviewUx-Pack #3：快捷模板默认键位冲突检测纯逻辑测试。
 *
 * Keymap 以动态代理桩模拟（仅实现 getActionIds/getShortcuts/addShortcut 三个反射入口，
 * 与生产实际调用的 API 面一致）：
 *  - conflictOwner：占用者识别（排除自身）；
 *  - fallbackOf：备选键位推导（叠加 Ctrl+Alt）；
 *  - resolveConflictLine：冲突 → 换绑成功/失败两种文案分支。
 */
class SqlTemplateShortcutConflictCheckTest {

    /** 记录 addShortcut 调用的 keymap 桩。bindings: actionId → shortcuts；addedShortcuts: 换绑记录。 */
    private class KeymapStubBuilder(val bindings: MutableMap<String, MutableList<KeyboardShortcut>>) {
        val addedShortcuts = mutableListOf<Pair<String, KeyboardShortcut>>()
        var allowAdd = true

        fun proxy(): Any = Proxy.newProxyInstance(
            KeymapStubBuilder::class.java.classLoader,
            arrayOf(com.intellij.openapi.keymap.Keymap::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getActionIds" -> bindings.keys.toTypedArray()
                "getShortcuts" -> {
                    val list = bindings[args?.get(0) as? String] ?: emptyList<KeyboardShortcut>()
                    // 用 Arrays.copyOf 生成真正 Shortcut[]（避免数组协变 cast 的 CCE）
                    java.util.Arrays.copyOf(
                        list.toTypedArray(),
                        list.size,
                        Array<com.intellij.openapi.actionSystem.Shortcut>::class.java,
                    )
                }
                "addShortcut" -> {
                    if (!allowAdd) throw IllegalStateException("scheme is not editable")
                    addedShortcuts += (args?.get(0) as String) to (args[1] as KeyboardShortcut)
                    Unit
                }
                else -> defaultReturn(method.returnType)
            }
        }

        fun defaultReturn(type: Class<*>): Any? = when {
            type == Boolean::class.javaPrimitiveType -> false
            else -> null
        }
    }

    private fun shortcut(keyCode: Int, mods: Int): KeyboardShortcut =
        KeyboardShortcut(KeyStroke.getKeyStroke(keyCode, mods), null)

    private fun altShift(keyCode: Int): KeyboardShortcut = shortcut(keyCode, InputEvent.ALT_MASK or InputEvent.SHIFT_MASK)

    private fun ctrlAltShift(keyCode: Int): KeyboardShortcut =
        shortcut(keyCode, InputEvent.CTRL_MASK or InputEvent.ALT_MASK or InputEvent.SHIFT_MASK)

    @Test
    fun test无冲突返回null() {
        val stub = KeymapStubBuilder(mutableMapOf("ours" to mutableListOf(altShift(KeyEvent.VK_E))))
        val keymap = stub.proxy() as com.intellij.openapi.keymap.Keymap
        assertNull(
            SqlTemplateShortcutConflictCheck.conflictOwner(
                keymap, "ours", altShift(KeyEvent.VK_E),
            ),
        )
    }

    @Test
    fun test占用者识别且排除自身() {
        val e = altShift(KeyEvent.VK_E)
        val stub = KeymapStubBuilder(
            mutableMapOf(
                "ours" to mutableListOf(e),
                "some.other.Action" to mutableListOf(e),
            ),
        )
        val keymap = stub.proxy() as com.intellij.openapi.keymap.Keymap
        assertEquals("some.other.Action", SqlTemplateShortcutConflictCheck.conflictOwner(keymap, "ours", e))
    }

    @Test
    fun test备选键位推导叠加CtrlAlt() {
        val fallback = SqlTemplateShortcutConflictCheck.fallbackOf(altShift(KeyEvent.VK_E))
        assertNotNull(fallback)
        assertEquals(ctrlAltShift(KeyEvent.VK_E).firstKeyStroke, fallback!!.firstKeyStroke)
    }

    @Test
    fun test冲突行换绑成功与失败两分支() {
        val e = altShift(KeyEvent.VK_E)
        val actionId = "cn.song.mybatisplus.visualizer.ExplainSelectedTable"
        // 占用者存在且备选键位空闲 → 换绑成功文案
        val stub = KeymapStubBuilder(
            mutableMapOf(
                actionId to mutableListOf(e),
                "other.Action" to mutableListOf(e),
            ),
        )
        val line = SqlTemplateShortcutConflictCheck.resolveConflictLine(
            stub.proxy() as com.intellij.openapi.keymap.Keymap, actionId,
        )
        assertNotNull("冲突应产出提示行", line)
        assertEquals(1, stub.addedShortcuts.size)
        assertEquals(actionId, stub.addedShortcuts[0].first)
        // 备选键位也被占用 → 不换绑（manual 分支）
        val ctrlAltShiftE = ctrlAltShift(KeyEvent.VK_E)
        val stub2 = KeymapStubBuilder(
            mutableMapOf(
                actionId to mutableListOf(e),
                "other.Action" to mutableListOf(e, ctrlAltShiftE),
            ),
        )
        val line2 = SqlTemplateShortcutConflictCheck.resolveConflictLine(
            stub2.proxy() as com.intellij.openapi.keymap.Keymap, actionId,
        )
        assertNotNull(line2)
        assertEquals(0, stub2.addedShortcuts.size)
        // addShortcut 抛异常（scheme 不可写）→ 不换绑（manual 分支），不外抛
        val stub3 = KeymapStubBuilder(
            mutableMapOf(
                actionId to mutableListOf(e),
                "other.Action" to mutableListOf(e),
            ),
        )
        stub3.allowAdd = false
        val line3 = SqlTemplateShortcutConflictCheck.resolveConflictLine(
            stub3.proxy() as com.intellij.openapi.keymap.Keymap, actionId,
        )
        assertNotNull(line3)
    }

    @Test
    fun test键位无绑定的动作返回null() {
        val stub = KeymapStubBuilder(mutableMapOf("other.Action" to mutableListOf(altShift(KeyEvent.VK_E))))
        val line = SqlTemplateShortcutConflictCheck.resolveConflictLine(
            stub.proxy() as com.intellij.openapi.keymap.Keymap,
            "not.registered.action",
        )
        assertNull("无绑定（未注册/环境缺 keymap 项）→ 静默", line)
    }
}
