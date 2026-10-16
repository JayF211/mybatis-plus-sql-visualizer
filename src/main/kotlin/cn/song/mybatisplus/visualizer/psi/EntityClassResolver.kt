package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * 按「实体名」解析实体类的**唯一收口**（T-EntityNameScope）。
 *
 * ## 为什么需要它
 *
 * 预览/转换链里有多处「拿 HQL 里的实体名去找 PsiClass」的地方，此前一律是
 * `PsiShortNamesCache.getClassesByName(name, scope).firstOrNull()`——**没有任何消歧**。
 * 而 JPA 的实体名默认就是**简单类名**（`@Entity` 不写 `name` 时），于是只要工程里有两个同名实体类
 * （多模块各一份是很常见的：`Order`/`User`/`Config`/共享模型 JAR 与业务模块各一份），解析结果就是
 * **索引顺序里的第一个**——用户视角是「预览把实体认成了另一个类」，而且同一份代码两次可能给不同结果。
 *
 * 实测案例（demo 仓）：`jpa-jar-model-demo` 的 HQL 写 `from WmsInventoryEntity`，工程里
 * `jpa-embedded-prefix-demo` 与 `jpa-jar-model-lib` **各有一个 `WmsInventoryEntity`**，
 * 结果解析到嵌入列 demo 那个类 ⇒ FROM 表名、SELECT 列清单全按那个类渲染，而 HQL 里只存在于 jar 实体的
 * 两条嵌入路径（`sku.inventoryStatus` / `itemKey.lotInfo.receivedDate`）找不到映射、按既有约定原样透传。
 *
 * ## 消歧口径（强信号在前）
 *
 * 1. **模块可见性优先**：先只看「[context] 所在模块 + 其依赖 + 其库」（
 *    [GlobalSearchScope.moduleWithDependenciesAndLibrariesScope]）。Java 自己的解析规则就是
 *    「本模块声明的依赖可见才能引用」——不可见的类不可能是目标。**必须带 Libraries**：
 *    不含库的 `moduleWithDependenciesScope` 对 jar 里的类恒 null（仓内
 *    [cn.song.mybatisplus.visualizer.log.ProjectTypeDetector] 已留过同款注释）。
 * 2. **显式 `@Entity(name="X")` 精确匹配**优先于「类名默认成实体名」。
 * 3. 仍并列时按**全限定名稳定排序**，保证同样输入每次给同样结果（不再是索引顺序）。
 * 4. 可见范围内 0 命中 → 退回「项目范围优先、再全范围」的既有次序（不改变老工程结果）。
 *
 * ## 不静默猜
 *
 * 候选 >1 时**告诉用户**（每项目 + 每个实体名一次），而不是默默挑一个：这种错此前只能靠用户
 * 从渲染出的 SQL 里肉眼发现。诊断走预览通知组，不新增设置项。
 *
 * 线程：纯 PSI 索引查询（每个名字最多 3 次 `getClassesByName`），须在 ReadAction 内调用；
 * **不做任何全量类扫描**（那是另一条被性能收口钉死的路径）。
 */
object EntityClassResolver {

    private val LOG = Logger.getInstance(EntityClassResolver::class.java)

    /**
     * 解析结果。
     *
     * @param psiClass   选中的实体类（无候选时 null）
     * @param candidates 候选集合（>1 表示存在同名歧义，调用方/本对象据此提示）
     */
    data class Resolution(val psiClass: PsiClass?, val candidates: List<PsiClass>) {
        val ambiguous: Boolean get() = candidates.size > 1
    }

    /**
     * 按实体名解析实体类（须在 ReadAction 内调用）。
     *
     * @param entityName HQL 里的实体名（`from WmsInventoryEntity` → `WmsInventoryEntity`）
     * @param context    HQL/DAO 所在 PSI 元素（决定模块可见性）；null = 无上下文，按既有次序解析
     */
    fun resolveByName(project: Project, entityName: String, context: PsiElement?): Resolution {
        if (entityName.isBlank()) return Resolution(null, emptyList())
        val pool = candidatePool(project, entityName, context)
        if (pool.isEmpty()) return Resolution(null, emptyList())
        val chosen = pool.sortedWith(
            compareBy(
                { if (hasExplicitEntityName(it, entityName)) 0 else 1 },
                { if (isInContextModule(project, it, context)) 0 else 1 },
                { it.qualifiedName ?: it.name ?: "" },
            ),
        ).first()
        if (pool.size > 1) notifyAmbiguityOnce(project, entityName, chosen, pool)
        return Resolution(chosen, pool)
    }

