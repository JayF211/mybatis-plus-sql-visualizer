package cn.song.mybatisplus.visualizer.psi;

/**
 * {@code PsiSnapshot.kt} 顶层函数（{@code validateCaptured}）的 Java 承载类。
 *
 * <p>与平台对象解耦的纯函数内核——表驱动用例可直接喂三态事实，不必构造失效的
 * {@code VirtualFile} / 真改文档。</p>
 */
public final class PsiSnapshotKt {

    private PsiSnapshotKt() {
    }

    /**
     * {@link PsiSnapshot#validate} 的纯函数内核。
     *
     * @param capturedStamp 捕获时的 {@code modificationStamp}
     * @param startOffset 捕获的 textRange 起点（文档偏移）
     * @param endOffset 捕获的 textRange 终点（文档偏移）
     * @param fileValid 当前 {@code VirtualFile.isValid()}
     * @param currentStamp 当前 {@code modificationStamp}
     * @param documentLength 当前文档文本长度
     */
    public static PsiSnapshot.Validity validateCaptured(
            long capturedStamp,
            int startOffset,
            int endOffset,
            boolean fileValid,
            long currentStamp,
            int documentLength) {
        if (!fileValid) {
            return new PsiSnapshot.Validity.Invalid(PsiSnapshot.InvalidReason.FILE_GONE);
        }
        if (currentStamp != capturedStamp) {
            return new PsiSnapshot.Validity.Invalid(PsiSnapshot.InvalidReason.DOCUMENT_MODIFIED);
        }
        if (startOffset < 0 || endOffset > documentLength || startOffset > endOffset) {
            return new PsiSnapshot.Validity.Invalid(PsiSnapshot.InvalidReason.RANGE_OUT_OF_BOUNDS);
        }
        return PsiSnapshot.Validity.Valid.INSTANCE;
    }
}
