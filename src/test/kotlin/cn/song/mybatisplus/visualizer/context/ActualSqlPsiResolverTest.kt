package cn.song.mybatisplus.visualizer.context

import cn.song.mybatisplus.visualizer.ui.inject.SqlMapperLanguage
import cn.song.mybatisplus.visualizer.ui.inject.SqlMapperParserDefinition
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class ActualSqlPsiResolverTest : BasePlatformTestCase() {

    private val originalSqlFileClassLookup = ActualSqlPsiResolver.sqlFileClassLookup

    override fun setUp() {
        super.setUp()
        // 243 headless fixture 不注册 DatabaseTools SQL Language；算法用 PsiFile 宽谓词验证，
        // 真 SqlFile ABI 由 PlatformSignatureCrossVersionTest 在 243/253/262 发行物上补齐。
        ActualSqlPsiResolver.sqlFileClassLookup = { com.intellij.psi.PsiFile::class.java }
        if (LanguageParserDefinitions.INSTANCE.forLanguage(SqlMapperLanguage) == null) {
            LanguageParserDefinitions.INSTANCE.addExplicitExtension(
                SqlMapperLanguage,
                SqlMapperParserDefinition(),
                testRootDisposable,
            )
        }
        InjectedLanguageManager.getInstance(project)
            .registerMultiHostInjector(MarkerInjector(), testRootDisposable)
    }

    override fun tearDown() {
        ActualSqlPsiResolver.sqlFileClassLookup = originalSqlFileClassLookup
        super.tearDown()
    }

    fun test同一owner两个SQL注入_无精确光标返回歧义() {
        val (file, method) = configureJava(
            """class Demo {
  void run() {
    String first = "SQL:SELECT 1";
    String second = "SQL:SELECT 2";
  }
}""",
        )

        val result = ActualSqlPsiResolver.resolve(file, method)
        assertTrue("两个真实 SQL 注入不得静默取首个：$result", result is SqlPsiResolution.Ambiguous)
        assertEquals(2, (result as SqlPsiResolution.Ambiguous).files.size)
    }

    fun test同一owner两个SQL注入_精确光标优先命中对应注入() {
        val (file, method) = configureJava(
            """class Demo {
  void run() {
    String first = "SQL:SELECT 1";
    String second = "SQL:SELECT 2";
  }
}""",
        )
        val secondOffset = file.text.indexOf("SELECT 2") + 2
        val manager = InjectedLanguageManager.getInstance(project)
        val expected = manager.findInjectedElementAt(file, secondOffset)!!.containingFile

        val result = ActualSqlPsiResolver.resolve(file, method, secondOffset)
        assertTrue("精确光标须压过 owner 内另一注入：$result", result is SqlPsiResolution.Exact)
        assertSame(expected, (result as SqlPsiResolution.Exact).file)
    }

    fun testowner内唯一SQL注入返回UniqueInOwner() {
        val (file, method) = configureJava(
            """class Demo {
  void run() {
    String sql = "SQL:SELECT 1";
  }
}""",
        )

        val result = ActualSqlPsiResolver.resolve(file, method)
        assertTrue("无精确光标但 owner 内唯一真实 SQL 应可确定：$result", result is SqlPsiResolution.UniqueInOwner)
    }

    fun test反射SqlFile类型不匹配的普通注入不被接受() {
        val (file, method) = configureJava(
            """class Demo {
  void run() {
    String text = "PLAIN:not sql";
  }
}""",
        )
        ActualSqlPsiResolver.sqlFileClassLookup = { String::class.java }

        assertEquals(SqlPsiResolution.None, ActualSqlPsiResolver.resolve(file, method))
    }

    fun testMpSqlMapper注入即使满足宽谓词也被显式拒绝() {
        val (file, method) = configureJava(
            """class Demo {
  void run() {
    String mapper = "MAPPER:select * from t";
  }
}""",
        )
        val mapperOffset = file.text.indexOf("select *") + 2
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, mapperOffset)
        assertNotNull("夹具必须真实产生 MpSqlMapper 注入，不能用零注入制造空绿", injected)
        assertEquals("MpSqlMapper", injected!!.containingFile.language.id)

        assertEquals(SqlPsiResolution.None, ActualSqlPsiResolver.resolve(file, method))
    }

    fun test宿主文档未提交时返回Uncommitted而非陈旧注入() {
        val (file, method) = configureJava(
            """class Demo {
  void run() {
    String sql = "SQL:SELECT 1";
  }
}""",
        )
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.textLength - 1, " ")
        }
        assertTrue("夹具必须处于未提交态", !documentManager.isCommitted(document))

        val result = ActualSqlPsiResolver.resolve(file, method)
        assertEquals(SqlPsiResolution.Uncommitted, result)
        documentManager.commitDocument(document)
    }

    fun testDatabaseTools类型不可见时诚实返回Unavailable() {
        val (file, method) = configureJava(
            """class Demo {
  void run() {
    String sql = "SQL:SELECT 1";
  }
}""",
        )
        ActualSqlPsiResolver.sqlFileClassLookup = { null }

        assertEquals(
            SqlPsiResolution.DatabaseToolsUnavailable,
            ActualSqlPsiResolver.resolve(file, method),
        )
    }

    fun testowner扫描达到节点上限时不把局部候选冒认唯一() {
        val (file, method) = configureJava(
            """class Demo {
  void run() {
    String first = "SQL:SELECT 1";
    String second = "SQL:SELECT 2";
  }
}""",
        )

        assertEquals(
            SqlPsiResolution.None,
            ActualSqlPsiResolver.resolve(file, method, maxOwnerElements = 1),
        )
    }

    private fun configureJava(text: String): Pair<com.intellij.psi.PsiFile, PsiMethod> {
        val file = myFixture.configureByText("Demo.java", text)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val method = PsiTreeUtil.findChildOfType(file, PsiMethod::class.java)
            ?: error("fixture method missing")
        return file to method
    }

    private class MarkerInjector : MultiHostInjector {
        override fun elementsToInjectIn(): List<Class<out PsiElement>> =
            listOf(PsiLiteralExpression::class.java)

        override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
            val literal = context as? PsiLiteralExpression ?: return
            val value = literal.value as? String ?: return
            val host = literal as? PsiLanguageInjectionHost ?: return
            val language = when {
                value.startsWith("SQL:") -> PlainTextLanguage.INSTANCE
                value.startsWith("PLAIN:") -> PlainTextLanguage.INSTANCE
                value.startsWith("MAPPER:") -> SqlMapperLanguage
                else -> return
            }
            registrar.startInjecting(language)
                .addPlace(null, null, host, ElementManipulators.getValueTextRange(literal))
                .doneInjecting()
        }
    }
}
