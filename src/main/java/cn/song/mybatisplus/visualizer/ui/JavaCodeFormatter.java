package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Java 代码格式化（T-M45「格式化代码」按钮）：用 IDE 的 Java 格式化器
 * （CodeStyleManager）格式化生成/编辑后的代码片段——空格/缩进归一、长链折行
 * 按用户当前代码风格配置执行。
 *
 * 实现：把代码片段**包进合成类的方法体**再建成临时 PSI 文件 → 重排 → 经 PSI 取出
 * 方法体文本（去最小缩进，结果对齐顶层语句形态）。
 */
public final class JavaCodeFormatter {

    public static final JavaCodeFormatter INSTANCE = new JavaCodeFormatter();

    /** 临时文件名（Java 语言解析器按扩展名识别语言；脱离项目不注册，无需清理）。 */
    private static final String SNIPPET_FILE_NAME = "GeneratedSnippet.java";

    /** 合成类包装（代码片段置于方法体内——顶层语句/表达式均合法）。 */
    private static final String WRAPPER_PREFIX = "class GeneratedSnippet {\n    void m() {\n";
    private static final String WRAPPER_SUFFIX = "\n    }\n}";

    /** T-Perf-11：全量 reformat 输入长度上限——超过走 {@link #lightNormalize} 轻量降级
     *  （write action 内的全量 reformat 不可取消，冻结与代码体积成正比）。 */
    private static final int MAX_REFORMAT_LENGTH = 64_000;

    private JavaCodeFormatter() {
    }

    /** 格式化代码片段；project 为空或内容为空 → 原样返回。 */
    @NotNull
    public static String format(@Nullable Project project, @NotNull String code) {
        if (code.isBlank() || project == null) return code;
        if (code.length() > MAX_REFORMAT_LENGTH) return lightNormalize(code);
        PsiFile psiFile = ReadAction.compute(() ->
                PsiFileFactory.getInstance(project)
                        .createFileFromText(SNIPPET_FILE_NAME, JavaLanguage.INSTANCE, WRAPPER_PREFIX + code + WRAPPER_SUFFIX));
        String[] holder = {code};
        WriteCommandAction.runWriteCommandAction(project, () -> {
            CodeStyleManager.getInstance(project).reformat(psiFile);
            String extracted = extractBody(psiFile);
            holder[0] = extracted != null ? extracted : code;
        });
        return holder[0];
    }

    /**
     * T-Perf-11：后台格式化（EDT 调用入口）——PSI 创建 + 全量 reformat 是平台标注的慢操作，
     * 批量转换 20+ 条语句拼大代码块时 EDT 冻结与体积成正比；移 {@link Task.Backgroundable}
     * （带后台进度指示）执行，结果回 EDT 回调。超长输入在提交后台前即走 {@link #lightNormalize} 降级。
     */
    public static void formatAsync(@NotNull Project project, @NotNull String code,
                                   @NotNull java.util.function.Consumer<String> onResult) {
        if (code.isBlank() || code.length() > MAX_REFORMAT_LENGTH) {
            onResult.accept(code.isBlank() ? code : lightNormalize(code));
            return;
        }
        com.intellij.openapi.application.Application app =
                com.intellij.openapi.application.ApplicationManager.getApplication();
        // headless 单测：同步执行保持既有测试语义（测试环境无进度后台任务泵）
        if (app != null && app.isUnitTestMode()) {
            onResult.accept(format(project, code));
            return;
        }
        new Task.Backgroundable(project, MpSqlVisualizerBundle.message("reverse.panel.formatProgress"), true) {
            @Override
            public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                String formatted = format(project, code);
                app.invokeLater(() -> {
                    if (project.isDisposed()) return;
                    onResult.accept(formatted);
                });
            }
        }.queue();
    }

    /** T-Perf-11：轻量缩进归一（超长输入降级路径；无 PSI、零 write action）——去行尾空白、去首尾空行。 */
    static String lightNormalize(@NotNull String code) {
        String joined = code.lines().map(String::stripTrailing)
                .collect(java.util.stream.Collectors.joining("\n"));
        int begin = 0;
        int end = joined.length();
        while (begin < end && joined.charAt(begin) == '\n') begin++;
        while (end > begin && joined.charAt(end - 1) == '\n') end--;
        return joined.substring(begin, end);
    }

    /**
     * 取出合成方法体文本（代码块文本去掉两侧花括号）并去最小缩进。
     * 解析失败（用户代码含类/方法声明等非语句内容）→ null，调用方回落原文。
     */
    @Nullable
    private static String extractBody(@NotNull PsiFile psiFile) {
        if (!(psiFile instanceof PsiJavaFile javaFile)) return null;
        if (javaFile.getClasses().length == 0) return null;
        if (javaFile.getClasses()[0].getMethods().length == 0) return null;
        var method = javaFile.getClasses()[0].getMethods()[0];
        var block = method.getBody();
        if (block == null) return null;
        String text = block.getText();
        if (text.length() < 2) return null;
        String inner = text.substring(1, text.length() - 1);
        List<String> lines = new ArrayList<>();
        for (String line : inner.split("\n", -1)) {
            lines.add(line);
        }
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) continue;
            int idx = 0;
            while (idx < line.length() && (line.charAt(idx) == ' ' || line.charAt(idx) == '\t')) idx++;
            minIndent = Math.min(minIndent, idx);
        }
        if (minIndent == Integer.MAX_VALUE) minIndent = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                sb.append("");
            } else {
                sb.append(line.substring(Math.min(minIndent, line.length())));
            }
            if (i < lines.size() - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }
}