    /** 只要选中类的便捷形态。 */
    fun resolveClassByName(project: Project, entityName: String, context: PsiElement?): PsiClass? =
        resolveByName(project, entityName, context).psiClass

    // ------------------------------------------------------------------
    // 候选集合
    // ------------------------------------------------------------------

    /**
     * 候选集合：模块可见范围 → 项目范围 → 全范围，**取第一个非空档**（不再合并，避免把不可见的
     * 同名类混进来）。无 [context] 时跳过第一档，与既有「项目优先、再全范围」次序一致。
     */
    private fun candidatePool(project: Project, name: String, context: PsiElement?): List<PsiClass> {
        val cache = PsiShortNamesCache.getInstance(project)
        val module = contextModule(project, context)
        if (module != null) {
            val visible = cache.getClassesByName(name, GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module))
            if (visible.isNotEmpty()) return visible.toList()
        }
        val inProject = cache.getClassesByName(name, GlobalSearchScope.projectScope(project))
        if (inProject.isNotEmpty()) return inProject.toList()
        return cache.getClassesByName(name, GlobalSearchScope.allScope(project)).toList()
    }

    /** [context] 所在模块（拿不到返回 null——如实体本身来自无模块的库、或 context 为 null）。 */
    private fun contextModule(project: Project, context: PsiElement?): Module? = runCatching {
        val vf = when (context) {
            null -> return null
            is PsiFile -> context.virtualFile
            else -> context.containingFile?.virtualFile
        } ?: return null
        ProjectFileIndex.getInstance(project).getModuleForFile(vf)
    }.getOrNull()

    private fun isInContextModule(project: Project, clazz: PsiClass, context: PsiElement?): Boolean {
        val module = contextModule(project, context) ?: return false
        return contextModule(project, clazz) == module
    }

    /** 类上是否写了**显式** `@Entity(name = 实体名)`（未写 name 时 [JpaMetadataReader.entityNameOf] 返回 null）。 */
    private fun hasExplicitEntityName(clazz: PsiClass, entityName: String): Boolean = runCatching {
        JpaMetadataReader.entityNameOf(clazz)?.equals(entityName, ignoreCase = true) == true
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // 歧义提示（不静默猜）
    // ------------------------------------------------------------------

    /**
     * 同名候选 >1 时提示一次（每项目 + 每个实体名一次，避免刷屏）。
     *
     * 提示而不阻断：选中结果仍然返回，用户据此知道「为什么预览认成了这个类」。
     */
    private fun notifyAmbiguityOnce(project: Project, entityName: String, chosen: PsiClass, pool: List<PsiClass>) {
        val key = entityName.lowercase()
        val seen = project.getUserData(AMBIGUITY_NOTIFIED_KEY) ?: mutableSetOf<String>().also {
            project.putUserData(AMBIGUITY_NOTIFIED_KEY, it)
        }
        if (!seen.add(key)) return
        val chosenName = chosen.qualifiedName ?: chosen.name ?: "?"
        val others = pool.mapNotNull { it.qualifiedName ?: it.name }.filter { it != chosenName }
        LOG.info(
            "[T-EntityNameScope] 实体名 $entityName 有 ${pool.size} 个同名候选，已选 $chosenName；" +
                "其余候选：${others.joinToString()}",
        )
        ambiguitySinkForTest?.invoke(project, entityName, chosenName, others)
            ?: runCatching {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(PREVIEW_NOTIFICATION_GROUP)
                    .createNotification(
                        MpSqlVisualizerBundle.message("preview.entityNameAmbiguity.title", entityName),
                        MpSqlVisualizerBundle.message(
                            "preview.entityNameAmbiguity.content",
                            chosenName,
                            others.joinToString("、"),
                        ),
                        NotificationType.WARNING,
                    )
                    .notify(project)
            }
    }

    /** 已提示过的实体名（每项目一次）。 */
    private val AMBIGUITY_NOTIFIED_KEY = Key.create<MutableSet<String>>("mp-visualizer.entityNameAmbiguityNotified")

    private const val PREVIEW_NOTIFICATION_GROUP = "cn.song.mybatisplus.visualizer.preview"

    /** 测试缝：歧义提示替身（null = 发预览通知）；用于断言「选谁 + 提示了什么」而不真弹通知。 */
    internal var ambiguitySinkForTest: ((Project, String, String, List<String>) -> Unit)? = null

    /** 测试访问器：清掉「已提示过」登记。 */
    internal fun clearAmbiguityNotifiedForTest(project: Project) {
        project.putUserData(AMBIGUITY_NOTIFIED_KEY, null)
    }
}
