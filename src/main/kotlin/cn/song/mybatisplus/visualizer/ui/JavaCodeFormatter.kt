package cn.song.mybatisplus.visualizer.ui

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * Java 代码格式化（T-M45「格式化代码」按钮）：用 IDE 的 Java 格式化器
 * （[CodeStyleManager]）格式化生成/编辑后的代码片段——空格/缩进归一、长链折行
 * 按用户当前代码风格配置执行。
 *
 * 实现：把代码片段**包进合成类的方法体**再建成临时 PSI 文件 → 重排 → 经 PSI 取出
 * 方法体文本（去最小缩进，结果对齐顶层语句形态）。
 * 为什么包方法体：片段多为顶层裸表达式（如 `new QueryWrapper<>()\n .select(...)`），
 * 直接按文件顶层解析是语法错误——格式化器对错误节点输出乱码（`.` 单独成行、参数
 * 逗号后空格丢失，平台实证）；方法体内语句上下文合法，格式化结果稳定。
 * 注意（T-M44 红线实证）：`PsiFileFactory.createFileFromText` 在 EDT 直调会触发
 * 262 平台的 markGenerated 树遍历 read access 硬抛——PSI 创建必须包 [ReadAction]
 * （EDT 上同步执行语义不变）；重排是 PSI 写操作，须在 [WriteCommandAction] 内。
 */
object JavaCodeFormatter {

    /** 临时文件名（Java 语言解析器按扩展名识别语言；脱离项目不注册，无需清理）。 */
    private const val SNIPPET_FILE_NAME = "GeneratedSnippet.java"

    /** 合成类包装（代码片段置于方法体内——顶层语句/表达式均合法）。 */
    private const val WRAPPER_PREFIX = "class GeneratedSnippet {\n    void m() {\n"
    private const val WRAPPER_SUFFIX = "\n    }\n}"

    /** 格式化代码片段；project 为空或内容为空 → 原样返回。 */
    fun format(project: Project?, code: String): String {
        if (code.isBlank() || project == null) return code
        val psiFile = ReadAction.compute<PsiFile, Throwable> {
            PsiFileFactory.getInstance(project)
                .createFileFromText(SNIPPET_FILE_NAME, JavaLanguage.INSTANCE, WRAPPER_PREFIX + code + WRAPPER_SUFFIX)
        }
        var result = code
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(psiFile)
            result = extractBody(psiFile) ?: code
        }
        return result
    }

    /**
     * 取出合成方法体文本（代码块文本去掉两侧花括号）并去最小缩进。
     * 解析失败（用户代码含类/方法声明等非语句内容）→ null，调用方回落原文。
     */
    private fun extractBody(psiFile: PsiFile): String? {
        val javaFile = psiFile as? PsiJavaFile ?: return null
        val method = javaFile.classes.firstOrNull()?.methods?.firstOrNull() ?: return null
        val block = method.body ?: return null
        val text = block.text
        if (text.length < 2) return null
        val inner = text.substring(1, text.length - 1)
        val lines = inner.split('\n')
        val minIndent = lines.asSequence()
            .filter { it.isNotBlank() }
            .map { line -> line.indexOfFirst { c -> c != ' ' && c != '\t' }.let { if (it < 0) line.length else it } }
            .minOrNull() ?: 0
        return lines.joinToString("\n") { line ->
            if (line.isBlank()) "" else line.drop(minIndent)
        }.trim()
    }
}
