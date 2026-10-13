package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * 工程 Hibernate 命名约定探测（T-EmbeddedColPrefix 方案 A）。
 *
 * 实体本身不携带「嵌入列名怎么拼」这一信息——它由工程的 Hibernate 隐式命名策略决定
 * （见 [EmbeddedColumnNaming]）。本对象从工程配置里找这个信号：
 * 扫描 `application*.yml/.yaml/.properties`，命中组件路径命名策略的任一写法即视为该工程
 * 使用前缀约定。
 *
 * 设计取舍：
 *  - **只认强信号**：匹配命名策略类名/关键字，不做「看着像」的模糊判定。判不出返回 false，
 *    由设置项兜底（[EmbeddedColumnNamingResolver]）——宁可漏判（用户可显式开）也不误判
 *    （误判会把 JPA 默认工程的列名改错）；
 *  - **纯文本判定与扫描分离**：[indicatesComponentPath] 不碰平台 API，可脱离工程单测；
 *  - **按 PSI 版本缓存**：实体解析会被反复触发，每次扫工程配置文件不可接受。
 */
object HibernateNamingConfigProbe {

    /**
     * 组件路径命名策略的识别标记（全名短名 + Hibernate 配置关键字写法）。
     *
     * `ImplicitNamingStrategyComponentPathImpl` 是 Hibernate 5/6 的实现类；
     * `DefaultComponentSafeNamingStrategy` 是它移植前的 Hibernate 4 源类（老工程仍可能配这个名字）。
     */
    internal val COMPONENT_PATH_MARKERS = listOf(
        "ImplicitNamingStrategyComponentPathImpl",
        "DefaultComponentSafeNamingStrategy",
        "component-path",
        "component_path",
    )

    /** 配置文件候选扩展名（Spring Boot 常规 `application` 前缀文件）。 */
    private val CONFIG_EXTENSIONS = listOf("properties", "yml", "yaml")

    /** 配置文件主名前缀。 */
    private const val CONFIG_FILE_PREFIX = "application"

    private val KEY: Key<CachedValue<Boolean>> = Key.create("mpsv.embeddedNaming.componentPathProbe")

    /**
     * 工程是否使用「组件路径」嵌入列命名。
     *
     * 结果挂在 [PsiModificationTracker.MODIFICATION_COUNT] 上缓存——配置文件被编辑（PSI 变更）
     * 自动失效，无需自己管失效时机。
     */
    fun projectUsesComponentPath(project: Project): Boolean =
        CachedValuesManager.getManager(project).getCachedValue(
            project,
            KEY,
            CachedValueProvider {
                CachedValueProvider.Result.create(scan(project), PsiModificationTracker.MODIFICATION_COUNT)
            },
            false,
        )

    private fun scan(project: Project): Boolean = runCatching {
        ReadAction.compute<Boolean, RuntimeException> {
            val scope = GlobalSearchScope.projectScope(project)
            val psiManager = PsiManager.getInstance(project)
            CONFIG_EXTENSIONS.asSequence()
                .flatMap { ext ->
                    FilenameIndex.getAllFilesByExt(project, ext, scope).asSequence()
                        .filter { it.name.startsWith(CONFIG_FILE_PREFIX) }
                }
                .any { vf ->
                    val text = runCatching { psiManager.findFile(vf)?.text }.getOrNull()
                        ?: runCatching { VfsUtilCore.loadText(vf) }.getOrNull()
                        ?: return@any false
                    indicatesComponentPath(text)
                }
        }
    }.getOrDefault(false)

    /**
     * 纯文本判定（可单测）：配置文本里出现组件路径命名策略的任一写法即命中。
     *
     * 先剔除整行注释（`#` / `!` 起首，覆盖 properties 与 yml 两种注释形态）——被注释掉的
     * 配置不是生效配置，把它当命中会把工程判定反。
     */
    internal fun indicatesComponentPath(text: String): Boolean {
        val active = text.lineSequence()
            .filterNot {
                val trimmed = it.trimStart()
                trimmed.startsWith("#") || trimmed.startsWith("!")
            }
            .joinToString("\n")
        return COMPONENT_PATH_MARKERS.any { active.contains(it, ignoreCase = true) }
    }
}
