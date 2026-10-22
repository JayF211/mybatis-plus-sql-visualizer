package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * Resolve entity names inside the query's module, dependencies and libraries.
 * Call under read access. A missing visible class never falls back to unrelated modules;
 * equally valid candidates fail closed. Sorting is for diagnostics, not selection.
 *
 * This entry point performs only name/FQN lookups, never an all-class-name sweep.
 * Callers with an existing bounded alias search can supply its complete results through
 * [resolveWithAliasCandidates]. A truncated search cannot establish uniqueness.
 */
object EntityClassResolver {
    private val LOG = Logger.getInstance(EntityClassResolver::class.java)

    data class Resolution(val psiClass: PsiClass?, val candidates: List<PsiClass>) {
        val ambiguous: Boolean get() = candidates.size > 1
    }

    fun resolveByName(project: Project, entityName: String, context: PsiElement?): Resolution =
        resolveWithAliasCandidates(project, entityName, context, emptyList())

    fun resolveClassByName(project: Project, entityName: String, context: PsiElement?): PsiClass? =
        resolveByName(project, entityName, context).psiClass

    /**
     * Merge a COMPLETE, bounded alias-search result with direct name candidates.
     * The caller owns cancellation/budget and context-sensitive cache invalidation of that search.
     * Do not pass a first-hit or truncated result as proof of uniqueness. Explicit and default
     * entity names share one namespace: two visible owners are ambiguous, neither has priority.
     */
    fun resolveWithAliasCandidates(
        project: Project,
        entityName: String,
        context: PsiElement?,
        aliasCandidates: Collection<PsiClass>,
    ): Resolution {
        if (entityName.isBlank()) return Resolution(null, emptyList())
        ProgressManager.checkCanceled()
        val module = contextModule(project, context)
        val scope = searchScope(project, context)
        val facade = JavaPsiFacade.getInstance(project)
        val qualified = '.' in entityName
        val direct = if (qualified) facade.findClasses(entityName, scope).toList()
            else PsiShortNamesCache.getInstance(project).getClassesByName(entityName, scope).toList()
        val visible = (direct + aliasCandidates).filter { clazz ->
            ProgressManager.checkCanceled()
            clazz.isValid && clazz.containingFile?.virtualFile?.let(scope::contains) == true
        }.distinctBy { it.qualifiedName to it.containingFile?.virtualFile?.url }

        // Resolve duplicate source/binary definitions with the platform's actual classpath order.
        // Without a module there is no meaningful classpath winner across unrelated modules.
        val canonical = visible.groupBy { it.qualifiedName }.flatMap { (fqn, definitions) ->
            if (module == null || fqn == null || definitions.size == 1) definitions
            else {
                val resolved = facade.findClass(fqn, scope)
                definitions.filter { candidate ->
                    candidate == resolved || candidate.containingFile?.virtualFile == resolved?.containingFile?.virtualFile
                }
            }
        }
        val candidates = canonical.filter { clazz ->
            ProgressManager.checkCanceled()
            if (qualified) clazz.qualifiedName == entityName
            else {
                val explicitName = JpaMetadataReader.entityNameOf(clazz)?.takeIf { it.isNotEmpty() }
                (explicitName ?: clazz.name) == entityName
            }
        }.sortedWith(compareBy({ it.qualifiedName ?: it.name.orEmpty() }, { it.containingFile?.virtualFile?.url.orEmpty() }))
        if (candidates.size > 1) notifyAmbiguityOnce(project, entityName, module, candidates)
        return Resolution(candidates.singleOrNull(), candidates)
    }

    /** Shared scope for direct, alias and metadata lookup; never broadens a known module. */
    fun searchScope(project: Project, context: PsiElement?): GlobalSearchScope =
        contextModule(project, context)?.let { GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(it) }
            ?: GlobalSearchScope.allScope(project)

