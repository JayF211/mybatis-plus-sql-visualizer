// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inlay/PreviewInsertColumnInlayProvider.kt 移植
package cn.song.mybatisplus.visualizer.ui.inlay;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind;
import com.intellij.codeInsight.hints.ChangeListener;
import com.intellij.codeInsight.hints.ImmediateConfigurable;
import com.intellij.codeInsight.hints.InlayHintsCollector;
import com.intellij.codeInsight.hints.InlayHintsProvider;
import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.InlayPresentationFactory;
import com.intellij.codeInsight.hints.NoSettings;
import com.intellij.codeInsight.hints.SettingsKey;
import com.intellij.codeInsight.hints.presentation.PresentationFactory;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * INSERT 目标列 inlay provider（T-InsertColumnInlay O-44b）。
 *
 * <p>在<strong>本面板预览编辑器</strong>的 INSERT 语句上，为每个 VALUES 表达式 / SELECT 投影挂目标列名
 * 提示，让多列动态 INSERT 的「列-值错位」一眼可见。规划规则见 {@link InsertColumnInlayPlanner}。
 *
 * <p>三道闸门（任一不满足即返回 null，平台侧「无 collector」= 零 inlay）：
 * <ol>
 *  <li><strong>作用域</strong>：{@link PreviewInlayScope#previewStatementKind} 双锚点（文件名 + userData）；</li>
 *  <li><strong>语句类型</strong>：{@code xmlModel.kind == INSERT}（面板经 {@link PreviewInlayScope#attach} 注入；
 *      非 INSERT 与「无 xmlModel 的非 XML 预览链」都取不到 INSERT 值 → 不提示）；</li>
 *  <li><strong>静态可判</strong>：{@link InsertColumnInlayPlanner} 内部的列/值对应判定（不可判即不提示）。</li>
 * </ol>
 *
 * <p><strong>零设置项</strong>：EP 未注册 {@code settingsKeyId}，平台按
 * {@code InlayHintsSettings.computeIsEnabledByDefault} 的「无匹配 bean → true」口径默认开启
 * （243 源码实证），用户可用平台的 inlay 全局开关或语言级开关关闭；本 provider 不新增设置页面
 * （{@code createConfigurable} 为接口强制实现的空壳，无 {@code settingsKeyId} 即无设置 UI 入口）。
 *
 * <p><strong>纯显示层</strong>：只读编辑器文档文本 + 向 InlayModel 投提示，不改 SQL 文本、不写文档。
 * 提示内容只依赖文档文本（不依赖 PSI 树/索引），故实现 {@link DumbAware}（索引期间照常可用）。
 */
public final class PreviewInsertColumnInlayProvider implements InlayHintsProvider<NoSettings>, DumbAware {

    /** 设置键（Kotlin {@code override val key} 等价；{@link SettingsKey} 不可变，无实例态）。 */
    private static final SettingsKey<NoSettings> KEY = new SettingsKey<>("sqlVisualizer.insertColumn");

    @Override
    @Nullable
    public InlayHintsCollector getCollectorFor(
            @NotNull PsiFile file,
            @NotNull Editor editor,
            @NotNull NoSettings settings,
            @NotNull InlayHintsSink sink) {
        if (PreviewInlayScope.previewStatementKind(file, editor) != XmlStatementKind.INSERT) return null;
        return new InsertColumnInlayCollector(new PresentationFactory(editor));
    }

    @Override
    @NotNull
    public NoSettings createSettings() {
        return new NoSettings();
    }

    @Override
    @NotNull
    public String getName() {
        return MpSqlVisualizerBundle.message("inlay.insertColumn.name");
    }

    @Override
    @NotNull
    public SettingsKey<NoSettings> getKey() {
        return KEY;
    }

    @Override
    @Nullable
    public String getPreviewText() {
        return null;
    }

    @Override
    @NotNull
    public ImmediateConfigurable createConfigurable(@NotNull NoSettings settings) {
        return NoOpConfigurable.INSTANCE;
    }
}

/**
 * INSERT 目标列提示收集器。
 *
 * <p>平台两处调用形态不同，故用「一次性」闸门：
 * <ul>
 *  <li>真机 pass（{@code InlayHintsPass.doCollectInformation}）按 Divider 分段元素逐个调用，返回 false
 *      即把本 collector 移出后续调度；</li>
 *  <li>平台测试链（{@code CollectorWithSettings.collectTraversing}，{@code SyntaxTraverser.psiTraverser(file)}）
 *      <strong>忽略返回值</strong>、对每个 PSI 元素都调用一次——无闸门会按元素数量重复投提示。</li>
 * </ul>
 *
 * <p>故首次调用即投完全部提示并置位（提示位点取自编辑器当前文档全文，与传入元素无关）。
 */
final class InsertColumnInlayCollector implements InlayHintsCollector {

    private final InlayPresentationFactory factory;

    private boolean collected = false;

    InsertColumnInlayCollector(InlayPresentationFactory factory) {
        this.factory = factory;
    }

    @Override
    public boolean collect(PsiElement element, Editor editor, InlayHintsSink sink) {
        if (collected) return false;
        collected = true;
        String text = editor.getDocument().getText();
        for (InsertColumnInlayPlanner.Hint hint : InsertColumnInlayPlanner.plan(text)) {
            // 防御：位点由本次同一份文本算出，理论上必在范围内；越界时平台 addInlineElement 会硬断言
            //（显示层功能不得因断言升级成异常），故越界条目直接跳过。
            if (hint.offset() < 0 || hint.offset() > text.length()) continue;
            // relatesToPrecedingText=false：提示归属其后的值表达式（同参数名提示口径）；
            // placeAtTheEndOfLine=false：不占行尾锚点（行内提示，允许光标落在其后继续编辑）
            sink.addInlineElement(hint.offset(), false, factory.text(hint.columnName()), false);
        }
        return false;
    }
}

/**
 * 空壳配置页（接口强制实现）。
 *
 * <p>EP 未注册 {@code settingsKeyId} ⇒ 平台设置页不会为本 provider 建入口（{@code createConfigurable}
 * 无调用方），本对象只是接口契约的落点；不做任何 UI（返回空面板，无控件即无状态）。
 */
final class NoOpConfigurable implements ImmediateConfigurable {

    /** Kotlin object 单例等价。 */
    static final NoOpConfigurable INSTANCE = new NoOpConfigurable();

    private NoOpConfigurable() {
    }

    @Override
    public JComponent createComponent(ChangeListener listener) {
        return new JPanel();
    }
}
