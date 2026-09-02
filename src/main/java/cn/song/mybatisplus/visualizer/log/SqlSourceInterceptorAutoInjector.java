package cn.song.mybatisplus.visualizer.log;

import com.intellij.lang.Language;
import com.intellij.lang.StdLanguages;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.codeStyle.CodeStyleManager;

/**
 * F-44-7：SqlSourceInterceptor 自动注入核心。
 *
 * 在用户项目中自动生成拦截器类和 Spring 配置类，
 * 应用重启后实时日志携带源码位置，实现精确跳转。
 *
 * 幂等性：注入前检查文件是否已存在，已存在则跳过。
 */
public final class SqlSourceInterceptorAutoInjector {

    private static final Logger LOG = Logger.getInstance(SqlSourceInterceptorAutoInjector.class);

    private SqlSourceInterceptorAutoInjector() {
    }

    /** 自动注入入口。 */
    public static InjectionResult inject(Project project) {
        // 1. 检测项目结构
        ProjectStructure structure = ProjectTypeDetector.detect(project);
        if (structure == null) {
            return InjectionResult.CannotDetectStructure.INSTANCE;
        }

        if (structure.getType() == ProjectType.UNSUPPORTED) {
            return InjectionResult.UnsupportedProject.INSTANCE;
        }

        String basePackage = structure.getBasePackage();
        if (basePackage == null) {
            return InjectionResult.CannotDetectStructure.INSTANCE;
        }

        VirtualFile sourceRoot = structure.getSourceRoot();

        // 2. 检查是否已存在（幂等性）
        if (interceptorExists(sourceRoot, basePackage)) {
            return InjectionResult.AlreadyExists.INSTANCE;
        }

        // 3. 生成文件
        if (structure.getType() == ProjectType.SPRING_BOOT) {
            return injectSpringBoot(project, basePackage, sourceRoot);
        }
        if (structure.getType() == ProjectType.MYBATIS_NATIVE) {
            return injectMyBatisNative(project, basePackage, sourceRoot);
        }
        return InjectionResult.UnsupportedProject.INSTANCE;
    }

    /** 检查拦截器类是否已存在。 */
    private static boolean interceptorExists(VirtualFile sourceRoot, String basePackage) {
        String configDir = basePackage.replace('.', '/') + "/config";
        String interceptorPath = sourceRoot.getPath() + "/" + configDir + "/"
                + SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME;
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(interceptorPath);
        return file != null && file.exists();
    }

    /** Spring Boot 项目注入。 */
    private static InjectionResult injectSpringBoot(Project project, String basePackage, VirtualFile sourceRoot) {
        try {
            InjectionResult result = WriteCommandAction.runWriteCommandAction(project,
                    (Computable<InjectionResult>) () -> {
                        // 创建 config 包目录
                        PsiDirectory configDir = createPackageDirectories(project, sourceRoot, basePackage + ".config");
                        if (configDir == null) {
                            return InjectionResult.CannotDetectStructure.INSTANCE;
                        }

                        // 创建拦截器类
                        createJavaFile(
                                project, configDir,
                                SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME,
                                SourceInterceptorTemplates.generateInterceptorClass(basePackage));

                        // 创建配置类
                        createJavaFile(
                                project, configDir,
                                SourceInterceptorTemplates.CONFIG_FILE_NAME,
                                SourceInterceptorTemplates.generateSpringBootConfigClass(basePackage));

                        return new InjectionResult.Success(
                                "已生成 " + SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME + " 和 "
                                        + SourceInterceptorTemplates.CONFIG_FILE_NAME + "，位于 " + basePackage
                                        + ".config 包下，重启应用后生效。");
                    });
            LOG.info("SqlSourceInterceptorAutoInjector: Spring Boot 注入成功，basePackage=" + basePackage);
            return result;
        } catch (Exception e) {
            LOG.warn("SqlSourceInterceptorAutoInjector: Spring Boot 注入失败", e);
            return InjectionResult.CannotDetectStructure.INSTANCE;
        }
    }

    /** MyBatis 原生项目注入（TODO：后续实现）。 */
    private static InjectionResult injectMyBatisNative(Project project, String basePackage, VirtualFile sourceRoot) {
        // TODO：检测 mybatis-config.xml 并添加拦截器配置
        LOG.info("SqlSourceInterceptorAutoInjector: MyBatis 原生项目暂不支持自动注入");
        return InjectionResult.UnsupportedProject.INSTANCE;
    }

    /** 创建包目录（递归）。 */
    private static PsiDirectory createPackageDirectories(Project project, VirtualFile sourceRoot,
            String packageQualifiedName) {
        PsiManager psiManager = PsiManager.getInstance(project);
        PsiDirectory currentDir = psiManager.findDirectory(sourceRoot);
        if (currentDir == null) {
            return null;
        }

        for (String pkgPart : packageQualifiedName.split("\\.")) {
            PsiDirectory existing = currentDir.findSubdirectory(pkgPart);
            if (existing != null) {
                currentDir = existing;
            } else {
                currentDir = currentDir.createSubdirectory(pkgPart);
            }
        }

        return currentDir;
    }

    /** 创建 Java 文件（PSI）。 */
    private static void createJavaFile(Project project, PsiDirectory directory, String fileName, String content) {
        Language javaLanguage = Language.findLanguageByID("JAVA");
        if (javaLanguage == null) {
            javaLanguage = StdLanguages.JAVA;
        }
        PsiFile psiFile = PsiFileFactory.getInstance(project)
                .createFileFromText(fileName, javaLanguage, content);

        // 代码格式化
        try {
            CodeStyleManager.getInstance(project).reformat(psiFile);
        } catch (Exception e) {
            // 格式化失败不影响文件创建
        }

        // 添加到目录
        directory.add(psiFile);
    }
}
