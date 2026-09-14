package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager

/**
 * F-44-7：SqlSourceInterceptor 自动注入核心。
 *
 * 在用户项目中自动生成拦截器类和 Spring 配置类，
 * 应用重启后实时日志携带源码位置，实现精确跳转。
 *
 * 幂等性：注入前检查文件是否已存在，已存在则跳过。
 * T-JumpPath-04：多模块工程逐模块注入（旧实现只落第一个模块，用户手工拷贝到其他模块
 * 引发拷贝件生成期 basePackage 常量漏改的连锁缺陷），结果聚合规则见 [decideAggregate]。
 */
sealed class InjectionResult {
    /** 注入成功（多模块时消息含成功模块清单）。 */
    data class Success(val message: String) : InjectionResult()

    /** 项目类型不支持。 */
    data object UnsupportedProject : InjectionResult()

    /** 无法检测项目结构。 */
    data object CannotDetectStructure : InjectionResult()

    /** 文件已存在（幂等跳过）。 */
    data object AlreadyExists : InjectionResult()
}

object SqlSourceInterceptorAutoInjector {

    private val LOG = Logger.getInstance(SqlSourceInterceptorAutoInjector::class.java)

    /** 检测阶段产物（T-Perf-10：后台 ReadAction 检测 → EDT 最小写创建）。 */
    private sealed class PrepareOutcome {
        data object CannotDetectStructure : PrepareOutcome()
        data object UnsupportedProject : PrepareOutcome()
        data object AlreadyExists : PrepareOutcome()

        /** [ready] = 待注入的支持模块；[existed] = 已注入过的支持模块名（幂等跳过）。 */
        data class Ready(val ready: List<ProjectStructure>, val existed: List<String>) : PrepareOutcome()
    }

    /** 自动注入入口（同步语义保留——测试/非 EDT 场景；EDT 调用请用 [injectAsync]）。 */
    fun inject(project: Project): InjectionResult {
        return when (val outcome = detectForInject(project)) {
            PrepareOutcome.CannotDetectStructure -> InjectionResult.CannotDetectStructure
            PrepareOutcome.UnsupportedProject -> InjectionResult.UnsupportedProject
            PrepareOutcome.AlreadyExists -> InjectionResult.AlreadyExists
            is PrepareOutcome.Ready -> createFiles(project, outcome)
        }
    }

