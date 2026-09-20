package cn.song.mybatisplus.visualizer.ui.inlay

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind
import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.InlayPresentationFactory
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 预览语句类型载体 + 作用域锚点（T-InsertColumnInlay O-44b）。
 *
 * **为什么需要作用域判定**：inlay 的 EP（`com.intellij.codeInsight.inlayProvider`）是 **language 级**的——
 * 一旦注册 `language="SQL"`，IDE 内**所有** SQL 编辑器（含用户自己的 `.sql` 文件）都会向本 provider
 * 索取提示。本任务唯一的真实风险即「作用域泄漏到用户文件」，故 provider 首件事就是判定
 * 「这个编辑器是不是本面板的预览编辑器」。
 *
 * **双锚点（两条都成立才算命中）**：
 *  1. **文件名** = 本插件预览文件的保留名（[PREVIEW_FILE_NAME] 一级 / [MEMORY_PREVIEW_FILE_NAME] 二级）；
 *  2. **userData** = [KEY] 语句类型载体（Editor 优先、PSI 文件兜底，同 M16.2 补全上下文双载体口径）。
 *
 * 单靠 userData 不够：它只证明「有人往这个编辑器写过本插件的键」，锚点二把「本插件预览文件」
 * 这个事实也钉上，两者同时成立才放行——用户自己的 `.sql` 文件即使被误挂了键（或文件恰好同名但
 * 非本面板产物）也不满足全部条件。反之单靠文件名也不够（同名用户文件会误命中），故取合取。
 *
 * **文件名取值口径（订正计划 §1.2 的实测缺陷）**：预览文件有**三个不同形态**，一级（项目内临时
 * VFS 文件，inlay 真正会渲染的那一级）是 `mpsql-preview.sql`——**不是** `mpsql-preview-*.sql`
 * （少一个连字符，`-*` 通配匹配不到），二级内存 PSI 才是 `mpsql-preview-memory.sql`。故保留名
 * 逐个列举而非通配，两个常量同时供面板建文件处引用（单一来源，杜绝锚点与真实文件名漂移）。
 * 测试缝文件名 `preview.sql`（`SqlPreviewPanel.createPreviewFileForTest`）**有意不列入**：
 * 它是最像用户文件的普通名，列入即把「用户自己的 `.sql` 文件」判据打穿。
 */
object PreviewInlayScope {

    /** 一级预览文件（项目内临时 VFS 文件）名。 */
    const val PREVIEW_FILE_NAME: String = "mpsql-preview.sql"

    /** 二级预览文件（内存 PSI，降级链第二级）名。 */
    const val MEMORY_PREVIEW_FILE_NAME: String = "mpsql-preview-memory.sql"

    private val RESERVED_FILE_NAMES = setOf(PREVIEW_FILE_NAME, MEMORY_PREVIEW_FILE_NAME)

    /** 预览语句类型载体（值 = 当前预览的语句类型；null = 非本面板预览 / 无语句类型语义）。 */
    val KEY: Key<XmlStatementKind> = Key.create("MP_PREVIEW_STATEMENT_KIND")

    /** 写载体（Editor 与 PSI 文件双写，面板收口处调用）；[kind] 为 null 即清除。 */
    fun attach(target: UserDataHolder, kind: XmlStatementKind?) {
        target.putUserData(KEY, kind)
    }

    /**
     * 双锚点判定 + 取语句类型：命中返回语句类型，未命中返回 null（provider 据此不产任何提示）。
     *
     * @param file   平台传入的 PSI 文件（裸 Document 降级级无 PSI ⇒ provider 不被调用，天然不渲染）
     * @param editor 平台传入的编辑器（userData 主载体：用户输入后 PSI 可能被 commit 重建而丢 userData，
     *               Editor 对象编辑期间稳定）
     */
    fun previewStatementKind(file: PsiFile?, editor: Editor?): XmlStatementKind? {
        if (file == null || file.name !in RESERVED_FILE_NAMES) return null
        return editor?.getUserData(KEY) ?: file.getUserData(KEY)
    }
}

