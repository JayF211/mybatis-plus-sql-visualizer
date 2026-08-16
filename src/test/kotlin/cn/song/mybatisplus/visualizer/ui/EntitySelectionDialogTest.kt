package cn.song.mybatisplus.visualizer.ui

import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * 实体类选择弹窗测试（手动选择实体类：自动推断兜底 + 多表一次多选）。
 *
 * 覆盖弹窗核心纯逻辑（构造渲染/预选/清除/确定回调）；Goto Class 弹窗交互为平台组件，
 * Light 环境不可测（候选实体经 configureByText 构造）。
 */
class EntitySelectionDialogTest : BasePlatformTestCase() {

    private fun entityClass(name: String): PsiClass {
        val file = myFixture.configureByText(
            "$name.java",
            "public class $name { private Long id; }",
        )
        return PsiTreeUtil.findChildOfType(file, PsiClass::class.java)!!
    }

    fun testRowsPreSelectFirstCandidate() {
        val main = entityClass("AccountRoleRelEntity")
        val sub = entityClass("Address")
        val dialog = EntitySelectionDialog(
            project,
            listOf(
                EntitySelectionDialog.TableRow(EntitySelectionDialog.MAIN_KEY, "主表 sys_account_role_rel", listOf(main)),
                EntitySelectionDialog.TableRow("t1", "联表 t1 address", listOf(sub)),
                EntitySelectionDialog.TableRow("t2", "联表 t2 org", emptyList()),
            ),
        ) {}
        assertEquals("自动候选应预选第一个", main, dialog.selectedForTest(EntitySelectionDialog.MAIN_KEY))
        assertEquals("副表自动候选同样预选", sub, dialog.selectedForTest("t1"))
        assertNull("无候选行应保持未指定", dialog.selectedForTest("t2"))
    }

    fun testDoOKActionCollectsSelections() {
        val main = entityClass("AccountRoleRelEntity")
        val sub = entityClass("Address")
        var result: Map<String, PsiClass?>? = null
        val dialog = EntitySelectionDialog(
            project,
            listOf(
                EntitySelectionDialog.TableRow(EntitySelectionDialog.MAIN_KEY, "主表 sys_account_role_rel", listOf(main)),
                EntitySelectionDialog.TableRow("t1", "联表 t1 address", listOf(sub)),
            ),
        ) { result = it }
        dialog.doOKAction()
        assertNotNull("确认应触发回调", result)
        assertEquals("主表行应携带选中实体", main, result!![EntitySelectionDialog.MAIN_KEY])
        assertEquals("副表行应携带选中实体", sub, result!!["t1"])
    }

    fun testClearSelectionFallsBackToNull() {
        val main = entityClass("AccountRoleRelEntity")
        var result: Map<String, PsiClass?>? = null
        val dialog = EntitySelectionDialog(
            project,
            listOf(
                EntitySelectionDialog.TableRow(EntitySelectionDialog.MAIN_KEY, "主表 sys_account_role_rel", listOf(main)),
                EntitySelectionDialog.TableRow("t1", "联表 t1 address", emptyList()),
            ),
        ) { result = it }
        assertEquals("清除前预选自动候选", main, dialog.selectedForTest(EntitySelectionDialog.MAIN_KEY))
        dialog.clearSelectionForTest(EntitySelectionDialog.MAIN_KEY)
        assertNull("清除后该行回落未指定", dialog.selectedForTest(EntitySelectionDialog.MAIN_KEY))
        dialog.doOKAction()
        assertNotNull(result)
        assertNull("清除行回调应为 null（调用方回落字符串列名）", result!![EntitySelectionDialog.MAIN_KEY])
    }
}