    /**
     * T-Perf-10：异步注入入口（设置页 EDT 按钮回调用）——
     * 检测阶段（[ProjectTypeDetector.detectPerModule] 的 JavaPsiFacade.findClass /
     * AnnotatedElementsSearch / 源码目录递归遍历 + 拦截器存在性 VFS 查找）后台 ReadAction 执行；
     * 仅文件创建保留最小 WriteCommandAction（EDT 回调内执行，每模块 2 个模板文件，毫秒级）；
     * 结果经回调返回（EDT）。
     */
    fun injectAsync(project: Project, onResult: (InjectionResult) -> Unit) {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
            val outcome = com.intellij.openapi.application.ReadAction.compute<PrepareOutcome, Throwable> {
                detectForInject(project)
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                onResult(
                    when (outcome) {
                        PrepareOutcome.CannotDetectStructure -> InjectionResult.CannotDetectStructure
                        PrepareOutcome.UnsupportedProject -> InjectionResult.UnsupportedProject
                        PrepareOutcome.AlreadyExists -> InjectionResult.AlreadyExists
                        is PrepareOutcome.Ready -> createFiles(project, outcome)
                    },
                )
            }
        }
    }

    /** 检测阶段（须在 ReadAction 内调用——PSI 索引/目录访问）。 */
    private fun detectForInject(project: Project): PrepareOutcome {
        val structures = ProjectTypeDetector.detectPerModule(project)
        if (structures.isEmpty()) {
            // 无任何支持模块：区分「项目里根本没有可用源码结构」与「有结构但都不是 MP 工程」
            return if (hasAnySourceRoot(project)) {
                PrepareOutcome.UnsupportedProject
            } else {
                PrepareOutcome.CannotDetectStructure
            }
        }
        val (existedStructures, ready) = structures.partition { interceptorExists(it.sourceRoot, it.basePackage ?: "") }
        if (ready.isEmpty()) return PrepareOutcome.AlreadyExists
        val existed = existedStructures.mapNotNull { it.moduleName }
        return PrepareOutcome.Ready(ready, existed)
    }

    /** 项目是否存在任何模块源根（区分 CannotDetectStructure 与 UnsupportedProject）。 */
    private fun hasAnySourceRoot(project: Project): Boolean =
        ModuleManager.getInstance(project).modules.any {
            ModuleRootManager.getInstance(it).sourceRoots.isNotEmpty()
        }

    /**
     * 逐模块结果 → 用户可见聚合（纯决策，消息经 [successMessageFor] 注入——表驱动单测驱动本函数）。
     *
     * 规则（计划 §4.4）：至少一模块新注入 → Success（成功优先，失败模块并入由调用方日志留痕）；
     * 无新注入但有已存在模块 → AlreadyExists；全失败/无支持模块 → UnsupportedProject。
     */
    internal fun decideAggregate(
        injected: List<String>,
        existed: List<String>,
        failed: List<String>,
        successMessageFor: (List<String>) -> String,
    ): InjectionResult = when {
        injected.isNotEmpty() -> InjectionResult.Success(successMessageFor(injected))
        existed.isNotEmpty() -> InjectionResult.AlreadyExists
        else -> InjectionResult.UnsupportedProject
    }

    /** 文件创建（EDT；逐模块最小 WriteCommandAction——T-Perf-10：reformat 省略，模板即规范缩进）。 */
    private fun createFiles(project: Project, outcome: PrepareOutcome.Ready): InjectionResult {
        val injected = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (structure in outcome.ready) {
            val moduleName = structure.moduleName ?: structure.basePackage ?: "?"
            val result = when (structure.type) {
                ProjectType.SPRING_BOOT -> injectSpringBoot(project, structure.basePackage!!, structure.sourceRoot)
                else -> injectMyBatisNative(project, structure.basePackage ?: "", structure.sourceRoot)
            }
            if (result is InjectionResult.Success) {
                injected += moduleName
            } else {
                failed += moduleName
                LOG.info("SqlSourceInterceptorAutoInjector: 模块 $moduleName 注入未成功（$result），继续其余模块")
            }
        }
        return decideAggregate(injected, outcome.existed, failed) { names -> successMessage(names, outcome) }
    }

    /** 成功消息：单模块沿用既有三参文案；多模块列模块清单（T-JumpPath-04 聚合形态）。 */
    private fun successMessage(injectedNames: List<String>, outcome: PrepareOutcome.Ready): String {
        val bundle = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
        return if (injectedNames.size == 1) {
            val structure = outcome.ready.first { (it.moduleName ?: it.basePackage ?: "?") == injectedNames[0] }
            bundle.message(
                "log.inject.success",
                SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME,
                SourceInterceptorTemplates.CONFIG_FILE_NAME,
                structure.basePackage ?: injectedNames[0],
            )
        } else {
            bundle.message(
                "log.inject.successMulti",
                injectedNames.size.toString(),
                injectedNames.joinToString("、"),
                SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME,
                SourceInterceptorTemplates.CONFIG_FILE_NAME,
            )
        }
    }

    /** 检查拦截器类是否已存在。 */
    private fun interceptorExists(sourceRoot: VirtualFile, basePackage: String): Boolean {
        val configDir = "${basePackage.replace('.', '/')}/config"
        val interceptorPath = "${sourceRoot.path}/$configDir/${SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME}"
        val file = LocalFileSystem.getInstance().findFileByPath(interceptorPath)
        return file != null && file.exists()
    }

    /** Spring Boot 项目注入（单模块；聚合消息在 [createFiles] 统一组装，此处 message 仅占位）。 */
    private fun injectSpringBoot(project: Project, basePackage: String, sourceRoot: VirtualFile): InjectionResult {
        return try {
            WriteCommandAction.runWriteCommandAction<InjectionResult>(project) {
                // 创建 config 包目录
                val configDir = createPackageDirectories(project, sourceRoot, "$basePackage.config")
                    ?: return@runWriteCommandAction InjectionResult.CannotDetectStructure

                // 创建拦截器类
                createJavaFile(
                    project, configDir,
                    SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME,
                    SourceInterceptorTemplates.generateInterceptorClass(basePackage),
                )

                // 创建配置类
                createJavaFile(
                    project, configDir,
                    SourceInterceptorTemplates.CONFIG_FILE_NAME,
                    SourceInterceptorTemplates.generateSpringBootConfigClass(basePackage),
                )

                InjectionResult.Success(basePackage)
            }.also {
                LOG.info("SqlSourceInterceptorAutoInjector: Spring Boot 注入${if (it is InjectionResult.Success) "成功" else "未成功"}，basePackage=$basePackage")
            }
        } catch (e: Exception) {
            LOG.warn("SqlSourceInterceptorAutoInjector: Spring Boot 注入失败", e)
            InjectionResult.CannotDetectStructure
        }
    }

    /** MyBatis 原生项目注入（TODO：后续实现）。 */
    private fun injectMyBatisNative(
        project: Project,
        basePackage: String,
        sourceRoot: VirtualFile,
    ): InjectionResult {
        // TODO：检测 mybatis-config.xml 并添加拦截器配置
        LOG.info("SqlSourceInterceptorAutoInjector: MyBatis 原生项目暂不支持自动注入")
        return InjectionResult.UnsupportedProject
    }

    /** 创建包目录（递归）。 */
    private fun createPackageDirectories(
        project: Project,
        sourceRoot: VirtualFile,
        packageQualifiedName: String,
    ): PsiDirectory? {
        val psiManager = PsiManager.getInstance(project)
        var currentDir = psiManager.findDirectory(sourceRoot) ?: return null

        for (pkgPart in packageQualifiedName.split('.')) {
            val existing = currentDir.findSubdirectory(pkgPart)
            if (existing != null) {
                currentDir = existing
            } else {
                currentDir = currentDir.createSubdirectory(pkgPart)
            }
        }

        return currentDir
    }

    /** 创建 Java 文件（PSI；T-Perf-10：reformat 省略——模板内容本身即规范缩进，reformat 是平台标注的慢操作）。 */
    private fun createJavaFile(project: Project, directory: PsiDirectory, fileName: String, content: String) {
        // T-DepApi：StdLanguages.JAVA 全平台标 deprecated——JavaLanguage.INSTANCE（java 插件，
        // plugin.xml 已声明 com.intellij.java 依赖）是官方等价物，243/253/262 javap 实证均无弃用标记；
        // findLanguageByID("JAVA") 找不到时（Java 插件被禁用的极端场景）回退 null → 交给调用方报错，
        // 原本 StdLanguages 兜底也依赖同一插件提供 Language 实现，语义不变。
        val javaLanguage = com.intellij.lang.java.JavaLanguage.INSTANCE
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText(fileName, javaLanguage, content)

        // 添加到目录
        directory.add(psiFile)
    }
}
