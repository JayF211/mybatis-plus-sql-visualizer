package cn.song.mybatisplus.visualizer.sql.insight

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.xml.XmlFile
import java.util.ArrayDeque
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

sealed class SqlUsageSearchScope {
    data class CurrentFile(val file: VirtualFile) : SqlUsageSearchScope()
    data class ModuleFiles(val module: Module) : SqlUsageSearchScope()
}

/** Explicit-query production entry point. No service registration, persistent index or library traversal. */
class ProjectSqlUsageService(private val project: Project) : Disposable {
    @Volatile private var disposed = false
    private val generation = AtomicLong()
    @Volatile private var active: Future<*>? = null

    @Synchronized fun cancel() { generation.incrementAndGet(); active?.cancel(false) }
    @Synchronized override fun dispose() { disposed = true; cancel() }

    /** Completion runs on the pooled thread. UI must marshal its own presentation to EDT. */
    @Synchronized fun collectAsync(scope: SqlUsageSearchScope, template: AnalysisSnapshot, limits: SqlUsageLimits = SqlUsageLimits(),
        onComplete: (SqlUsageReport) -> Unit): Future<*> {
        check(!disposed && !project.isDisposed) { "SQL usage service is disposed" }
        cancel()
        val token = generation.get()
        val future = ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            val report = collect(scope, template, limits) { disposed || project.isDisposed || generation.get() != token || Thread.currentThread().isInterrupted }
            synchronized(this) {
                if (!disposed && !project.isDisposed && generation.get() == token) onComplete(report)
            }
        })
        active = future
        return future
    }

    /** Synchronous background entry is also usable by explicit callers; never run it on EDT. */
    fun collect(scope: SqlUsageSearchScope, template: AnalysisSnapshot, limits: SqlUsageLimits = SqlUsageLimits(),
        isCancelled: () -> Boolean = { false }): SqlUsageReport {
        check(!ApplicationManager.getApplication().isDispatchThread) { "SQL usage collection must run on a background thread" }
        val candidates = mutableListOf<SqlUsageCandidate>()
        val diagnostics = mutableListOf<SqlUsageDiagnostic>()
        val queue = ArrayDeque<VirtualFile>()
        val seen = mutableSetOf<String>()
        var files = 0
        var directories = 0
        var skipped = 0
        var truncated = false
        fun cancelled() = disposed || project.isDisposed || isCancelled() || (scope is SqlUsageSearchScope.ModuleFiles && scope.module.isDisposed)
        val roots = ReadAction.compute<List<VirtualFile>, RuntimeException> {
            if (cancelled()) emptyList() else when (scope) {
                is SqlUsageSearchScope.CurrentFile -> listOf(scope.file)
                is SqlUsageSearchScope.ModuleFiles -> ModuleRootManager.getInstance(scope.module).contentRoots.toList()
            }
        }
        queue.addAll(roots)
        while (queue.isNotEmpty() && !cancelled()) {
            if (files >= limits.maxFiles || candidates.size >= limits.maxCandidates || directories >= limits.maxDirectories) { truncated = true; break }
            val vf = queue.removeFirst()
            // A short read per directory/file: never enumerate a module under one long read lock.
            val result = ReadAction.compute<FileSlice, RuntimeException> {
                if (cancelled() || !vf.isValid || !seen.add(vf.path)) return@compute FileSlice(visited = false)
                if (scope is SqlUsageSearchScope.ModuleFiles) {
                    val index = ProjectFileIndex.getInstance(project)
                    if (index.isExcluded(vf) || index.isInLibrary(vf) || index.getModuleForFile(vf) != scope.module) return@compute FileSlice(visited = false)
                }
                if (vf.isDirectory) {
                    val children = vf.children
                    return@compute FileSlice(children = children.take(limits.maxFiles + limits.maxDirectories),
                        truncated = children.size > limits.maxFiles + limits.maxDirectories)
                }
                if (vf.extension?.lowercase() !in setOf("sql", "java", "xml")) return@compute FileSlice(skipped = true)
                if (vf.length > limits.maxFileCharacters * 4L) return@compute FileSlice(skipped = true,
                    diagnostics = listOf(SqlUsageDiagnostic("FILE_SIZE_LIMIT", "File exceeds collection budget: ${vf.path}")))
                val psi = PsiManager.getInstance(project).findFile(vf) ?: return@compute FileSlice(skipped = true)
                if (psi.textLength > limits.maxFileCharacters) return@compute FileSlice(skipped = true,
                    diagnostics = listOf(SqlUsageDiagnostic("FILE_SIZE_LIMIT", "File exceeds text budget: ${vf.path}")))
                extract(psi, vf.path, psi.modificationStamp, template, limits, ::cancelled)
            }
            if (result.children != null) {
                directories++
                val capacity = (limits.maxFiles + limits.maxDirectories - queue.size).coerceAtLeast(0)
                queue.addAll(result.children.take(capacity))
                if (result.children.size > capacity) truncated = true
            }
            else if (result.visited) files++
            if (result.skipped) skipped++
            diagnostics += result.diagnostics
            candidates += result.candidates.take((limits.maxCandidates - candidates.size).coerceAtLeast(0))
            if (result.truncated) truncated = true
        }
        val label = when (scope) { is SqlUsageSearchScope.CurrentFile -> "current file"; is SqlUsageSearchScope.ModuleFiles -> "explicit module content (no dependencies)" }
        val analysis = SqlObjectUsageAnalyzer.analyze(candidates, ::cancelled, limits, label)
        if (truncated) diagnostics += SqlUsageDiagnostic("SCOPE_LIMIT", "File/directory/candidate/PSI budget reached; coverage is partial.")
        diagnostics += SqlUsageDiagnostic("SUPPORTED_SOURCES", "Collected SQL files, Java literal-only constants and static mapper CRUD text; Kotlin/runtime-generated SQL and dynamic mapper fragments are not covered.")
        return analysis.copy(diagnostics = diagnostics + analysis.diagnostics,
            coverage = analysis.coverage.copy(visitedFiles = files, skippedFiles = skipped, truncated = truncated || analysis.coverage.truncated, cancelled = cancelled() || analysis.coverage.cancelled))
    }

    private data class FileSlice(val children: List<VirtualFile>? = null, val candidates: List<SqlUsageCandidate> = emptyList(),
        val diagnostics: List<SqlUsageDiagnostic> = emptyList(), val visited: Boolean = true, val skipped: Boolean = false, val truncated: Boolean = false)

    private fun extract(file: PsiElement, path: String, stamp: Long, template: AnalysisSnapshot, limits: SqlUsageLimits,
        cancelled: () -> Boolean): FileSlice {
        val candidates = mutableListOf<SqlUsageCandidate>()
        val diagnostics = mutableListOf<SqlUsageDiagnostic>()
        fun add(sql: String, element: PsiElement, kind: SqlCandidateKind, label: String? = null) {
            if (candidates.size >= limits.maxCandidates) return
            if (!Regex("(?is)^\\s*(?:/\\*.*?\\*/\\s*|--[^\\n]*\\n\\s*)*(SELECT|WITH|INSERT|UPDATE|DELETE|MERGE|CREATE|DROP)\\b").containsMatchIn(sql)) return
            val range = element.textRange
            candidates += SqlUsageCandidate(template.copy(sql = sql, sourceVersion = stamp.toString()),
                SqlSourceLocation(path, range.startOffset, range.endOffset, stamp, label), kind)
        }
        if (path.endsWith(".sql", true)) { add(file.text, file, SqlCandidateKind.SQL_FILE); return FileSlice(candidates = candidates) }
        if (file is XmlFile) {
            val root = file.rootTag
            if (root?.name != "mapper") return FileSlice(skipped = true)
            for (tag in root.subTags) {
                if (cancelled() || candidates.size >= limits.maxCandidates) break
                if (tag.name !in setOf("select", "insert", "update", "delete")) continue
                val location = SqlSourceLocation(path, tag.textRange.startOffset, tag.textRange.endOffset, stamp, tag.getAttributeValue("id"))
                val text = tag.value.textElements.joinToString("") { it.value }
                if (tag.subTags.isNotEmpty() || text.contains("#{") || text.contains("\${")) {
                    diagnostics += SqlUsageDiagnostic("DYNAMIC_MAPPER_SKIPPED", "Mapper statement has dynamic tags/placeholders; no speculative SQL was produced.", location)
                } else add(text, tag, SqlCandidateKind.MAPPER_STATEMENT, tag.getAttributeValue("id"))
            }
            return FileSlice(candidates = candidates, diagnostics = diagnostics, truncated = candidates.size >= limits.maxCandidates)
        }
        val stack = ArrayDeque<PsiElement>()
        stack.add(file)
        var nodes = 0
        while (stack.isNotEmpty() && !cancelled() && nodes++ < limits.maxPsiNodesPerFile && candidates.size < limits.maxCandidates) {
            val element = stack.removeLast()
            if (element is PsiPolyadicExpression && element.operationTokenType == JavaTokenType.PLUS) {
                val operands = element.operands
                if (operands.size > limits.maxPsiNodesPerFile - nodes) return FileSlice(candidates = candidates, diagnostics = diagnostics, truncated = true)
                nodes += operands.size
                val parts = operands.map { (it as? PsiLiteralExpression)?.value as? String }
                if (parts.all { it != null }) add(parts.joinToString("") { it!! }, element, SqlCandidateKind.JAVA_CONSTANT)
                else diagnostics += SqlUsageDiagnostic("NON_CONSTANT_CONCATENATION", "Non-literal concatenation was not evaluated.",
                    SqlSourceLocation(path, element.textRange.startOffset, element.textRange.endOffset, stamp))
                continue
            }
            if (element is PsiLiteralExpression) (element.value as? String)?.let { add(it, element, SqlCandidateKind.JAVA_CONSTANT) }
            var child = element.firstChild
            while (child != null) {
                if (cancelled() || nodes + stack.size >= limits.maxPsiNodesPerFile)
                    return FileSlice(candidates = candidates, diagnostics = diagnostics, truncated = true)
                stack.addLast(child)
                child = child.nextSibling
            }
        }
        return FileSlice(candidates = candidates, diagnostics = diagnostics, truncated = stack.isNotEmpty())
    }
}
