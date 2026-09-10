package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F-44-7：项目类型检测——自动识别 Spring Boot / MyBatis 原生项目，供 SqlSourceInterceptorAutoInjector 使用。
 *
 * 检测策略：
 *  - Spring Boot：搜索 @SpringBootApplication 注解类（AnnotatedElementsSearch）
 *  - MyBatis 原生：检测 SqlSessionFactory 类是否在 classpath
 *  - 包名：从 @SpringBootApplication 所在包提取，或从源码目录第一个 .java 文件推断
 */
enum ProjectType {
    /** Spring Boot 项目（存在 @SpringBootApplication 注解类）。 */
    SPRING_BOOT,
    /** MyBatis 原生项目（存在 mybatis-config.xml 或 SqlSessionFactory 配置）。 */
    MYBATIS_NATIVE,
    /** 不支持的项目类型。 */
    UNSUPPORTED,
}

/** 项目结构信息。 */
class ProjectStructure {
    private final ProjectType type;
    private final String basePackage;
    private final VirtualFile sourceRoot;

    ProjectStructure(ProjectType type, String basePackage, VirtualFile sourceRoot) {
        this.type = type;
        this.basePackage = basePackage;
        this.sourceRoot = sourceRoot;
    }

    public ProjectType getType() {
        return type;
    }

    public String getBasePackage() {
        return basePackage;
    }

    public VirtualFile getSourceRoot() {
        return sourceRoot;
    }
}

/** 项目类型检测器（单例工具类）。 */
final class ProjectTypeDetector {

    private static final Logger LOG = Logger.getInstance(ProjectTypeDetector.class);

    private ProjectTypeDetector() {
    }

    /**
     * 检测项目类型和结构。
     *
     * T-PreviewDocLock2 F2：检测体全量 PSI 探测（ModuleManager/ModuleRootManager 源根、
     * JavaPsiFacade.findClass、AnnotatedElementsSearch、PsiManager.findDirectory、file.text）
     * 均在 262 平台要求读权限；其中 JavaPsiFacade.findClass 是 assertReadAccess <b>硬抛</b>（非软
     * 断言），在 EDT 上裸调会直接 RuntimeException——调用方（设置页勾选回调）先前靠 catch(Exception)
     * 吞掉表现为「注入静默失败」。这里把探测纯函数包 ReadAction（纯读、无写操作，EDT 上同步语义
     * 不变）；调用方 SqlSourceInterceptorAutoInjector.inject 的文件创建段仍走 WriteCommandAction，
     * 与探测分开。
     */
    public static ProjectStructure detect(Project project) {
        return ReadAction.compute(() -> detectSync(project));
    }

    /** 检测主体（ReadAction 内执行）。 */
    private static ProjectStructure detectSync(Project project) {
        Module[] modules = ModuleManager.getInstance(project).getModules();
        if (modules.length == 0) {
            return null;
        }

        // 获取主源码目录
        VirtualFile sourceRoot = findMainSourceRoot(modules);
        if (sourceRoot == null) {
            return null;
        }

        // 检测 Spring Boot
        if (isSpringBootProject(project)) {
            String basePackage = findBasePackage(project, sourceRoot);
            if (basePackage != null) {
                LOG.info("ProjectTypeDetector: Spring Boot 项目，basePackage=" + basePackage
                        + ", sourceRoot=" + sourceRoot.getPath());
                return new ProjectStructure(ProjectType.SPRING_BOOT, basePackage, sourceRoot);
            }
        }

        // 检测 MyBatis 原生
        if (isMyBatisNativeProject(project)) {
            String basePackage = findBasePackage(project, sourceRoot);
            if (basePackage != null) {
                LOG.info("ProjectTypeDetector: MyBatis 原生项目，basePackage=" + basePackage
                        + ", sourceRoot=" + sourceRoot.getPath());
                return new ProjectStructure(ProjectType.MYBATIS_NATIVE, basePackage, sourceRoot);
            }
        }

        LOG.info("ProjectTypeDetector: 不支持的项目类型，sourceRoot=" + sourceRoot.getPath());
        return new ProjectStructure(ProjectType.UNSUPPORTED, null, sourceRoot);
    }

