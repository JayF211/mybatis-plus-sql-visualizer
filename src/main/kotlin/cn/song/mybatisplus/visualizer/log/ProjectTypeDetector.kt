package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

/**
 * F-44-7：项目类型检测——自动识别 Spring Boot / MyBatis 原生项目，供 [SqlSourceInterceptorAutoInjector] 使用。
 *
 * 检测策略：
 *  - Spring Boot：搜索 @SpringBootApplication 注解类（AnnotatedElementsSearch）
 *  - MyBatis 原生：检测 SqlSessionFactory 类是否在 classpath
 *  - 包名：从 @SpringBootApplication 所在包提取，或从源码目录第一个 .java 文件推断
 */
enum class ProjectType {
    /** Spring Boot 项目（存在 @SpringBootApplication 注解类）。 */
    SPRING_BOOT,
    /** MyBatis 原生项目（存在 mybatis-config.xml 或 SqlSessionFactory 配置）。 */
    MYBATIS_NATIVE,
    /** 不支持的项目类型。 */
    UNSUPPORTED,
}

/** 项目结构信息。 */
data class ProjectStructure(
    val type: ProjectType,
    val basePackage: String?,
    val sourceRoot: VirtualFile,
)

object ProjectTypeDetector {

    private val LOG = Logger.getInstance(ProjectTypeDetector::class.java)

    /** 检测项目类型和结构。 */
    fun detect(project: Project): ProjectStructure? {
        // T-PreviewDocLock2 F2：检测体全量 PSI 探测（ModuleManager/ModuleRootManager 源根、
        // JavaPsiFacade.findClass、AnnotatedElementsSearch、PsiManager.findDirectory、file.text）
        // 均在 262 平台要求读权限；其中 JavaPsiFacade.findClass 是 assertReadAccess **硬抛**（非软
        // 断言），在 EDT 上裸调会直接 RuntimeException——调用方（设置页勾选回调）先前靠 catch(Exception)
        // 吞掉表现为「注入静默失败」。这里把探测纯函数包 ReadAction（纯读、无写操作，EDT 上同步语义
        // 不变）；调用方 [SqlSourceInterceptorAutoInjector.inject] 的文件创建段仍走 WriteCommandAction，
        // 与探测分开。
        return ReadAction.compute<ProjectStructure?, Throwable> { detectSync(project) }
    }

    /** 检测主体（ReadAction 内执行）。 */
    private fun detectSync(project: Project): ProjectStructure? {
        val modules = ModuleManager.getInstance(project).modules
        if (modules.isEmpty()) return null

        // 获取主源码目录
        val sourceRoot = findMainSourceRoot(modules) ?: return null

        // 检测 Spring Boot
        if (isSpringBootProject(project)) {
            val basePackage = findBasePackage(project, sourceRoot)
            if (basePackage != null) {
                LOG.info("ProjectTypeDetector: Spring Boot 项目，basePackage=$basePackage, sourceRoot=${sourceRoot.path}")
                return ProjectStructure(ProjectType.SPRING_BOOT, basePackage, sourceRoot)
            }
        }

        // 检测 MyBatis 原生
        if (isMyBatisNativeProject(project)) {
            val basePackage = findBasePackage(project, sourceRoot)
            if (basePackage != null) {
                LOG.info("ProjectTypeDetector: MyBatis 原生项目，basePackage=$basePackage, sourceRoot=${sourceRoot.path}")
                return ProjectStructure(ProjectType.MYBATIS_NATIVE, basePackage, sourceRoot)
            }
        }

        LOG.info("ProjectTypeDetector: 不支持的项目类型，sourceRoot=${sourceRoot.path}")
        return ProjectStructure(ProjectType.UNSUPPORTED, null, sourceRoot)
    }

