package cn.song.mybatisplus.visualizer.psi

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * [PsiSnapshot] 单测（T-PsiCaptureProtocol T-PCP-01）。
 *
 * 覆盖两层：
 *  - **表驱动**（[PsiSnapshotKt.validateCaptured]，纯函数）：文件不存在 / stamp 变化 / textRange 越界三态各判「失效」，
 *    无变化判「有效」——三态齐全，含边界（区间端点、负偏移、start > end）；
 *  - **真夹具**（EDT 捕获 → 改文档 → 后台校验）：未变更判有效且行号正确、文档变更判
 *    `DOCUMENT_MODIFIED`、文件删除判 `FILE_GONE`。
 *
 * 第三态（越界）只有表驱动覆盖：真夹具里制造「区间越界但 stamp 不变」需要 `Document.setModificationStamp`，
 * 而 [com.intellij.openapi.editor.Document] 接口未暴露该方法（javap 实证：仅 `getModificationStamp`），
 * 不为此改生产代码或走实现类强转——如实登记为表驱动单侧覆盖。
 *
 * k2j 适配：顶层函数落在门面类 `PsiSnapshotKt`（同包不自动可见，须显式限定）；Java 静态方法无具名
 * 实参，`validateCaptured` 改位置实参（顺序与 master 具名实参一致）；`data object Valid` 的 Java
 * 承载是单例字段，故 `PsiSnapshot.Validity.Valid` → `PsiSnapshot.Validity.Valid.INSTANCE`。
 */
class PsiSnapshotTest : BasePlatformTestCase() {

    private fun configure(code: String) {
        myFixture.configureByText("Demo.java", code.trimIndent())
    }

    /** 文件内第一个局部变量的名字 identifier（行号固定 = 3，见各用例夹具）。 */
    private fun firstLocalVariableId(): PsiElement =
        PsiTreeUtil.findChildOfType(myFixture.file, PsiLocalVariable::class.java)!!.nameIdentifier!!

    /** 文档整体重写（EDT 上改文档的标准形态：WriteCommandAction 内 setText）。 */
    private fun rewriteDocument(text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText(text)
        }
    }

    private fun deleteConfiguredFile() {
        val vf = myFixture.file.virtualFile
        WriteCommandAction.runWriteCommandAction(project) {
            vf.delete(this)
        }
    }

    // ------------------------------------------------------------------
    // 表驱动（纯函数内核）
    // ------------------------------------------------------------------

    fun test表驱动_文件不存在_stamp变化_区间越界三态判失效() {
        assertEquals(
            "文件失效应判 FILE_GONE（其余事实即使全部正常）",
            PsiSnapshot.Validity.Invalid(PsiSnapshot.InvalidReason.FILE_GONE),
            PsiSnapshotKt.validateCaptured(7, 0, 3, false, 7, 10),
        )
        assertEquals(
            "stamp 变化应判 DOCUMENT_MODIFIED",
            PsiSnapshot.Validity.Invalid(PsiSnapshot.InvalidReason.DOCUMENT_MODIFIED),
            PsiSnapshotKt.validateCaptured(7, 0, 3, true, 8, 10),
        )
        assertEquals(
            "区间越出文档长度应判 RANGE_OUT_OF_BOUNDS",
            PsiSnapshot.Validity.Invalid(PsiSnapshot.InvalidReason.RANGE_OUT_OF_BOUNDS),
            PsiSnapshotKt.validateCaptured(7, 2, 11, true, 7, 10),
        )
        assertEquals(
            "负偏移应判 RANGE_OUT_OF_BOUNDS",
            PsiSnapshot.Validity.Invalid(PsiSnapshot.InvalidReason.RANGE_OUT_OF_BOUNDS),
            PsiSnapshotKt.validateCaptured(7, -1, 3, true, 7, 10),
        )
        assertEquals(
            "start > end 应判 RANGE_OUT_OF_BOUNDS",
            PsiSnapshot.Validity.Invalid(PsiSnapshot.InvalidReason.RANGE_OUT_OF_BOUNDS),
            PsiSnapshotKt.validateCaptured(7, 5, 3, true, 7, 10),
        )
    }

    fun test表驱动_无变化判有效_端点值不误判() {
        assertEquals(
            "三项事实一致应判有效",
            PsiSnapshot.Validity.Valid.INSTANCE,
            PsiSnapshotKt.validateCaptured(7, 0, 3, true, 7, 10),
        )
        assertEquals(
            "区间贴住文档末尾（endOffset == documentLength）应判有效（边界含端点）",
            PsiSnapshot.Validity.Valid.INSTANCE,
            PsiSnapshotKt.validateCaptured(7, 7, 10, true, 7, 10),
        )
        assertEquals(
            "空区间（start == end）应判有效",
            PsiSnapshot.Validity.Valid.INSTANCE,
            PsiSnapshotKt.validateCaptured(7, 4, 4, true, 7, 10),
        )
    }

    // ------------------------------------------------------------------
    // 真夹具（EDT 捕获 → 后台读取前校验）
    // ------------------------------------------------------------------

    fun test真夹具_捕获后未变更判有效且行号正确() {
        configure(
            """
            public class Demo {
                public void query() {
                    java.util.List<String> list = new java.util.ArrayList<String>();
                }
            }
            """,
        )
        val snapshot = PsiSnapshot.capture(firstLocalVariableId())
        assertNotNull("EDT 捕获应产出快照（含虚拟文件 + 文档）", snapshot)
        assertEquals(
            "未变更应判有效",
            PsiSnapshot.Validity.Valid.INSTANCE,
            snapshot!!.validate(),
        )
        assertEquals("行号应为元素所在行（1-based）", 3, snapshot.lineNumber())
        assertTrue("快照须带文件路径（诊断与项目内相对化都用它）", snapshot.filePath.endsWith("Demo.java"))
    }

    fun test真夹具_文档变更后判失效() {
        configure(
            """
            public class Demo {
                public void query() {
                    java.util.List<String> list = new java.util.ArrayList<String>();
                }
            }
            """,
        )
        val snapshot = PsiSnapshot.capture(firstLocalVariableId())
        assertNotNull(snapshot)
        assertEquals(PsiSnapshot.Validity.Valid.INSTANCE, snapshot!!.validate())

        rewriteDocument(
            """
            public class Demo {
                public void query() {
                    int before = 1;
                    java.util.List<String> list = new java.util.ArrayList<String>();
                }
            }
            """.trimIndent(),
        )

        val validity = snapshot.validate()
        assertTrue("文档变更后应判失效，实际 $validity", validity is PsiSnapshot.Validity.Invalid)
        assertEquals(
            "失效原因应为 DOCUMENT_MODIFIED",
            PsiSnapshot.InvalidReason.DOCUMENT_MODIFIED,
            (validity as PsiSnapshot.Validity.Invalid).reason,
        )
    }

    fun test真夹具_文件删除后判失效() {
        configure(
            """
            public class Demo {
                public void query() {
                    java.util.List<String> list = new java.util.ArrayList<String>();
                }
            }
            """,
        )
        val snapshot = PsiSnapshot.capture(firstLocalVariableId())
        assertNotNull(snapshot)
        assertEquals(PsiSnapshot.Validity.Valid.INSTANCE, snapshot!!.validate())

        deleteConfiguredFile()

        val validity = snapshot.validate()
        assertTrue("文件删除后应判失效，实际 $validity", validity is PsiSnapshot.Validity.Invalid)
        assertEquals(
            "失效原因应为 FILE_GONE",
            PsiSnapshot.InvalidReason.FILE_GONE,
            (validity as PsiSnapshot.Validity.Invalid).reason,
        )
    }
}
