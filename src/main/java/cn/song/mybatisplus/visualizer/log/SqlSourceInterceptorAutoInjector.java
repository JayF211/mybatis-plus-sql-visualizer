package cn.song.mybatisplus.visualizer.log;

import com.intellij.lang.Language;
import com.intellij.lang.java.JavaLanguage;
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

import java.util.function.Consumer;

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

    /** 检测阶段产物（T-Perf-10：后台 ReadAction 检测 → EDT 最小写创建）。 */
    private sealed interface PrepareOutcome {
        /** 无法识别项目结构。 */
        record CannotDetectStructure() implements PrepareOutcome {
            static final CannotDetectStructure INSTANCE = new CannotDetectStructure();
        }

        /** 不支持的项目类型。 */
        record UnsupportedProject() implements PrepareOutcome {
            static final UnsupportedProject INSTANCE = new UnsupportedProject();
        }

        /** 拦截器已存在（幂等跳过）。 */
        record AlreadyExists() implements PrepareOutcome {
            static final AlreadyExists INSTANCE = new AlreadyExists();
        }

        /** 检测通过，可创建文件。 */
        record Ready(ProjectType type, String basePackage, VirtualFile sourceRoot) implements PrepareOutcome {
        }
    }

    /** 自动注入入口（同步语义保留——测试/非 EDT 场景；EDT 调用请用 {@link #injectAsync}）。 */
    public static InjectionResult inject(Project project) {
        PrepareOutcome outcome = detectForInject(project);
        if (outcome instanceof PrepareOutcome.CannotDetectStructure) {
            return InjectionResult.CannotDetectStructure.INSTANCE;
        }
        if (outcome instanceof PrepareOutcome.UnsupportedProject) {
            return InjectionResult.UnsupportedProject.INSTANCE;
        }
        if (outcome instanceof PrepareOutcome.AlreadyExists) {
            return InjectionResult.AlreadyExists.INSTANCE;
        }
        if (outcome instanceof PrepareOutcome.Ready ready) {
            return createFiles(project, ready);
        }
        return InjectionResult.UnsupportedProject.INSTANCE;
    }

    /**
     * T-Perf-10：异步注入入口（设置页 EDT 按钮回调用）——
     * 检测阶段（ProjectTypeDetector.detect 的 AnnotatedElementsSearch.findAll /
     * allScope findClass / 源码目录递归遍历 + 拦截器存在性 VFS 查找）后台 ReadAction 执行；
     * 仅文件创建保留最小 WriteCommandAction（EDT 回调内执行，2 个模板文件，毫秒级）；
     * 结果经回调返回（EDT）。
     */
    public static void injectAsync(Project project, Consumer<InjectionResult> onResult) {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
            PrepareOutcome computed;
            try {
                computed = com.intellij.openapi.application.ReadAction.compute(
                        (com.intellij.openapi.util.ThrowableComputable<PrepareOutcome, Throwable>)
                                () -> detectForInject(project));
            } catch (Throwable t) {
                computed = null;
            }
            final PrepareOutcome outcome = computed;
            if (outcome == null) {
                return;
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) {
                    return;
                }
                onResult.accept(toResult(project, outcome));
            });
        });
    }

    /** 检测产物 → 注入结果（文件创建在 EDT 最小写段执行）。 */
    private static InjectionResult toResult(Project project, PrepareOutcome outcome) {
        if (outcome instanceof PrepareOutcome.CannotDetectStructure) {
            return InjectionResult.CannotDetectStructure.INSTANCE;
        }
        if (outcome instanceof PrepareOutcome.UnsupportedProject) {
            return InjectionResult.UnsupportedProject.INSTANCE;
        }
        if (outcome instanceof PrepareOutcome.AlreadyExists) {
            return InjectionResult.AlreadyExists.INSTANCE;
        }
        if (outcome instanceof PrepareOutcome.Ready ready) {
            return createFiles(project, ready);
        }
        return InjectionResult.UnsupportedProject.INSTANCE;
    }

    /** 检测阶段（须在 ReadAction 内调用——PSI 索引/目录访问）。 */
    private static PrepareOutcome detectForInject(Project project) {
        ProjectStructure structure = ProjectTypeDetector.detect(project);
        if (structure == null) {
            return PrepareOutcome.CannotDetectStructure.INSTANCE;
        }

        if (structure.getType() == ProjectType.UNSUPPORTED) {
            return PrepareOutcome.UnsupportedProject.INSTANCE;
        }

        String basePackage = structure.getBasePackage();
        if (basePackage == null) {
            return PrepareOutcome.CannotDetectStructure.INSTANCE;
        }

        VirtualFile sourceRoot = structure.getSourceRoot();

        // 检查是否已存在（幂等性）
        if (interceptorExists(sourceRoot, basePackage)) {
            return PrepareOutcome.AlreadyExists.INSTANCE;
        }
        return new PrepareOutcome.Ready(structure.getType(), basePackage, sourceRoot);
    }

    /** 文件创建（EDT；最小 WriteCommandAction——T-Perf-10：reformat 省略，模板内容本身即规范缩进）。 */
    private static InjectionResult createFiles(Project project, PrepareOutcome.Ready outcome) {
        if (outcome.type() == ProjectType.SPRING_BOOT) {
            return injectSpringBoot(project, outcome.basePackage(), outcome.sourceRoot());
        }
        if (outcome.type() == ProjectType.MYBATIS_NATIVE) {
            return injectMyBatisNative(project, outcome.basePackage(), outcome.sourceRoot());
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

    /** 创建 Java 文件（PSI；T-Perf-10：reformat 省略——模板内容本身即规范缩进，reformat 是平台标注的慢操作）。 */
    private static void createJavaFile(Project project, PsiDirectory directory, String fileName, String content) {
        // T-DepApi：StdLanguages.JAVA 全平台标 deprecated——JavaLanguage.INSTANCE（java 插件，
        // plugin.xml 已声明 com.intellij.java 依赖）是官方等价物，243/253/262 javap 实证均无弃用标记；
        // findLanguageByID("JAVA") 找不到时（Java 插件被禁用的极端场景）原 StdLanguages 兜底也依赖
        // 同一插件提供 Language 实现，语义不变，故直接取 INSTANCE。
        Language javaLanguage = JavaLanguage.INSTANCE;
        PsiFile psiFile = PsiFileFactory.getInstance(project)
                .createFileFromText(fileName, javaLanguage, content);

        // 添加到目录
        directory.add(psiFile);
    }
}
