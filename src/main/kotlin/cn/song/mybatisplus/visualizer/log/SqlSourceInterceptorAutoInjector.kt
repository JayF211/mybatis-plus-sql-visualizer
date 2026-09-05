package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager

/**
 * F-44-7：SqlSourceInterceptor 自动注入核心。
 *
 * 在用户项目中自动生成拦截器类和 Spring 配置类，
 * 应用重启后实时日志携带源码位置，实现精确跳转。
 *
 * 幂等性：注入前检查文件是否已存在，已存在则跳过。
 */
sealed class InjectionResult {
    /** 注入成功。 */
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
        data class Ready(val type: ProjectType, val basePackage: String, val sourceRoot: VirtualFile) : PrepareOutcome()
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
     * 检测阶段（[ProjectTypeDetector.detect] 的 AnnotatedElementsSearch.findAll /
     * allScope findClass / 源码目录递归遍历 + 拦截器存在性 VFS 查找）后台 ReadAction 执行；
     * 仅文件创建保留最小 WriteCommandAction（EDT 回调内执行，2 个模板文件，毫秒级）；
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
                    }
                )
            }
        }
    }

    /** 检测阶段（须在 ReadAction 内调用——PSI 索引/目录访问）。 */
    private fun detectForInject(project: Project): PrepareOutcome {
        val structure = ProjectTypeDetector.detect(project)
            ?: return PrepareOutcome.CannotDetectStructure

        if (structure.type == ProjectType.UNSUPPORTED) {
            return PrepareOutcome.UnsupportedProject
        }

        val basePackage = structure.basePackage
            ?: return PrepareOutcome.CannotDetectStructure

        val sourceRoot = structure.sourceRoot

        // 检查是否已存在（幂等性）
        if (interceptorExists(sourceRoot, basePackage)) {
            return PrepareOutcome.AlreadyExists
        }
        return PrepareOutcome.Ready(structure.type, basePackage, sourceRoot)
    }

    /** 文件创建（EDT；最小 WriteCommandAction——T-Perf-10：reformat 省略，模板内容本身即规范缩进）。 */
    private fun createFiles(project: Project, outcome: PrepareOutcome.Ready): InjectionResult {
        return when (outcome.type) {
            ProjectType.SPRING_BOOT -> injectSpringBoot(project, outcome.basePackage, outcome.sourceRoot)
            ProjectType.MYBATIS_NATIVE -> injectMyBatisNative(project, outcome.basePackage, outcome.sourceRoot)
            else -> InjectionResult.UnsupportedProject
        }
    }

    /** 检查拦截器类是否已存在。 */
    private fun interceptorExists(sourceRoot: VirtualFile, basePackage: String): Boolean {
        val configDir = "${basePackage.replace('.', '/')}/config"
        val interceptorPath = "${sourceRoot.path}/$configDir/${SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME}"
        val file = LocalFileSystem.getInstance().findFileByPath(interceptorPath)
        return file != null && file.exists()
    }

    /** Spring Boot 项目注入。 */
    private fun injectSpringBoot(project: Project, basePackage: String, sourceRoot: VirtualFile): InjectionResult {
        return try {
            val result = WriteCommandAction.runWriteCommandAction<InjectionResult>(project) {
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

                InjectionResult.Success(
                    "已生成 ${SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME} 和 ${SourceInterceptorTemplates.CONFIG_FILE_NAME}，" +
                        "位于 ${basePackage}.config 包下，重启应用后生效。",
                )
            }
            LOG.info("SqlSourceInterceptorAutoInjector: Spring Boot 注入成功，basePackage=$basePackage")
            result
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
        val javaLanguage = com.intellij.lang.Language.findLanguageByID("JAVA")
            ?: com.intellij.lang.StdLanguages.JAVA
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText(fileName, javaLanguage, content)

        // 添加到目录
        directory.add(psiFile)
    }
}
