package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-EntryInjectedSql：注解 SQL 的注入片段内触发预览。
 *
 * 真机 20261019 走查：光标落在 `@Delete("DELETE FROM ...")` 的字符串里按 Alt+Shift+Q，
 * 提示「未在光标处找到可预览的 SQL 源」——动作拿到的 PSI 文件/元素停在注入 SQL 片段，
 * 父链上没有宿主 `PsiAnnotation`。本类用测试注入器复刻该形态（Light 环境不加载
 * 平台 Database 插件的 Java 注解注入），同时覆盖「动作拿到宿主文件」与「动作拿到注入文件」
 * 两种入参（后者是真机失败形态）。
 */
class ConvertToSqlInjectedAnnotationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // 复刻平台对 Java 字符串字面量的 SQL 注入（Light 环境无 Database 插件）
        InjectedLanguageManager.getInstance(project)
            .registerMultiHostInjector(LiteralInjector(), testRootDisposable)
    }

    private fun mapperFile(): PsiJavaFile = myFixture.configureByText(
        "ProbeMapper.java",
        """
        package probe;

        import org.apache.ibatis.annotations.Delete;

        public interface ProbeMapper {
            @Delete("DELETE FROM user WHERE id = #{id}")
            int deleteUserById(Integer id);
        }
        """.trimIndent(),
    ) as PsiJavaFile

    fun testCaretInsideInjectedAnnotationSqlResolvesToHostAnnotation() {
        val file = mapperFile()
        val offset = file.text.indexOf("FROM user") + 2
        val injections = InjectedLanguageManager.getInstance(project)
        val injected = injections.findInjectedElementAt(file, offset)
        assertNotNull("注解 SQL 未产生注入片段（用例前提不成立）", injected)

        // 形态一：动作拿到宿主文件
        val fromHost = resolveCaretElement(project, file, offset)
        assertNotNull("宿主文件内光标必须能归位", fromHost)
        assertEquals("Delete", annotationOf(fromHost!!)?.qualifiedName?.substringAfterLast('.'))

        // 形态二：动作拿到注入文件（真机失败形态），offset 仍是编辑器宿主偏移
        val fromInjectedFile = resolveCaretElement(project, injected!!.containingFile, offset)
        assertNotNull("注入文件形态也必须归位到宿主注解", fromInjectedFile)
        val annotation = annotationOf(fromInjectedFile!!)
        assertEquals("Delete", annotation?.qualifiedName?.substringAfterLast('.'))

        val result = AnnotationSqlPreviewService.preview(annotation!!, project)
        assertTrue("注解预览应产出 DELETE：${result.sql}", result.sql.contains("DELETE FROM user", ignoreCase = true))
    }

    fun testCaretOutsideInjectionKeepsOriginalElement() {
        val file = myFixture.configureByText("Plain.java", "class Plain { int value = 1; }") as PsiJavaFile
        val offset = file.text.indexOf("value = 1") + 2
        val raw = file.findElementAt(offset)!!
        assertSame("非注入位置必须原样返回", raw, resolveCaretElement(project, file, offset))
    }

    private fun annotationOf(element: PsiElement): PsiAnnotation? =
        PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java, false)

    /** 只给普通字符串字面量注入纯文本（与平台 SQL 注入同形态：注入体 = 去引号后的值空间）。 */
    private class LiteralInjector : MultiHostInjector {
        override fun elementsToInjectIn(): List<Class<out PsiElement>> =
            listOf(PsiLiteralExpression::class.java)

        override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
            val literal = context as? PsiLiteralExpression ?: return
            if (literal.value !is String) return
            val host = literal as? PsiLanguageInjectionHost ?: return
            registrar.startInjecting(PlainTextLanguage.INSTANCE)
                .addPlace(null, null, host, ElementManipulators.getValueTextRange(literal))
                .doneInjecting()
        }
    }
}
