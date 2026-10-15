package cn.song.mybatisplus.visualizer.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * O-145 的模块归属解析器。
 *
 * 不使用 `findModuleForFile` 的单值便利入口：共享 content/source root 时它会隐藏歧义。本实现枚举
 * [ModuleManager.getModules]，逐个以 [ModuleUtilCore.moduleContainsFile] 判断并保留全部候选。
 */
object ModuleIdentityResolver {

    fun resolve(project: Project, file: VirtualFile): ModuleIdentityResolution {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return resolveCandidates(ModuleManager.getInstance(project).modules.asList()) { module ->
            ModuleUtilCore.moduleContainsFile(module, file, false)
        }
    }

    /** 测试与调用边界共用的候选收口：输入已经是平台判定“包含该文件”的模块事实。 */
    internal fun resolveCandidates(
        modules: Iterable<Module>,
        containsFile: (Module) -> Boolean,
    ): ModuleIdentityResolution {
        val identities = modules.asSequence()
            .filterNot { it.isDisposed }
            .filter(containsFile)
            .map(::identityOf)
            .toList()

        return when (identities.size) {
            0 -> ModuleIdentityResolution.None
            1 -> ModuleIdentityResolution.Unique(identities.single())
            else -> ModuleIdentityResolution.Ambiguous(identities)
        }
    }

    /**
     * 外部系统的 linked project id 优先于可变模块名；缺 id 时退到 linked path，再退模块文件和名称。
     * Project 本身已构成命名空间，因此 canonical key 不拼本机绝对 root path，避免项目搬家即失效。
     */
    fun identityOf(module: Module): ModuleIdentity {
        val external = ExternalSystemModulePropertyManager.getInstance(module)
        val systemId = external.getExternalSystemId()?.trim().orEmpty()
        val linkedProjectId = external.getLinkedProjectId()?.trim().orEmpty()
        if (systemId.isNotEmpty() && linkedProjectId.isNotEmpty()) {
            return ModuleIdentity(
                module,
                "external:$systemId:$linkedProjectId",
                ModuleKeySource.EXTERNAL_SYSTEM_ID,
            )
        }

        val linkedProjectPath = external.getLinkedProjectPath()?.trim().orEmpty()
        if (systemId.isNotEmpty() && linkedProjectPath.isNotEmpty()) {
            return ModuleIdentity(
                module,
                "external-path:$systemId:${normalizePath(linkedProjectPath)}",
                ModuleKeySource.EXTERNAL_SYSTEM_PATH,
            )
        }

        val moduleFilePath = module.moduleFilePath.trim()
        if (moduleFilePath.isNotEmpty()) {
            return ModuleIdentity(
                module,
                "module-file:${normalizePath(moduleFilePath)}",
                ModuleKeySource.MODULE_FILE,
            )
        }

        return ModuleIdentity(module, "module-name:${module.name}", ModuleKeySource.MODULE_NAME)
    }

    private fun normalizePath(path: String): String =
        FileUtil.toSystemIndependentName(path).trimEnd('/')
}