    /** 查找主源码目录（src/main/java）。 */
    private static VirtualFile findMainSourceRoot(Module[] modules) {
        for (Module module : modules) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            for (VirtualFile sourceRoot : rootManager.getSourceRoots()) {
                String path = sourceRoot.getPath();
                if (path.endsWith("src/main/java") || path.endsWith("src/main/java/")) {
                    return sourceRoot;
                }
            }
        }
        // 回退：取第一个源码目录
        for (Module module : modules) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            VirtualFile[] sourceRoots = rootManager.getSourceRoots(false);
            if (sourceRoots.length > 0) {
                return sourceRoots[0];
            }
        }
        return null;
    }

    /** 检测是否为 Spring Boot 项目。 */
    private static boolean isSpringBootProject(Project project) {
        // 注意：findClass 必须用 allScope（含依赖库），因为 Spring Boot 类在 jar 中
        GlobalSearchScope allScope = GlobalSearchScope.allScope(project);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);

        try {
            PsiClass annotationClass = facade.findClass(
                    "org.springframework.boot.autoconfigure.SpringBootApplication", allScope);
            if (annotationClass == null) {
                return hasSpringBootMarkers(project, allScope);
            }

            // 搜索带有该注解的类（仅项目源码，不含库）
            GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
            for (PsiClass psiClass : AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).findAll()) {
                return true;
            }
            return false;
        } catch (Exception e) {
            LOG.debug("ProjectTypeDetector: Spring Boot 检测异常，回退检测", e);
            return hasSpringBootMarkers(project, allScope);
        }
    }

    /** 回退检测 Spring Boot 标记（检测 spring-boot 核心类是否存在）。 */
    private static boolean hasSpringBootMarkers(Project project, GlobalSearchScope scope) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        try {
            PsiClass springAppClass = facade.findClass("org.springframework.boot.SpringApplication", scope);
            return springAppClass != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 检测是否为 MyBatis 原生项目。 */
    private static boolean isMyBatisNativeProject(Project project) {
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        try {
            PsiClass sqlSessionFactory = facade.findClass("org.apache.ibatis.session.SqlSessionFactory", scope);
            return sqlSessionFactory != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 查找项目主包名。 */
    private static String findBasePackage(Project project, VirtualFile sourceRoot) {
        GlobalSearchScope allScope = GlobalSearchScope.allScope(project);
        GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);

        // 方法 1：从 @SpringBootApplication 所在包提取
        try {
            PsiClass annotationClass = facade.findClass(
                    "org.springframework.boot.autoconfigure.SpringBootApplication", allScope);
            if (annotationClass == null) {
                return inferPackageFromSourceRoot(project, sourceRoot);
            }

            PsiClass psiClass = null;
            for (PsiClass c : AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).findAll()) {
                psiClass = c;
                break;
            }
            if (psiClass == null) {
                return inferPackageFromSourceRoot(project, sourceRoot);
            }

            String qualifiedName = psiClass.getQualifiedName();
            if (qualifiedName == null) {
                return inferPackageFromSourceRoot(project, sourceRoot);
            }
            // @SpringBootApplication 所在包即为 basePackage
            int idx = qualifiedName.lastIndexOf('.');
            return idx < 0 ? "" : qualifiedName.substring(0, idx);
        } catch (Exception e) {
            LOG.debug("ProjectTypeDetector: 包名提取异常，回退推断", e);
            return inferPackageFromSourceRoot(project, sourceRoot);
        }
    }

    /** 从源码目录推断包名（取第一个 Java 文件的包名）。 */
    private static String inferPackageFromSourceRoot(Project project, VirtualFile sourceRoot) {
        PsiManager psiManager = PsiManager.getInstance(project);
        PsiDirectory psiDirectory = psiManager.findDirectory(sourceRoot);
        if (psiDirectory == null) {
            return null;
        }
        return findFirstPackage(psiDirectory);
    }

    /** 递归查找第一个 Java 文件的包名。 */
    private static String findFirstPackage(PsiDirectory directory) {
        // 先搜索当前目录的文件
        for (PsiFile file : directory.getFiles()) {
            if (file.getName().endsWith(".java")) {
                Pattern pkgPattern = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
                Matcher pkgMatch = pkgPattern.matcher(file.getText());
                if (pkgMatch.find()) {
                    return pkgMatch.group(1);
                }
            }
        }

        // 递归子目录
        for (PsiDirectory subDir : directory.getSubdirectories()) {
            String pkg = findFirstPackage(subDir);
            if (pkg != null) {
                return pkg;
            }
        }

        return null;
    }
}
