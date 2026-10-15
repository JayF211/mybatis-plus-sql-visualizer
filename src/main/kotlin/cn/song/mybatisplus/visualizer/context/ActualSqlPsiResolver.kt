package cn.song.mybatisplus.visualizer.context

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 从宿主 PSI 中解析真正的 DatabaseTools SQL PSI。
 *
 * 约束：
 * 1. 光标精确命中优先于 owner 扫描；
 * 2. owner 扫描同时受节点数和文本长度约束，截断时绝不把已见单候选冒认成唯一；
 * 3. `com.intellij.sql.psi.SqlFile` 只按类名反射检查，DatabaseTools 缺席不会污染插件类加载；
 * 4. 本插件自研的 `MpSqlMapper` 注入只负责着色/补全，不冒充平台 SQL PSI。
 */
object ActualSqlPsiResolver {

    internal const val DEFAULT_MAX_OWNER_ELEMENTS = 2_048
    internal const val DEFAULT_MAX_OWNER_TEXT_LENGTH = 128 * 1024
    private const val SQL_FILE_FQN = "com.intellij.sql.psi.SqlFile"
    private const val MP_SQL_MAPPER_LANGUAGE_ID = "MpSqlMapper"

    /** 测试缝：生产始终走字符串类名加载；不得改成 SqlFile.class 直接链接。 */
    internal var sqlFileClassLookup: () -> Class<*>? = {
        try {
            Class.forName(SQL_FILE_FQN, false, ActualSqlPsiResolver::class.java.classLoader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    fun resolve(
        hostFile: PsiFile,
        owner: PsiElement? = hostFile,
        caretOffset: Int? = null,
        maxOwnerElements: Int = DEFAULT_MAX_OWNER_ELEMENTS,
        maxOwnerTextLength: Int = DEFAULT_MAX_OWNER_TEXT_LENGTH,
    ): SqlPsiResolution {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val sqlFileClass = sqlFileClassLookup() ?: return SqlPsiResolution.DatabaseToolsUnavailable

        val documentManager = PsiDocumentManager.getInstance(hostFile.project)
        val document = documentManager.getDocument(hostFile)
        if (document != null && !documentManager.isCommitted(document)) {
            return SqlPsiResolution.Uncommitted
        }

        val injectionManager = InjectedLanguageManager.getInstance(hostFile.project)
        if (caretOffset != null && caretOffset >= 0 && caretOffset < hostFile.textLength) {
            val exact = injectionManager.findInjectedElementAt(hostFile, caretOffset)
                ?.containingFile
                ?.takeIf { isActualSqlFile(it, sqlFileClass) }
            if (exact != null) return SqlPsiResolution.Exact(exact)
        }

        val boundedOwner = owner
            ?.takeIf { it.isValid && it.containingFile == hostFile }
            ?: return SqlPsiResolution.None
        if (maxOwnerElements <= 0 || maxOwnerTextLength <= 0 || boundedOwner.textLength > maxOwnerTextLength) {
            return SqlPsiResolution.None
        }

        val candidates = Collections.newSetFromMap(IdentityHashMap<PsiFile, Boolean>())
        val orderedCandidates = mutableListOf<PsiFile>()
        val stack = ArrayDeque<PsiElement>()
        stack.addLast(boundedOwner)
        var visited = 0
        var truncated = false

        while (stack.isNotEmpty()) {
            if (visited >= maxOwnerElements) {
                truncated = true
                break
            }
            val element = stack.removeLast()
            visited++

            if (element is PsiLanguageInjectionHost) {
                val injectedFiles = injectionManager.getInjectedPsiFiles(element).orEmpty()
                for (injected in injectedFiles) {
                    val file = injected.first.containingFile ?: continue
                    if (isActualSqlFile(file, sqlFileClass) && candidates.add(file)) {
                        orderedCandidates += file
                    }
                }
            }

            var child = element.lastChild
            while (child != null) {
                stack.addLast(child)
                child = child.prevSibling
            }
        }

        if (truncated) return SqlPsiResolution.None
        return when (orderedCandidates.size) {
            0 -> SqlPsiResolution.None
            1 -> SqlPsiResolution.UniqueInOwner(orderedCandidates.single())
            else -> SqlPsiResolution.Ambiguous(orderedCandidates.toList())
        }
    }

    private fun isActualSqlFile(file: PsiFile, sqlFileClass: Class<*>): Boolean =
        file.language.id != MP_SQL_MAPPER_LANGUAGE_ID && sqlFileClass.isInstance(file)
}
