package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class AnnotationSqlPreviewDialectContextTest : BasePlatformTestCase() {
    fun testAnnotationPreviewRetainsSourceDialectForLaterExecutionGate() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.state.copy()
        try {
            settings.state.dialectAuto = false
            settings.state.dialect = "Oracle"
            myFixture.configureByText("Mapper.java", """
                @interface Select { String value(); }
                interface Mapper {
                    @Select("SELECT * FROM t_user WHERE id = #{id}")
                    Object find(Long id);
                }
            """.trimIndent())
            val annotation = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiAnnotation::class.java)
                .first { it.qualifiedName?.substringAfterLast('.') == "Select" }
            val context = AnnotationSqlPreviewService.resolveSourceContext(annotation, project)
            assertEquals(cn.song.mybatisplus.visualizer.context.PreviewSourceOrigin.PSI, context.origin)
            assertEquals(myFixture.file, context.hostPsiFile)
            val result = AnnotationSqlPreviewService.preview(annotation, project, context)
            assertEquals("Oracle", result.effectiveDialect?.name)
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testProductionAnnotationPathResolvesActualSqlPsiBeforeDialectDecision() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "src/main/kotlin").isDirectory }
        val text = File(root,
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/AnnotationSqlPreviewService.kt").readText()
        assertTrue(text.contains("ActualSqlPsiResolver.resolve(host, owner, owner.textOffset)"))
        assertTrue(text.contains("DialectRegistry.effective(it)"))
        assertTrue(text.contains("copy(renderedDialect = renderDialect)"))
        val action = File(root,
            "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt").readText()
        val gutter = File(root,
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/AnnotationSqlGutterLineMarkerProvider.kt").readText()
        assertTrue(action.contains("AnnotationSqlPreviewService.resolveSourceContext"))
        assertTrue(action.contains("sourceLocation, contextRef.get()"))
        assertTrue(gutter.contains("AnnotationSqlPreviewService.resolveSourceContext"))
        assertTrue(gutter.contains("sourceContext = contextRef.get()"))
        val dialog = File(root,
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewDialog.kt").readText()
        assertTrue(dialog.contains("panel.setSourceContext(sourceContext)"))
    }
}