/**
 * INSERT 目标列 inlay provider（T-InsertColumnInlay O-44b）。
 *
 * 在**本面板预览编辑器**的 INSERT 语句上，为每个 VALUES 表达式 / SELECT 投影挂目标列名提示，
 * 让多列动态 INSERT 的「列-值错位」一眼可见。规划规则见 [InsertColumnInlayPlanner]。
 *
 * 三道闸门（任一不满足即返回 null，平台侧「无 collector」= 零 inlay）：
 *  1. **作用域**：[PreviewInlayScope.previewStatementKind] 双锚点（文件名 + userData）；
 *  2. **语句类型**：`xmlModel.kind == INSERT`（面板经 [PreviewInlayScope.attach] 注入；
 *     非 INSERT 与「无 xmlModel 的非 XML 预览链」都取不到 INSERT 值 → 不提示）；
 *  3. **静态可判**：[InsertColumnInlayPlanner] 内部的列/值对应判定（不可判即不提示）。
 *
 * **零设置项**：EP 未注册 `settingsKeyId`，平台按 `InlayHintsSettings.computeIsEnabledByDefault`
 * 的「无匹配 bean → true」口径默认开启（243 源码实证），用户可用平台的 inlay 全局开关或
 * 语言级开关关闭；本 provider 不新增设置页面（`createConfigurable` 为接口强制实现的空壳，
 * 无 `settingsKeyId` 即无设置 UI 入口）。
 *
 * **纯显示层**：只读编辑器文档文本 + 向 InlayModel 投提示，不改 SQL 文本、不写文档。
 * 提示内容只依赖文档文本（不依赖 PSI 树/索引），故实现 [DumbAware]（索引期间照常可用）。
 */
class PreviewInsertColumnInlayProvider : InlayHintsProvider<NoSettings>, DumbAware {

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector? {
        if (PreviewInlayScope.previewStatementKind(file, editor) != XmlStatementKind.INSERT) return null
        return InsertColumnInlayCollector(PresentationFactory(editor))
    }

    override fun createSettings(): NoSettings = NoSettings()

    override val name: String
        get() = MpSqlVisualizerBundle.message("inlay.insertColumn.name")

    override val key: SettingsKey<NoSettings> = SettingsKey("sqlVisualizer.insertColumn")

    override val previewText: String?
        get() = null

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable = NoOpConfigurable
}

/**
 * INSERT 目标列提示收集器。
 *
 * 平台两处调用形态不同，故用「一次性」闸门：
 *  - 真机 pass（`InlayHintsPass.doCollectInformation`）按 Divider 分段元素逐个调用，返回 false
 *    即把本 collector 移出后续调度；
 *  - 平台测试链（`CollectorWithSettings.collectTraversing`，`SyntaxTraverser.psiTraverser(file)`）
 *    **忽略返回值**、对每个 PSI 元素都调用一次——无闸门会按元素数量重复投提示。
 *
 * 故首次调用即投完全部提示并置位（提示位点取自编辑器当前文档全文，与传入元素无关）。
 */
private class InsertColumnInlayCollector(
    private val factory: InlayPresentationFactory,
) : InlayHintsCollector {

    private var collected = false

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (collected) return false
        collected = true
        val text = editor.document.text
        for (hint in InsertColumnInlayPlanner.plan(text)) {
            // 防御：位点由本次同一份文本算出，理论上必在范围内；越界时平台 addInlineElement 会硬断言
            //（显示层功能不得因断言升级成异常），故越界条目直接跳过。
            if (hint.offset < 0 || hint.offset > text.length) continue
            // relatesToPrecedingText=false：提示归属其后的值表达式（同参数名提示口径）；
            // placeAtTheEndOfLine=false：不占行尾锚点（行内提示，允许光标落在其后继续编辑）
            sink.addInlineElement(hint.offset, false, factory.text(hint.columnName), false)
        }
        return false
    }
}

/**
 * 空壳配置页（接口强制实现）。
 *
 * EP 未注册 `settingsKeyId` ⇒ 平台设置页不会为本 provider 建入口（`createConfigurable` 无调用方），
 * 本对象只是接口契约的落点；不做任何 UI（返回空面板，无控件即无状态）。
 */
private object NoOpConfigurable : ImmediateConfigurable {
    override fun createComponent(listener: ChangeListener): JComponent = JPanel()
}
