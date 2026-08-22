package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * Wrapper 搜索服务（O-1 Phase A）：在项目内搜索引用指定表的 Wrapper 链，供日志 ↔ 源码关联跳转。
 *
 * 性能约束：
 * - 使用 [ReferencesSearch] 限定搜索范围（scope 受限，仅项目文件）
 * - 搜索操作含 PSI 读，调用方必须在 ReadAction 中调度
 * - 表名匹配走纯函数 [matchesTable]（< 1ms）
 */

/** 候选跳转目标。 */
data class WrapperCandidate(
    /** Wrapper 链锚点所在方法。 */
    val anchorMethod: PsiMethod,
    /** 所在文件。 */
    val file: PsiFile,
    /** 匹配到的表信息。 */
    val table: TableInfo,
)

/** 搜索结果封装。 */
sealed class WrapperSearchResult {
    /** 找到唯一候选。 */
    data class Single(val candidate: WrapperCandidate) : WrapperSearchResult()
    /** 找到多个候选。 */
    data class Multiple(val candidates: List<WrapperCandidate>) : WrapperSearchResult()
    /** 未找到任何候选。 */
    data object None : WrapperSearchResult()
}

object WrapperSearchService {

    private val LOG = Logger.getInstance(WrapperSearchService::class.java)

    /**
     * 判断表信息是否匹配目标表名（纯函数，可独立单测）。
     *
     * @param table 解析到的表信息（可能为 null）
     * @param tableName 目标表名（来自 SQL 提取）
     * @return true = 匹配
     */
    fun matchesTable(table: TableInfo?, tableName: String): Boolean {
        if (table == null || tableName.isBlank()) return false
        return table.tableName.equals(tableName, ignoreCase = true)
    }

    /**
     * 在项目内搜索引用指定表的 Wrapper 链。
     *
     * 注意：本方法含 PSI 读操作，调用方必须在 ReadAction 中调用。
     *
     * @param project 当前项目
     * @param tableName 目标表名
     * @return 搜索结果（唯一/多候选/无匹配）
     */
    fun search(project: Project, tableName: String): WrapperSearchResult {
        if (tableName.isBlank()) return WrapperSearchResult.None

        val matchedCandidates = mutableListOf<WrapperCandidate>()
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        try {
            for (className in WrapperLocator.wrapperFactoryClassNames) {
                val psiClass = facade.findClass(className, GlobalSearchScope.allScope(project)) ?: continue
                searchInClass(psiClass, tableName, scope, matchedCandidates)
            }
        } catch (e: Exception) {
            LOG.warn("WrapperSearchService.search 异常（table=$tableName）：${e.message}", e)
        }

        return when (matchedCandidates.size) {
            0 -> WrapperSearchResult.None
            1 -> WrapperSearchResult.Single(matchedCandidates.first())
            else -> WrapperSearchResult.Multiple(
                matchedCandidates.distinctBy { it.anchorMethod.textRange.startOffset },
            )
        }
    }

    /** 在单个工厂类内搜索匹配的 Wrapper 引用。 */
    private fun searchInClass(
        psiClass: PsiClass,
        tableName: String,
        scope: GlobalSearchScope,
        out: MutableList<WrapperCandidate>,
    ) {
        for (method in psiClass.methods) {
            if (method.name !in WrapperLocator.wrapperFactoryMethodNames) continue
            val refs = try {
                ReferencesSearch.search(method, scope, false).findAll()
            } catch (_: Exception) {
                continue
            }
            for (ref in refs) {
                val element = ref.element
                val located = WrapperLocator.locateWithTable(element) ?: continue
                val locatedTable = located.table ?: continue
                if (!matchesTable(locatedTable, tableName)) continue
                val anchorMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: continue
                val file = element.containingFile ?: continue
                out += WrapperCandidate(anchorMethod = anchorMethod, file = file, table = locatedTable)
            }
        }
    }

    /**
     * 跳转到候选元素（在 EDT 中执行编辑器定位）。
     */
    fun navigateTo(project: Project, candidate: WrapperCandidate) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val virtualFile = candidate.file.virtualFile ?: return@invokeLater
            val descriptor = com.intellij.openapi.fileEditor.OpenFileDescriptor(
                project, virtualFile, candidate.anchorMethod.textRange.startOffset,
            )
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }
}
