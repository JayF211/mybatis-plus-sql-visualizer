package cn.song.mybatisplus.visualizer.psi

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

/**
 * T-PsiCaptureProtocol T-PCP-01：PSI 元素跨线程读取的**两段式捕获快照**。
 *
 * 形态对齐第八轮报告 §7.2 Z-16（双快照 + 前后比对 + 交叉验证），按平台能力落地——我方是**读侧**，
 * 用 [Document.getModificationStamp] 而非 commit document（不需要写回）：
 *  1. **EDT 侧捕获**（[capture]）：记下元素所属 [VirtualFile]、[Document]、textRange 与当时的
 *     `modificationStamp`；
 *  2. **后台侧校验**（[validate]）：文件仍有效 / stamp 未变 / 区间仍在界内，任一不符即
 *     [Validity.Invalid]——调用方**如实降级**（gutter 标记不渲染、跳转不跳并留诊断），
 *     不得拿失效区间去算行号（那是静默错值）。
 *
 * 为什么需要它：`ui/gutter/SqlGutterLineMarkerProvider` 与
 * `ui/gutter/StringSqlMethodGutterLineMarkerProvider` 在 EDT 捕获 [PsiElement] 后把元素带进
 * `executeOnPooledThread`——捕获与读取之间若发生写操作（用户输入 / 平台 reparse），元素区间可能
 * 已失效，而原实现只包了读权限（`ReadAction`），没有「元素是否仍有效」的校验。
 *
 * **只读侧不做文本交叉验证**（计划 §三 T-PCP-01 原列「element 文本」字段，实施时按平台事实修正）：
 * PSI 树由**已提交**的文档内容构建，编辑器里的未提交变更只体现在 [Document] 上。若以
 * 「PSI 文本 == 文档对应区间文本」作第四项校验，用户刚敲完字（PSI 尚未 commit）点击 gutter 就会被
 * 误判为「已失效」，标记/记录静默消失；真实变更面已由 `modificationStamp` 覆盖，故本工具只做三项校验。
 *
 * 线程约定：
 *  - [capture] 在 **EDT（或持读权限的线程）** 调用——读 `containingFile` / `textRange` 属 PSI 读；
 *  - [validate] / [lineNumber] 须在**读权限内**调用（后台线程须自行包 `ReadAction`）——两者读
 *    [VirtualFile.isValid] 与文档状态。
 */
class PsiSnapshot private constructor(
    private val file: VirtualFile,
    private val document: Document,
    private val startOffset: Int,
    private val endOffset: Int,
    private val modificationStamp: Long,
) {

    /** 捕获时元素所属文件的绝对路径（与 [com.intellij.openapi.vfs.VirtualFile.getPath] 同形）。 */
    val filePath: String get() = file.path

    /** 校验结果：有效 / 失效（带原因，供诊断与用例断言）。 */
    sealed class Validity {
        data object Valid : Validity()
        data class Invalid(val reason: InvalidReason) : Validity()
    }

    /** 失效原因（三态 + 捕获期缺失，理由文案供 `LOG.debug` 诊断）。 */
    enum class InvalidReason(val description: String) {
        /** 虚拟文件已失效（被删除 / 移动 / 重建）。 */
        FILE_GONE("虚拟文件已失效（文件被删除/移动/重建）"),

        /** 文档 modificationStamp 与捕获时不一致（捕获后发生过文档写）。 */
        DOCUMENT_MODIFIED("捕获后文档已变更（modificationStamp 变化）"),

        /** 捕获的 textRange 越出当前文档边界（元素区间已不可用）。 */
        RANGE_OUT_OF_BOUNDS("textRange 越出文档边界（元素区间已失效）"),
    }

    /**
     * 校验捕获是否仍可用（**须在读权限内调用**）。
     *
     * 判定顺序固定为「文件 → stamp → 区间」：文件没了就没必要谈 stamp；stamp 变了则区间必已重算。
     */
    fun validate(): Validity = validateCaptured(
        capturedStamp = modificationStamp,
        startOffset = startOffset,
        endOffset = endOffset,
        fileValid = file.isValid,
        currentStamp = document.modificationStamp,
        documentLength = document.textLength,
    )

    /**
     * 捕获点所在行号（1-based；**须在读权限内调用，且仅在 [validate] 返回 [Validity.Valid] 后有意义**）。
     */
    fun lineNumber(): Int = document.getLineNumber(startOffset) + 1

    companion object {

        /**
         * EDT 侧捕获（元素无虚拟文件 / 无文档时返回 null——调用方按「无法定位」降级）。
         *
         * 注意：元素所属文件可能**不是**文本文件（无 [Document]），此时快照无意义，故返回 null。
         */
        fun capture(element: PsiElement): PsiSnapshot? {
            val file = element.containingFile?.virtualFile ?: return null
            val range = element.textRange ?: return null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
            return PsiSnapshot(
                file = file,
                document = document,
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                modificationStamp = document.modificationStamp,
            )
        }
    }
}

/**
 * [PsiSnapshot.validate] 的纯函数内核（与平台对象解耦——表驱动用例可直接喂三态事实，
 * 不必构造失效的 [VirtualFile] / 真改文档）。
 *
 * @param capturedStamp 捕获时的 `modificationStamp`
 * @param startOffset / endOffset 捕获的 textRange 边界（文档偏移）
 * @param fileValid 当前 [VirtualFile.isValid]
 * @param currentStamp 当前 `modificationStamp`
 * @param documentLength 当前文档文本长度
 */
internal fun validateCaptured(
    capturedStamp: Long,
    startOffset: Int,
    endOffset: Int,
    fileValid: Boolean,
    currentStamp: Long,
    documentLength: Int,
): PsiSnapshot.Validity = when {
    !fileValid -> PsiSnapshot.Validity.Invalid(PsiSnapshot.InvalidReason.FILE_GONE)
    currentStamp != capturedStamp -> PsiSnapshot.Validity.Invalid(PsiSnapshot.InvalidReason.DOCUMENT_MODIFIED)
    startOffset < 0 || endOffset > documentLength || startOffset > endOffset ->
        PsiSnapshot.Validity.Invalid(PsiSnapshot.InvalidReason.RANGE_OUT_OF_BOUNDS)
    else -> PsiSnapshot.Validity.Valid
}
