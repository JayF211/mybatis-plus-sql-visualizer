// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/ParamBraceTypedHandler.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Set;

/**
 * T-XmlSynHi D5：mapper XML 语句文本内敲 {@code #} / {@code $} 自动闭合 {@code {}}（光标落 #/{ 之间）。
 *
 * <p>「先查后插」防双插（决策 4-A，共存插件同型行为）：插入前查光标后方字符——
 * <ul>
 *  <li>已是 {@code {} → 不补插（对方插件/用户已手敲 {@code {}，双插会产出 {@code #{{}}）——直接放行原字符。</li>
 * </ul>
 * （T-PerfSweep16 F46 口径对齐：历史 KDoc 声称 {@code }} 后也放行，实现自始只查 {@code {}——修文档不改行为。）
 * 守卫：XmlSynHiGate <b>设置开关线</b>（{@code shouldYield(project)} 单参形态）+ 根 mapper + 语句标签内。
 * （T-XmlGap W1 勘误：本 KDoc 旧述「双让位（平台/CodeHelperPro 在位即不接管）」与实现不符——
 * 本类<b>从不做</b> per-host 探测，共存安全靠下面的「先查后插」与语句标签守卫，不靠让位。）
 *
 * <p>243+ 注入片段击键路由（TypedHandler.callDelegates 字节码实证）：光标在注入片段内时
 * 传给本回调的 {@code file} 是**注入 PsiFile**（SQL 语言）而非宿主 XmlFile、{@code editor} 是
 * EditorWindow——须还原宿主三件套（hostFile/hostEditor/hostOffset）再套守卫；宿主路径
 * （标签标记区等非注入位）三件套原样即位。
 */
public class ParamBraceTypedHandler extends TypedHandlerDelegate {

    private static final Set<String> STATEMENT_TAGS = Set.copyOf(Arrays.asList(
            "select", "insert", "update", "delete"));

    @Override
    public Result beforeCharTyped(char c, @NotNull Project project, @NotNull Editor editor,
                                  @NotNull PsiFile file, @NotNull FileType fileType) {
        if (c != '#' && c != '$') {
            return Result.CONTINUE;
        }
        boolean inInjected = !(file instanceof XmlFile);
        XmlFile hostFile;
        if (inInjected) {
            PsiFile hostPsi = InjectedLanguageManager.getInstance(project).getInjectionHost(file) != null
                    ? InjectedLanguageManager.getInstance(project).getInjectionHost(file).getContainingFile()
                    : null;
            if (!(hostPsi instanceof XmlFile)) {
                return Result.CONTINUE;
            }
            hostFile = (XmlFile) hostPsi;
        } else {
            hostFile = (XmlFile) file;
        }
        if (XmlSynHiGate.shouldYield(project)) {
            return Result.CONTINUE;
        }
        Editor hostEditor = editor instanceof EditorWindow ? ((EditorWindow) editor).getDelegate() : editor;
        int offset = editor.getCaretModel().getOffset();
        if (inInjected && editor.getDocument() instanceof DocumentWindow) {
            Integer injectedToHost = ((DocumentWindow) editor.getDocument())
                    .injectedToHost(editor.getCaretModel().getOffset());
            if (injectedToHost != null) {
                offset = injectedToHost;
            }
        }
        // T-PerfSweep16 F39：getCharsSequence 直读底层文本（原 document.text 每次击键整文档复制一份 String）
        CharSequence text = hostEditor.getDocument().getCharsSequence();
        // 光标后方已有 `{`（对方插件已补/用户已敲）→ 放行，不双插
        if (offset < text.length() && text.charAt(offset) == '{') {
            return Result.CONTINUE;
        }
        // 光标须在 mapper 语句标签文本内（根 mapper 守卫 + 标签定位）；宿主偏移左邻字符定位
        int at = Math.max(offset - 1, 0);
        PsiElement el = hostFile.findElementAt(at);
        if (el == null) {
            return Result.CONTINUE;
        }
        XmlTag tag = PsiTreeUtil.getParentOfType(el, XmlTag.class);
        if (tag == null) {
            return Result.CONTINUE;
        }
        if (!inStatementText(tag, offset)) {
            return Result.CONTINUE;
        }

        // Kotlin lambda 捕获 var 无碍，Java 捕获须 effectively final——落 final 快照再进写命令
        final int insertOffset = offset;
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> {
            hostEditor.getDocument().insertString(insertOffset, c + "{}");
            hostEditor.getCaretModel().moveToOffset(insertOffset + 1);
        });
        return Result.STOP;
    }

    /**
     * 光标前字符所属标签是否为语句文本位（mapper 根守卫 + 语句标签集，与 injector 同口径）。
     * 子标签两态分界（20260928 真机实证）：光标落子标签**边界之后**（如 {@code </if>} 右侧，
     * 属父语句内容区）→ 接管；落子标签**自身标记区内**（如 {@code <trim prefix=|>} 属性间隙）→
     * 不接管——第二态曾因 parentTag∈语句标签 的宽判全放，全靠 EP 未注册的死代码遮掩。
     */
    private static boolean inStatementText(XmlTag tag, int offset) {
        if (!XmlSynHiGate.inMapperFile(tag)) {
            return false;
        }
        if (STATEMENT_TAGS.contains(tag.getName())) {
            return true;
        }
        XmlTag parent = tag.getParentTag();
        if (parent == null || !STATEMENT_TAGS.contains(parent.getName())) {
            return false;
        }
        return offset >= tag.getTextRange().getEndOffset();
    }
}
