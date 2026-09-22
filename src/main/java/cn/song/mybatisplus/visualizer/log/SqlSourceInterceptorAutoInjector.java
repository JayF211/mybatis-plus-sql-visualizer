package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.lang.Language;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F-44-7：SqlSourceInterceptor 自动注入核心。
 *
 * 在用户项目中自动生成拦截器类和 Spring 配置类，
 * 应用重启后实时日志携带源码位置，实现精确跳转。
 *
 * 幂等性：注入前检查文件是否已存在，已存在则跳过。
 * T-JumpPath-04：多模块工程逐模块注入（旧实现只落第一个模块，用户手工拷贝到其他模块
 * 引发拷贝件生成期 basePackage 常量漏改的连锁缺陷），结果聚合规则见 {@link #decideAggregate}。
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

        /**
         * {@code ready} = 待注入的支持模块；{@code existed} = 已注入过且为新版模板的支持模块名（幂等跳过）；
         * {@code upgradable} = 已存在旧形态模板、可内容升级的支持模块（T-IntrUpg-03）。
         */
        record Ready(List<ProjectStructure> ready, List<String> existed, List<ProjectStructure> upgradable)
                implements PrepareOutcome {
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
     * 检测阶段（ProjectTypeDetector.detectPerModule 的 JavaPsiFacade.findClass /
     * AnnotatedElementsSearch / 源码目录递归遍历 + 拦截器存在性 VFS 查找）后台 ReadAction 执行；
     * 仅文件创建保留最小 WriteCommandAction（EDT 回调内执行，每模块 2 个模板文件，毫秒级）；
     * 结果经回调返回（EDT）。
     *
     * <p>T-ModPick：检测出多个待处理模块时先在 EDT 弹勾选框让用户选择（单模块不打扰直注），
     * 取消 → {@link InjectionResult.Cancelled}。
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
                if (outcome instanceof PrepareOutcome.Ready prepared) {
                    PrepareOutcome.Ready picked = pickModules(project, prepared);
                    if (picked == null) {
                        onResult.accept(InjectionResult.Cancelled.INSTANCE);
                        return;
                    }
                    onResult.accept(createFiles(project, picked));
                    return;
                }
                onResult.accept(toResult(outcome));
            });
        });
    }

    /** 检测产物 → 注入结果（不含文件创建的平凡分支；文件创建在 EDT 最小写段执行）。 */
    private static InjectionResult toResult(PrepareOutcome outcome) {
        if (outcome instanceof PrepareOutcome.CannotDetectStructure) {
            return InjectionResult.CannotDetectStructure.INSTANCE;
        }
        if (outcome instanceof PrepareOutcome.UnsupportedProject) {
            return InjectionResult.UnsupportedProject.INSTANCE;
        }
        if (outcome instanceof PrepareOutcome.AlreadyExists) {
            return InjectionResult.AlreadyExists.INSTANCE;
        }
        return InjectionResult.UnsupportedProject.INSTANCE;
    }

    /** 模块选择键（与 {@link #createFiles} 的模块名口径一致）。 */
    private static String moduleKey(ProjectStructure structure) {
        String name = structure.getModuleName();
        if (name != null) return name;
        String basePackage = structure.getBasePackage();
        return basePackage != null ? basePackage : "?";
    }

    /**
     * T-ModPick：待处理模块（新建 + 升级）多于一个时弹勾选框（用户反馈：多模块应让用户
     * 选择而非全部生成）；单模块直接返回不打扰。取消 → null（调用方回调 Cancelled）。
     */
    private static PrepareOutcome.Ready pickModules(Project project, PrepareOutcome.Ready outcome) {
        if (outcome.ready().size() + outcome.upgradable().size() <= 1) {
            return outcome;
        }
        List<InjectionModulePickerDialog.Row> rows = new ArrayList<>();
        for (ProjectStructure it : outcome.ready()) {
            rows.add(new InjectionModulePickerDialog.Row(
                    moduleKey(it), moduleKey(it) + " · " + (it.getBasePackage() != null ? it.getBasePackage() : "?"), false));
        }
        for (ProjectStructure it : outcome.upgradable()) {
            rows.add(new InjectionModulePickerDialog.Row(
                    moduleKey(it), moduleKey(it) + " · " + (it.getBasePackage() != null ? it.getBasePackage() : "?"), true));
        }
        InjectionModulePickerDialog dialog = new InjectionModulePickerDialog(project, rows);
        if (!dialog.showAndGet()) {
            return null;
        }
        java.util.Set<String> selected = dialog.selectedKeys();
        return new PrepareOutcome.Ready(
                outcome.ready().stream().filter(it -> selected.contains(moduleKey(it))).collect(Collectors.toList()),
                outcome.existed(),
                outcome.upgradable().stream().filter(it -> selected.contains(moduleKey(it))).collect(Collectors.toList()));
    }

    /** 检测阶段（须在 ReadAction 内调用——PSI 索引/目录访问）。 */
    private static PrepareOutcome detectForInject(Project project) {
        List<ProjectStructure> structures = ProjectTypeDetector.detectPerModule(project);
        if (structures.isEmpty()) {
            // 无任何支持模块：区分「项目里根本没有可用源码结构」与「有结构但都不是 MP 工程」
            return hasAnySourceRoot(project)
                    ? PrepareOutcome.UnsupportedProject.INSTANCE
                    : PrepareOutcome.CannotDetectStructure.INSTANCE;
        }
        List<ProjectStructure> ready = new ArrayList<>();
        List<String> existed = new ArrayList<>();
        List<ProjectStructure> upgradable = new ArrayList<>();
        for (ProjectStructure structure : structures) {
            VirtualFile existing = interceptorFile(structure.getSourceRoot(), structure.getBasePackage() == null ? "" : structure.getBasePackage());
            if (existing == null) {
                ready.add(structure);
                continue;
            }
            // T-IntrUpg-03：已存在拦截器文件 → 读文本判形态。旧模板产物（isSelf 常量拼接
            // 与运行类可能分叉）纳入升级清单，EDT 内容重写；其余（新版/用户自写）幂等跳过。
            String text = null;
            try {
                text = VfsUtil.loadText(existing);
            } catch (Throwable ignored) {
                // 读失败按新版处理（幂等跳过，不覆盖用户文件）
            }
            if (text != null && needsInterceptorUpgrade(text)) {
                upgradable.add(structure);
            } else if (structure.getModuleName() != null) {
                existed.add(structure.getModuleName());
            }
        }
        if (ready.isEmpty() && upgradable.isEmpty()) {
            return PrepareOutcome.AlreadyExists.INSTANCE;
        }
        return new PrepareOutcome.Ready(ready, existed, upgradable);
    }

    /**
     * 已存在拦截器文件是否需要升级（T-IntrUpg-02，表驱动单测 InjectionUpgradeDecisionTest）。
     *
     * <p>三条件缺一不可：
     * <ul>
     *  <li>含模板生成标记：仅插件生成物可被覆盖，用户手改/自写文件永不动；</li>
     *  <li>isSelf 为生成期常量拼接形态（旧模板特征 {@code cn.equals("}）：存在常量与运行类分叉缺陷；</li>
     *  <li>不含运行时比对 {@code getClass().getName()}：已是新模板则跳过。</li>
     * </ul>
     *
     * <p>判定基于运行时行为缺陷形态而非模板全文比对——模板其他部分后续演进不误判。
     *
     * <p><b>迁移前提（T-AgentResiduals R1b 登记，改模板内容前必读）</b>：模板是<b>注入到用户工程</b>的源码，
     * 本判据决定了「模板内容的改进能不能到存量用户手里」——三种情形<b>只有前两种自动生效</b>：
     * <ol>
     *   <li><b>新注入</b>（该模块无该文件）→ 直接用当前模板内容，<b>自动拿到</b>改进；</li>
     *   <li><b>旧形态副本</b>（上面三条件全中）→ 下次注入时内容升级，<b>自动拿到</b>改进；</li>
     *   <li><b>当前形态副本</b>（含 {@code getClass().getName()}）→ 本判据返回 false → <b>幂等跳过、永不更新</b>。
     *       存量用户绝大多数属此类（T-IntrUpg-03 之后注入的），<b>不会</b>自动变准。</li>
     * </ol>
     * 故模板内容的每次改动（如 R1b 补齐框架帧跳过前缀）都要在 CHANGELOG/发行说明里点明：
     * 只对<b>新注入</b>与<b>旧形态升级</b>生效；存量当前形态副本需用户<b>删除注入文件后重新注入</b>，或按
     * 模板注释手工补前缀。<b>不要</b>为了「让改动自动生效」而放宽本判据（把当前形态也判为可升级 =
     * 每次注入都覆盖用户可能改过的文件，违背「用户手改永不动」这条更硬的红线）。
     */
    static boolean needsInterceptorUpgrade(String text) {
        return text.contains("自动生成于 SQL Visualizer 插件")
                && text.contains("cn.equals(\"")
                && !text.contains("getClass().getName()");
    }

    /** 项目是否存在任何模块源根（区分 CannotDetectStructure 与 UnsupportedProject）。 */
    private static boolean hasAnySourceRoot(Project project) {
        for (com.intellij.openapi.module.Module module : ModuleManager.getInstance(project).getModules()) {
            if (ModuleRootManager.getInstance(module).getSourceRoots().length > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 逐模块结果 → 用户可见聚合（纯决策，消息经 {@code successMessageFor} 注入——表驱动单测驱动本函数）。
     *
     * <p>规则（计划 §4.4）：至少一模块新注入 → Success（成功优先，失败模块并入由调用方日志留痕）；
     * 无新注入但有已存在模块 → AlreadyExists；全失败/无支持模块 → UnsupportedProject。
     */
    static InjectionResult decideAggregate(
            List<String> injected,
            List<String> existed,
            List<String> failed,
            Function<List<String>, String> successMessageFor) {
        if (!injected.isEmpty()) {
            return new InjectionResult.Success(successMessageFor.apply(injected));
        }
        if (!existed.isEmpty()) {
            return InjectionResult.AlreadyExists.INSTANCE;
        }
        return InjectionResult.UnsupportedProject.INSTANCE;
    }

    /** 文件创建（EDT；逐模块最小 WriteCommandAction——T-Perf-10：reformat 省略，模板即规范缩进）。 */
    private static InjectionResult createFiles(Project project, PrepareOutcome.Ready outcome) {
        List<String> created = new ArrayList<>();
        List<String> upgraded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (ProjectStructure structure : outcome.ready()) {
            String moduleName = moduleKey(structure);
            InjectionResult result = structure.getType() == ProjectType.SPRING_BOOT
                    ? injectSpringBoot(project, structure.getBasePackage(), structure.getSourceRoot())
                    : injectMyBatisNative(project, structure.getBasePackage() == null ? "" : structure.getBasePackage(), structure.getSourceRoot());
            if (result instanceof InjectionResult.Success) {
                created.add(moduleName);
            } else {
                failed.add(moduleName);
                LOG.info("SqlSourceInterceptorAutoInjector: 模块 " + moduleName + " 注入未成功（" + result + "），继续其余模块");
            }
        }
        // T-IntrUpg-03：旧形态模板内容升级（不新建、不搬文件——拷贝件所在包即检测到的
        // 模块基础包，重写内容后 package 声明与 isSelf 常量随所在包修正）。
        for (ProjectStructure structure : outcome.upgradable()) {
            String moduleName = moduleKey(structure);
            if (upgradeInterceptorFile(project, structure)) {
                upgraded.add(moduleName);
            } else {
                failed.add(moduleName);
            }
        }
        List<String> injected = new ArrayList<>(created);
        injected.addAll(upgraded);
        return decideAggregate(injected, outcome.existed(), failed, names ->
                successMessage(names, created, upgraded, outcome));
    }

    /**
     * 单个旧形态拦截器文件内容升级（EDT 最小写）。文件定位失败/写失败 → false（调用方计 failed）。
     * 只重写拦截器文件，不动 Config——拷贝件场景 Config 的 appPackages 现值已正确（计划 §七）。
     */
    private static boolean upgradeInterceptorFile(Project project, ProjectStructure structure) {
        String basePackage = structure.getBasePackage();
        if (basePackage == null) {
            return false;
        }
        try {
            Boolean ok = WriteCommandAction.runWriteCommandAction(project, (Computable<Boolean>) () -> {
                VirtualFile file = interceptorFile(structure.getSourceRoot(), basePackage);
                if (file == null) {
                    return Boolean.FALSE;
                }
                try {
                    VfsUtil.saveText(file, SourceInterceptorTemplates.generateInterceptorClass(basePackage));
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                return Boolean.TRUE;
            });
            LOG.info("SqlSourceInterceptorAutoInjector: 拦截器文件" + (ok ? "升级成功" : "升级未成功") + "，basePackage=" + basePackage);
            return ok;
        } catch (Exception e) {
            LOG.warn("SqlSourceInterceptorAutoInjector: 拦截器文件升级失败，basePackage=" + basePackage, e);
            return false;
        }
    }

    /**
     * 成功消息：全部为升级 → 升级文案（已更新旧版拦截器文件）；
     * 单模块纯新建沿用既有三参文案；其余多模块/混合形态列模块清单（T-JumpPath-04 聚合形态）。
     */
    private static String successMessage(
            List<String> injectedNames,
            List<String> created,
            List<String> upgraded,
            PrepareOutcome.Ready outcome) {
        if (created.isEmpty() && !upgraded.isEmpty()) {
            return MpSqlVisualizerBundle.message(
                    "log.inject.upgraded",
                    String.valueOf(upgraded.size()),
                    String.join("、", upgraded));
        }
        if (injectedNames.size() == 1) {
            ProjectStructure structure = outcome.ready().stream()
                    .filter(it -> Objects.equals(moduleKey(it), injectedNames.get(0)))
                    .findFirst().orElse(null);
            String basePackage = structure != null && structure.getBasePackage() != null
                    ? structure.getBasePackage() : injectedNames.get(0);
            return MpSqlVisualizerBundle.message(
                    "log.inject.success",
                    SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME,
                    SourceInterceptorTemplates.CONFIG_FILE_NAME,
                    basePackage);
        }
        return MpSqlVisualizerBundle.message(
                "log.inject.successMulti",
                String.valueOf(injectedNames.size()),
                String.join("、", injectedNames),
                SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME,
                SourceInterceptorTemplates.CONFIG_FILE_NAME);
    }

    /** 拦截器类文件（不存在返回 null）。 */
    private static VirtualFile interceptorFile(VirtualFile sourceRoot, String basePackage) {
        String configDir = basePackage.replace('.', '/') + "/config";
        String interceptorPath = sourceRoot.getPath() + "/" + configDir + "/"
                + SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME;
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(interceptorPath);
        return file != null && file.exists() ? file : null;
    }

    /** Spring Boot 项目注入（单模块；聚合消息在 {@link #createFiles} 统一组装，此处 message 仅占位）。 */
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

                        return new InjectionResult.Success(basePackage);
                    });
            LOG.info("SqlSourceInterceptorAutoInjector: Spring Boot 注入"
                    + (result instanceof InjectionResult.Success ? "成功" : "未成功") + "，basePackage=" + basePackage);
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