    /** 查找主源码目录（src/main/java）。 */
    private fun findMainSourceRoot(modules: Array<com.intellij.openapi.module.Module>): VirtualFile? {
        for (module in modules) {
            val rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module)
            for (sourceRoot in rootManager.sourceRoots) {
                val path = sourceRoot.path
                if (path.endsWith("src/main/java") || path.endsWith("src/main/java/")) {
                    return sourceRoot
                }
            }
        }
        // 回退：取第一个源码目录
        for (module in modules) {
            val rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module)
            val sourceRoots = rootManager.getSourceRoots(false)
            if (sourceRoots.isNotEmpty()) return sourceRoots[0]
        }
        return null
    }

    /** 检测是否为 Spring Boot 项目。 */
    private fun isSpringBootProject(project: Project): Boolean {
        // 注意：findClass 必须用 allScope（含依赖库），因为 Spring Boot 类在 jar 中
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        return try {
            val annotationClass = facade.findClass(
                "org.springframework.boot.autoconfigure.SpringBootApplication",
                allScope,
            ) ?: return hasSpringBootMarkers(project, allScope)

            // 搜索带有该注解的类（仅项目源码，不含库）
            val projectScope = GlobalSearchScope.projectScope(project)
            val annotated = AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope)
            annotated.findAll().isNotEmpty()
        } catch (e: Exception) {
            LOG.debug("ProjectTypeDetector: Spring Boot 检测异常，回退检测", e)
            hasSpringBootMarkers(project, allScope)
        }
    }

    /** 回退检测 Spring Boot 标记（检测 spring-boot 核心类是否存在）。 */
    private fun hasSpringBootMarkers(project: Project, scope: GlobalSearchScope): Boolean {
        val facade = JavaPsiFacade.getInstance(project)
        return try {
            val springAppClass = facade.findClass("org.springframework.boot.SpringApplication", scope)
            springAppClass != null
        } catch (_: Exception) {
            false
        }
    }

    /** 检测是否为 MyBatis 原生项目。 */
    private fun isMyBatisNativeProject(project: Project): Boolean {
        val scope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        return try {
            val sqlSessionFactory = facade.findClass("org.apache.ibatis.session.SqlSessionFactory", scope)
            sqlSessionFactory != null
        } catch (_: Exception) {
            false
        }
    }

    /** 查找项目主包名。 */
    private fun findBasePackage(project: Project, sourceRoot: VirtualFile): String? {
        val allScope = GlobalSearchScope.allScope(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        // 方法 1：从 @SpringBootApplication 所在包提取
        try {
            val annotationClass = facade.findClass(
                "org.springframework.boot.autoconfigure.SpringBootApplication",
                allScope,
            ) ?: return inferPackageFromSourceRoot(project, sourceRoot)

            val annotated = AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope)
            val psiClass = annotated.findAll().firstOrNull() ?: return inferPackageFromSourceRoot(project, sourceRoot)

            val qualifiedName = psiClass.qualifiedName ?: return inferPackageFromSourceRoot(project, sourceRoot)
            // @SpringBootApplication 所在包即为 basePackage
            return qualifiedName.substringBeforeLast('.')
        } catch (e: Exception) {
            LOG.debug("ProjectTypeDetector: 包名提取异常，回退推断", e)
            return inferPackageFromSourceRoot(project, sourceRoot)
        }
    }

    /** 从源码目录推断包名（取第一个 Java 文件的包名）。 */
    private fun inferPackageFromSourceRoot(project: Project, sourceRoot: VirtualFile): String? {
        val psiManager = com.intellij.psi.PsiManager.getInstance(project)
        val psiDirectory = psiManager.findDirectory(sourceRoot) ?: return null

        // 递归查找第一个 .java 文件，提取其包名
        return findFirstPackage(psiDirectory)
    }

    /** 递归查找第一个 Java 文件的包名。 */
    private fun findFirstPackage(directory: PsiDirectory): String? {
        // 先搜索当前目录的文件
        for (file in directory.files) {
            if (file.name.endsWith(".java")) {
                val pkgMatch = Regex("""^\s*package\s+([\w.]+)\s*;""").find(file.text)
                if (pkgMatch != null) return pkgMatch.groupValues[1]
            }
        }

        // 递归子目录
        for (subDir in directory.subdirectories) {
            val pkg = findFirstPackage(subDir)
            if (pkg != null) return pkg
        }

        return null
    }
}
