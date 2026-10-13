package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * 工程 Hibernate 命名约定探测（T-EmbeddedColPrefix 方案 A）。
 *
 * 实体本身不携带「嵌入列名怎么拼」这一信息——它由 Hibernate 的隐式命名策略决定
 * （见 [EmbeddedColumnNaming]）。本对象从工程配置里找这个信号：**在实体所在模块**的
 * `src/main/resources` 下扫 `application*.yml/.yaml/.properties`，命中组件路径命名策略的
 * 写法即视为该模块用前缀约定。
 *
 * 为什么按**模块**而不是整个工程扫（20261013 修正）：命名策略是**每个持久化单元**的事，
 * 而多模块工程里各模块可以有各自的 `application.yml`。按工程全扫时，只要任何一个兄弟模块
 * 声明了 component-path，其它模块的嵌入列就会被错误套上前缀（demo 仓同时放了「无前缀」与
 * 「组件路径」两套语料，正是这种形态）。故取「实体文件所在模块」的资源目录为扫描范围；
 * 认不出模块布局（无 `pom.xml`/`build.gradle*` 上溯命中）时回落到工程全扫，行为与改前一致。
 *
 * 其它设计取舍：
 *  - **只认强信号**：匹配命名策略类名/关键字，不做「看着像」的模糊判定。判不出返回 false，
 *    由设置项兜底（[EmbeddedColumnNamingResolver]）——宁可漏判（用户可显式开）也不误判；
 *  - **纯文本判定与取文件分离**：[indicatesComponentPath] 与 [nearestModuleRootPath] 都不碰
 *    平台 API，可脱离工程单测；
 *  - **按 PSI 版本 + 扫描范围缓存**：实体解析会被反复触发，每次读配置不可接受。
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

    /** 配置文件主名前缀（Spring Boot 的 `application` 族）。 */
    private const val CONFIG_FILE_PREFIX = "application"

    /** 配置文件扩展名。 */
    private val CONFIG_EXTENSIONS = setOf("yml", "yaml", "properties")

    /** 模块根标记文件（任一命中即视为模块根）。 */
    internal val MODULE_MARKER_FILES = listOf("pom.xml", "build.gradle", "build.gradle.kts")

    /** 模块内配置所在相对路径。 */
    private const val RESOURCES_RELATIVE_PATH = "src/main/resources"

    /** 未识别出模块布局时的缓存键（工程全扫）。 */
    private const val PROJECT_WIDE_KEY = "<project-wide>"

    private data class Cached(val stamp: Long, val value: Boolean)

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * 工程（或更准确地说：[anchor] 所在模块）是否使用「组件路径」嵌入列命名。
     *
     * @param anchor 正在解析的实体文件；null 时按整个工程扫（调用方拿不到文件时的保守兜底）
     */
    fun projectUsesComponentPath(project: Project, anchor: VirtualFile? = null): Boolean {
        val resourcesDir = moduleResourcesDir(anchor)
        val key = resourcesDir?.path ?: PROJECT_WIDE_KEY
        val cacheKey = "${project.basePath ?: project.name}|$key"
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        cache[cacheKey]?.let { if (it.stamp == stamp) return it.value }
        val value = ReadAction.compute<Boolean, RuntimeException> {
            if (resourcesDir != null) scanDir(resourcesDir) else scanProject(project)
        }
        cache[cacheKey] = Cached(stamp, value)
        return value
    }

    /** 实体文件所在模块的 `src/main/resources`；认不出模块布局时返回 null（回落工程全扫）。 */
    private fun moduleResourcesDir(anchor: VirtualFile?): VirtualFile? {
        val anchorFile = anchor ?: return null
        val fs = LocalFileSystem.getInstance()
        val moduleRootPath = nearestModuleRootPath(anchorFile.path) { path ->
            val dir = fs.findFileByPath(path) ?: return@nearestModuleRootPath false
            MODULE_MARKER_FILES.any { dir.findChild(it) != null }
        } ?: return null
        val moduleRoot = fs.findFileByPath(moduleRootPath) ?: return null
        val resources = VfsUtilCore.findRelativeFile(RESOURCES_RELATIVE_PATH, moduleRoot)
        return resources?.takeIf { it.isDirectory }
    }

    private fun scanDir(resourcesDir: VirtualFile): Boolean = runCatching {
        resourcesDir.children
            .filter { !it.isDirectory && isApplicationConfig(it.name) }
            .any { indicatesComponentPath(readText(it)) }
    }.getOrDefault(false)

    private fun scanProject(project: Project): Boolean = runCatching {
        val scope = GlobalSearchScope.projectScope(project)
        CONFIG_EXTENSIONS.asSequence()
            .flatMap { ext ->
                FilenameIndex.getAllFilesByExt(project, ext, scope).asSequence()
                    .filter { isApplicationConfig(it.name) }
            }
            .any { indicatesComponentPath(readText(it)) }
    }.getOrDefault(false)

    private fun isApplicationConfig(name: String): Boolean =
        name.startsWith(CONFIG_FILE_PREFIX) && CONFIG_EXTENSIONS.any { name.endsWith(".$it") }

    private fun readText(file: VirtualFile): String = runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")

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

    /**
     * 纯路径判定（可单测）：自 [anchorPath] 向上找最近一个「含模块标记文件」的目录。
     *
     * @param anchorPath 实体文件的绝对路径（`/` 分隔）
     * @param isModuleDir 判定某目录是否为模块根（真实实现查 `pom.xml`/`build.gradle*` 是否存在）
     * @return 模块根路径；一路到根都没命中返回 null
     */
    internal fun nearestModuleRootPath(anchorPath: String, isModuleDir: (String) -> Boolean): String? {
        var dir = anchorPath.substringBeforeLast('/', "")
        while (dir.isNotEmpty()) {
            if (isModuleDir(dir)) return dir
            val parent = dir.substringBeforeLast('/', "")
            if (parent == dir) return null
            dir = parent
        }
        return null
    }
}
