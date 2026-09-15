package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
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

    /** 用户在模块选择弹框取消（T-ModPick：多模块工程不再全量注入，先让用户勾选）。 */
    data object Cancelled : InjectionResult()
}

object SqlSourceInterceptorAutoInjector {

    private val LOG = Logger.getInstance(SqlSourceInterceptorAutoInjector::class.java)

    /** 检测阶段产物（T-Perf-10：后台 ReadAction 检测 → EDT 最小写创建）。 */
    private sealed class PrepareOutcome {
        data object CannotDetectStructure : PrepareOutcome()
        data object UnsupportedProject : PrepareOutcome()
        data object AlreadyExists : PrepareOutcome()

        /**
         * [ready] = 待注入的支持模块；[existed] = 已注入过且为新版模板的支持模块名（幂等跳过）；
         * [upgradable] = 已存在旧形态模板、可内容升级的支持模块（T-IntrUpg-03）。
         */
        data class Ready(
            val ready: List<ProjectStructure>,
            val existed: List<String>,
            val upgradable: List<ProjectStructure> = emptyList(),
        ) : PrepareOutcome()
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
     *
     * T-ModPick：检测出多个待处理模块时先在 EDT 弹勾选框让用户选择（单模块不打扰直注），
     * 取消 → [InjectionResult.Cancelled]。
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
                    when (val prepared = outcome) {
                        PrepareOutcome.CannotDetectStructure -> InjectionResult.CannotDetectStructure
                        PrepareOutcome.UnsupportedProject -> InjectionResult.UnsupportedProject
                        PrepareOutcome.AlreadyExists -> InjectionResult.AlreadyExists
                        is PrepareOutcome.Ready -> {
                            val picked = pickModules(project, prepared)
                                ?: return@invokeLater onResult(InjectionResult.Cancelled)
                            createFiles(project, picked)
                        }
                    },
                )
            }
        }
    }

    /** 模块选择键（与 [createFiles] 的模块名口径一致）。 */
    private fun moduleKey(structure: ProjectStructure): String =
        structure.moduleName ?: structure.basePackage ?: "?"

    /**
     * T-ModPick：待处理模块（新建 + 升级）多于一个时弹勾选框（用户反馈：多模块应让用户
     * 选择而非全部生成）；单模块直接返回不打扰。取消 → null（调用方回调 Cancelled）。
     */
    private fun pickModules(project: Project, outcome: PrepareOutcome.Ready): PrepareOutcome.Ready? {
        if (outcome.ready.size + outcome.upgradable.size <= 1) return outcome
        val rows = buildList {
            outcome.ready.forEach { add(InjectionModulePickerDialog.Row(moduleKey(it), "${moduleKey(it)} · ${it.basePackage ?: "?"}", false)) }
            outcome.upgradable.forEach { add(InjectionModulePickerDialog.Row(moduleKey(it), "${moduleKey(it)} · ${it.basePackage ?: "?"}", true)) }
        }
        val dialog = InjectionModulePickerDialog(project, rows)
        if (!dialog.showAndGet()) return null
        val selected = dialog.selectedKeys()
        return outcome.copy(
            ready = outcome.ready.filter { selected.contains(moduleKey(it)) },
            upgradable = outcome.upgradable.filter { selected.contains(moduleKey(it)) },
        )
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
        val ready = mutableListOf<ProjectStructure>()
        val existed = mutableListOf<String>()
        val upgradable = mutableListOf<ProjectStructure>()
        for (structure in structures) {
            val existing = interceptorFile(structure.sourceRoot, structure.basePackage ?: "")
            if (existing == null) {
                ready += structure
                continue
            }
            // T-IntrUpg-03：已存在拦截器文件 → 读文本判形态。旧模板产物（isSelf 常量拼接
            // 与运行类可能分叉）纳入升级清单，EDT 内容重写；其余（新版/用户自写）幂等跳过。
            val text = runCatching { VfsUtil.loadText(existing) }.getOrNull()
            if (text != null && needsInterceptorUpgrade(text)) {
                upgradable += structure
            } else {
                structure.moduleName?.let { existed += it }
            }
        }
        if (ready.isEmpty() && upgradable.isEmpty()) return PrepareOutcome.AlreadyExists
        return PrepareOutcome.Ready(ready, existed, upgradable)
    }

    /**
     * 已存在拦截器文件是否需要升级（T-IntrUpg-02，表驱动单测 [InjectionUpgradeDecisionTest]）。
     *
     * 三条件缺一不可：
     *  - 含模板生成标记：仅插件生成物可被覆盖，用户手改/自写文件永不动；
     *  - isSelf 为生成期常量拼接形态（旧模板特征 `cn.equals("`）：存在常量与运行类分叉缺陷；
     *  - 不含运行时比对 `getClass().getName()`：已是新模板则跳过。
     *
     * 判定基于运行时行为缺陷形态而非模板全文比对——模板其他部分后续演进不误判。
     */
    internal fun needsInterceptorUpgrade(text: String): Boolean =
        text.contains("自动生成于 SQL Visualizer 插件") &&
            text.contains("cn.equals(\"") &&
            !text.contains("getClass().getName()")

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
        val created = mutableListOf<String>()
        val upgraded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (structure in outcome.ready) {
            val moduleName = structure.moduleName ?: structure.basePackage ?: "?"
            val result = when (structure.type) {
                ProjectType.SPRING_BOOT -> injectSpringBoot(project, structure.basePackage!!, structure.sourceRoot)
                else -> injectMyBatisNative(project, structure.basePackage ?: "", structure.sourceRoot)
            }
            if (result is InjectionResult.Success) {
                created += moduleName
            } else {
                failed += moduleName
                LOG.info("SqlSourceInterceptorAutoInjector: 模块 $moduleName 注入未成功（$result），继续其余模块")
            }
        }
        // T-IntrUpg-03：旧形态模板内容升级（不新建、不搬文件——拷贝件所在包即检测到的
        // 模块基础包，重写内容后 package 声明与 isSelf 常量随所在包修正）。
        for (structure in outcome.upgradable) {
            val moduleName = structure.moduleName ?: structure.basePackage ?: "?"
            if (upgradeInterceptorFile(project, structure)) {
                upgraded += moduleName
            } else {
                failed += moduleName
            }
        }
        return decideAggregate(created + upgraded, outcome.existed, failed) { names ->
            successMessage(names, created, upgraded, outcome)
        }
    }

    /**
     * 单个旧形态拦截器文件内容升级（EDT 最小写）。文件定位失败/写失败 → false（调用方计 failed）。
     * 只重写拦截器文件，不动 Config——拷贝件场景 Config 的 appPackages 现值已正确（计划 §七）。
     */
    private fun upgradeInterceptorFile(project: Project, structure: ProjectStructure): Boolean {
        val basePackage = structure.basePackage ?: return false
        return try {
            WriteCommandAction.runWriteCommandAction<Boolean>(project) {
                val file = interceptorFile(structure.sourceRoot, basePackage) ?: return@runWriteCommandAction false
                VfsUtil.saveText(file, SourceInterceptorTemplates.generateInterceptorClass(basePackage))
                true
            }.also {
                LOG.info("SqlSourceInterceptorAutoInjector: 拦截器文件${if (it) "升级成功" else "升级未成功"}，basePackage=$basePackage")
            }
        } catch (e: Exception) {
            LOG.warn("SqlSourceInterceptorAutoInjector: 拦截器文件升级失败，basePackage=$basePackage", e)
            false
        }
    }

    /**
     * 成功消息：全部为升级 → 升级文案（已更新旧版拦截器文件）；
     * 单模块纯新建沿用既有三参文案；其余多模块/混合形态列模块清单（T-JumpPath-04 聚合形态）。
     */
    private fun successMessage(
        injectedNames: List<String>,
        created: List<String>,
        upgraded: List<String>,
        outcome: PrepareOutcome.Ready,
    ): String {
        val bundle = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
        return when {
            created.isEmpty() && upgraded.isNotEmpty() -> bundle.message(
                "log.inject.upgraded",
                upgraded.size.toString(),
                upgraded.joinToString("、"),
            )
            injectedNames.size == 1 -> {
                val structure = outcome.ready.first { (it.moduleName ?: it.basePackage ?: "?") == injectedNames[0] }
                bundle.message(
                    "log.inject.success",
                    SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME,
                    SourceInterceptorTemplates.CONFIG_FILE_NAME,
                    structure.basePackage ?: injectedNames[0],
                )
            }
            else -> bundle.message(
                "log.inject.successMulti",
                injectedNames.size.toString(),
                injectedNames.joinToString("、"),
                SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME,
                SourceInterceptorTemplates.CONFIG_FILE_NAME,
            )
        }
    }

    /** 拦截器类文件（不存在返回 null）。 */
    private fun interceptorFile(sourceRoot: VirtualFile, basePackage: String): VirtualFile? {
        val configDir = "${basePackage.replace('.', '/')}/config"
        val interceptorPath = "${sourceRoot.path}/$configDir/${SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME}"
        val file = LocalFileSystem.getInstance().findFileByPath(interceptorPath)
        return file?.takeIf { it.exists() }
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
