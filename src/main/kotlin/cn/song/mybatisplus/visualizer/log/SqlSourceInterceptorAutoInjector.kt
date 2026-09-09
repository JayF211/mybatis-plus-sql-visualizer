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
import com.intellij.psi.codeStyle.CodeStyleManager

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

    /** 自动注入入口。 */
    fun inject(project: Project): InjectionResult {
        // 1. 检测项目结构
        val structure = ProjectTypeDetector.detect(project)
            ?: return InjectionResult.CannotDetectStructure

        if (structure.type == ProjectType.UNSUPPORTED) {
            return InjectionResult.UnsupportedProject
        }

        val basePackage = structure.basePackage
            ?: return InjectionResult.CannotDetectStructure

        val sourceRoot = structure.sourceRoot

        // 2. 检查是否已存在（幂等性）
        if (interceptorExists(sourceRoot, basePackage)) {
            return InjectionResult.AlreadyExists
        }

        // 3. 生成文件
        return when (structure.type) {
            ProjectType.SPRING_BOOT -> injectSpringBoot(project, basePackage, sourceRoot)
            ProjectType.MYBATIS_NATIVE -> injectMyBatisNative(project, basePackage, sourceRoot)
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

    /** 创建 Java 文件（PSI）。 */
    private fun createJavaFile(project: Project, directory: PsiDirectory, fileName: String, content: String) {
        // T-DepApi：StdLanguages.JAVA 全平台标 deprecated——JavaLanguage.INSTANCE（java 插件，
        // plugin.xml 已声明 com.intellij.java 依赖）是官方等价物，243/253/262 javap 实证均无弃用标记；
        // findLanguageByID("JAVA") 找不到时（Java 插件被禁用的极端场景）回退 null → 交给调用方报错，
        // 原本 StdLanguages 兜底也依赖同一插件提供 Language 实现，语义不变。
        val javaLanguage = com.intellij.lang.java.JavaLanguage.INSTANCE
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText(fileName, javaLanguage, content)

        // 代码格式化
        try {
            CodeStyleManager.getInstance(project).reformat(psiFile)
        } catch (_: Exception) {
            // 格式化失败不影响文件创建
        }

        // 添加到目录
        directory.add(psiFile)
    }
}