    /** Module identity for context-sensitive caller caches (null means no module context). */
    fun contextModule(project: Project, context: PsiElement?): Module? {
        val file = when (context) {
            null -> return null
            is PsiFile -> context
            else -> context.containingFile
        }
        val vf = file?.originalFile?.virtualFile ?: file?.virtualFile ?: return null
        return ProjectFileIndex.getInstance(project).getModuleForFile(vf)
    }

    private data class NoticeKey(val module: Module?, val entityName: String, val candidates: List<String>)

    private fun notifyAmbiguityOnce(project: Project, entityName: String, module: Module?, pool: List<PsiClass>) {
        val names = pool.map { it.qualifiedName ?: it.name ?: "?" }
        val key = NoticeKey(module, entityName, pool.map { it.containingFile?.virtualFile?.url.orEmpty() })
        // F102（T-PerfSweep26）：去重集改有界 LRU——此前按 (module, entity, urls) 元组在项目
        // 生命周期内只增不减（量级=项目内歧义实体数，实际很小但无上界）。超出 MAX 后淘汰最旧，
        // 代价=极老歧义项可能再次提示一次（可接受：提示是幂等告知，非状态机）。
        val seen = synchronized(project) {
            project.getUserData(AMBIGUITY_NOTIFIED_KEY) ?: object : LinkedHashMap<NoticeKey, Boolean>(
                AMBIGUITY_NOTICE_MAX + 1,
                0.75f,
                true,
            ) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NoticeKey, Boolean>?): Boolean =
                    size > AMBIGUITY_NOTICE_MAX
            }.also {
                project.putUserData(AMBIGUITY_NOTIFIED_KEY, it)
            }
        }
        val firstTime = synchronized(seen) {
            if (seen.containsKey(key)) {
                seen[key] = true // accessOrder：命中即刷新 LRU 位置
                false
            } else {
                seen[key] = true
                true
            }
        }
        if (!firstTime) return
        val scopeDescription = module?.name ?: "project / libraries"
        LOG.info("Entity name $entityName is ambiguous in $scopeDescription; no class selected: ${names.joinToString()}")
        val sink = ambiguitySinkForTest
        if (sink != null) {
            sink(project, entityName, scopeDescription, names)
            return
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(PREVIEW_NOTIFICATION_GROUP)
            .createNotification(
                MpSqlVisualizerBundle.message("preview.entityNameAmbiguity.title", entityName),
                MpSqlVisualizerBundle.message("preview.entityNameAmbiguity.content", scopeDescription, names.joinToString(", ")),
                NotificationType.WARNING,
            ).notify(project)
    }

    private val AMBIGUITY_NOTIFIED_KEY = Key.create<MutableMap<NoticeKey, Boolean>>("mp-visualizer.entityNameAmbiguityNotified")

    /** F102：歧义提示去重集容量上界（超出按 LRU 淘汰最旧）。 */
    private const val AMBIGUITY_NOTICE_MAX = 64
    private const val PREVIEW_NOTIFICATION_GROUP = "cn.song.mybatisplus.visualizer.preview"

    /** Callback arguments: project, entity name, scope label, all candidates (none selected). */
    @Volatile
    internal var ambiguitySinkForTest: ((Project, String, String, List<String>) -> Unit)? = null

    internal fun clearAmbiguityNotifiedForTest(project: Project) {
        synchronized(project) { project.putUserData(AMBIGUITY_NOTIFIED_KEY, null) }
    }

    /** F102 测试缝：当前去重集条目数（有界性断言用；未初始化返回 0）。 */
    internal fun ambiguityNoticeCountForTest(project: Project): Int =
        synchronized(project) { project.getUserData(AMBIGUITY_NOTIFIED_KEY)?.size ?: 0 }

    /** F102 测试缝：去重集容量上界（与实现常量同源，避免用例写死数字漂移）。 */
    internal fun ambiguityNoticeMaxForTest(): Int = AMBIGUITY_NOTICE_MAX
}
