package cn.song.mybatisplus.visualizer.psi;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-PsiCaptureProtocol T-PCP-01：PSI 元素跨线程读取的<b>两段式捕获快照</b>。
 *
 * <p>形态对齐第八轮报告 §7.2 Z-16（双快照 + 前后比对 + 交叉验证），按平台能力落地——我方是<b>读侧</b>，
 * 用 {@link Document#getModificationStamp()} 而非 commit document（不需要写回）：
 * <ol>
 *   <li><b>EDT 侧捕获</b>（{@link #capture}）：记下元素所属 {@link VirtualFile}、{@link Document}、textRange 与当时的
 *       {@code modificationStamp}；</li>
 *   <li><b>后台侧校验</b>（{@link #validate}）：文件仍有效 / stamp 未变 / 区间仍在界内，任一不符即
 *       {@link Validity.Invalid}——调用方<b>如实降级</b>（gutter 标记不渲染、跳转不跳并留诊断），
 *       不得拿失效区间去算行号（那是静默错值）。</li>
 * </ol>
 *
 * <p>为什么需要它：{@code ui/gutter/SqlGutterLineMarkerProvider} 与
 * {@code ui/gutter/StringSqlMethodGutterLineMarkerProvider} 在 EDT 捕获 {@link PsiElement} 后把元素带进
 * {@code executeOnPooledThread}——捕获与读取之间若发生写操作（用户输入 / 平台 reparse），元素区间可能
 * 已失效，而原实现只包了读权限（{@code ReadAction}），没有「元素是否仍有效」的校验。
 *
 * <p><b>只读侧不做文本交叉验证</b>（计划 §三 T-PCP-01 原列「element 文本」字段，实施时按平台事实修正）：
 * PSI 树由<b>已提交</b>的文档内容构建，编辑器里的未提交变更只体现在 {@link Document} 上。若以
 * 「PSI 文本 == 文档对应区间文本」作第四项校验，用户刚敲完字（PSI 尚未 commit）点击 gutter 就会被
 * 误判为「已失效」，标记/记录静默消失；真实变更面已由 {@code modificationStamp} 覆盖，故本工具只做三项校验。
 *
 * <p>线程约定：
 * <ul>
 *   <li>{@link #capture} 在 <b>EDT（或持读权限的线程）</b>调用——读 {@code containingFile} / {@code textRange} 属 PSI 读；</li>
 *   <li>{@link #validate} / {@link #lineNumber} 须在<b>读权限内</b>调用（后台线程须自行包 {@code ReadAction}）——两者读
 *       {@link VirtualFile#isValid()} 与文档状态。</li>
 * </ul>
 *
 * <p>master 的 {@code class PsiSnapshot} 的 Java 承载；{@code sealed class Validity} 的两个子类型按本仓
 * 既定形态落为 {@link Validity} 的嵌套类型（{@code data object Valid} → {@code INSTANCE} 单例，
 * {@code data class Invalid} → record）。</p>
 */
public final class PsiSnapshot {

    private final VirtualFile file;
    private final Document document;
    private final int startOffset;
    private final int endOffset;
    private final long modificationStamp;

    private PsiSnapshot(
            @NotNull VirtualFile file,
            @NotNull Document document,
            int startOffset,
            int endOffset,
            long modificationStamp) {
        this.file = file;
        this.document = document;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.modificationStamp = modificationStamp;
    }

    /** 捕获时元素所属文件的绝对路径（与 {@link VirtualFile#getPath()} 同形）。 */
    @NotNull
    public String getFilePath() {
        return file.getPath();
    }

    /** 校验结果：有效 / 失效（带原因，供诊断与用例断言）。 */
    public sealed interface Validity {

        /** 有效（{@code data object Valid} 等价单例）。 */
        final class Valid implements Validity {
            public static final Valid INSTANCE = new Valid();

            private Valid() {
            }
        }

        /**
         * 失效。
         *
         * @param reason 失效原因
         */
        record Invalid(@NotNull InvalidReason reason) implements Validity {
        }
    }

    /** 失效原因（三态 + 捕获期缺失，理由文案供 {@code LOG.debug} 诊断）。 */
    public enum InvalidReason {

        /** 虚拟文件已失效（被删除 / 移动 / 重建）。 */
        FILE_GONE("虚拟文件已失效（文件被删除/移动/重建）"),

        /** 文档 modificationStamp 与捕获时不一致（捕获后发生过文档写）。 */
        DOCUMENT_MODIFIED("捕获后文档已变更（modificationStamp 变化）"),

        /** 捕获的 textRange 越出当前文档边界（元素区间已不可用）。 */
        RANGE_OUT_OF_BOUNDS("textRange 越出文档边界（元素区间已失效）");

        private final String description;

        InvalidReason(String description) {
            this.description = description;
        }

        /** 失效理由文案。 */
        @NotNull
        public String getDescription() {
            return description;
        }
    }

    /**
     * 校验捕获是否仍可用（<b>须在读权限内调用</b>）。
     *
     * <p>判定顺序固定为「文件 → stamp → 区间」：文件没了就没必要谈 stamp；stamp 变了则区间必已重算。</p>
     */
    @NotNull
    public Validity validate() {
        return PsiSnapshotKt.validateCaptured(
                modificationStamp,
                startOffset,
                endOffset,
                file.isValid(),
                document.getModificationStamp(),
                document.getTextLength());
    }

    /**
     * 捕获点所在行号（1-based；<b>须在读权限内调用，且仅在 {@link #validate} 返回 {@link Validity.Valid} 后有意义</b>）。
     */
    public int lineNumber() {
        return document.getLineNumber(startOffset) + 1;
    }

    /**
     * EDT 侧捕获（元素无虚拟文件 / 无文档时返回 null——调用方按「无法定位」降级）。
     *
     * <p>注意：元素所属文件可能<b>不是</b>文本文件（无 {@link Document}），此时快照无意义，故返回 null。</p>
     */
    @Nullable
    public static PsiSnapshot capture(@NotNull PsiElement element) {
        PsiFile containingFile = element.getContainingFile();
        if (containingFile == null) {
            return null;
        }
        VirtualFile file = containingFile.getVirtualFile();
        if (file == null) {
            return null;
        }
        TextRange range = element.getTextRange();
        if (range == null) {
            return null;
        }
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            return null;
        }
        return new PsiSnapshot(
                file,
                document,
                range.getStartOffset(),
                range.getEndOffset(),
                document.getModificationStamp());
    }
}
