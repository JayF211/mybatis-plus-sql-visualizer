package cn.song.mybatisplus.visualizer.ui;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
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

    private JavaCodeFormatter() {
    }

    /** 格式化代码片段；project 为空或内容为空 → 原样返回。 */
    @NotNull
    public static String format(@Nullable Project project, @NotNull String code) {
        if (code.isBlank() || project == null) return code;
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
