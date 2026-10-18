package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.psi.EntityClassFinder
import com.intellij.openapi.application.ReadAction
import org.junit.Assert.assertTrue
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

    fun testScanLibrariesRunsLibrarySweep() {
        entityClass("LegacyBooking")
        EntityClassFinder.clearLibrarySweepCache(project)
        val dialog = EntitySelectionDialog(project, listOf(
            EntitySelectionDialog.TableRow(EntitySelectionDialog.MAIN_KEY, "主表 t_booking", emptyList(), "t_booking"),
        )) {}
        // 不能包 ReadAction，否则会把分片扫描重新变成整趟持锁。
        dialog.refreshCandidatesFromLibraries()
        assertTrue("「在依赖库中查找」应真的跑过一次库扫描（登记位被写上）",
            EntityClassFinder.sweepDispatchedForTest(project))
    }

    fun testScanResultRefreshesRowCandidates() {
        val legacy = entityClass("LegacyBooking")
        val dialog = EntitySelectionDialog(project, listOf(
            EntitySelectionDialog.TableRow(EntitySelectionDialog.MAIN_KEY, "主表 t_booking", emptyList(), "t_booking"),
        )) {}
        EntityClassFinder.seedLibrarySweepForTest(project, "t_booking", legacy)
        val refreshed = ReadAction.compute<List<EntitySelectionDialog.TableRow>, RuntimeException> {
            dialog.resolveCandidatesFromFinder()
        }
        assertEquals("重解析后该表应拿到新候选", 1, refreshed[0].candidates.size)
        assertEquals("LegacyBooking", refreshed[0].candidates[0].name)
        assertEquals("重建候选必须保留 SQL 表名", "t_booking", refreshed[0].tableName)
    }

    fun testScanWithoutResultKeepsCandidatesEmpty() {
        entityClass("LegacyBooking")
        val dialog = EntitySelectionDialog(project, listOf(
            EntitySelectionDialog.TableRow(EntitySelectionDialog.MAIN_KEY, "主表 t_booking", emptyList(), "t_booking"),
        )) {}
        EntityClassFinder.clearLibrarySweepCache(project)
        EntityClassFinder.sweepEnabledForTest = false
        try {
            val refreshed = ReadAction.compute<List<EntitySelectionDialog.TableRow>, RuntimeException> {
                dialog.resolveCandidatesFromFinder()
            }
            assertTrue("无库扫描结果时候选应保持为空", refreshed[0].candidates.isEmpty())
        } finally {
            EntityClassFinder.sweepEnabledForTest = null
        }
    }

    fun testLegacyRowDefaultAndWithCandidatesPreserveMetadata() {
        val clazz = entityClass("LegacyBooking")
        val display = EntitySelectionDialog.TableRow("display", "展示", emptyList())
        assertNull("旧三参数构造保留默认 null", display.tableName)
        val row = EntitySelectionDialog.TableRow("join", "联表", emptyList(), "t_booking")
        val copied = row.withCandidates(listOf(clazz))
        assertEquals("join", copied.key)
        assertEquals("联表", copied.label)
        assertEquals("t_booking", copied.tableName)
        assertEquals(listOf(clazz), copied.candidates)
        assertTrue("新字段必须参加值相等判定", row != EntitySelectionDialog.TableRow("join", "联表", emptyList(), null))
    }
}
