package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.PagesXmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import kotlin.Unit;

import java.awt.event.MouseEvent;
import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * maintainPage 预览图标（F-45）：`<maintainPage>` 行显示预览图标，
 * 点击弹出 SQL 预览（复用 {@link SqlPreviewService#showResult} 链路）。
 *
 * <p><b>性能红线</b>：快速路径只做 O(1) 语法判定（tag 名 + 父标签名），零 resolve、零遍历、
 * 零转换；tooltip 静态文案；点击转换走 {@link SqlPreviewService#previewAsync} 后台线程。
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="XML" .../>}。
 */
public class PagesXmlGutterLineMarkerProvider implements LineMarkerProvider {

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 必须注册在 leaf 元素：只接受 maintainPage 标签的 name token（O(1) 判定，零 resolve）
        if (!(element instanceof XmlToken)) return null;
        XmlToken token = (XmlToken) element;
        if (token.getTokenType() != XmlTokenType.XML_NAME) return null;
        XmlTag tag = token.getParent() instanceof XmlTag ? (XmlTag) token.getParent() : null;
        if (tag == null) return null;
        // maintainPage 标签 + 直接父级是 <pages>（避免非 pages XML 误显）
        if (!"maintainPage".equals(tag.getName())) return null;
        if (tag.getParentTag() == null || !"pages".equals(tag.getParentTag().getName())) return null;
        if (!gutterEnabled()) return null;
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.pages.tooltip"),
                PagesGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.pages.accessible.name")
        );
    }

    /** 读取 gutter 图标开关（测试环境 service 未注册时按默认 true 处理）。 */
    private boolean gutterEnabled() {
        try {
            return PluginSettings.getInstance().getState().getShowGutterIcon();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 点击处理：后台解析 + 渲染（EDT 禁止 PSI 慢操作），结果走统一展示入口。 */
    private static final class PagesGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final PagesGutterNavigationHandler INSTANCE = new PagesGutterNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            XmlTag tag = elt.getParent() instanceof XmlTag ? (XmlTag) elt.getParent() : null;
            if (tag == null) return;
            XmlFile file = tag.getContainingFile() instanceof XmlFile ? (XmlFile) tag.getContainingFile() : null;
            if (file == null) return;
            SqlPreviewService.Companion.previewAsync(project,
                    () -> PagesXmlPreviewService.INSTANCE.preview(file.getText(), tag.getAttributeValue("id"), project),
                    result -> { SqlPreviewService.Companion.showResult(project, result, null); return kotlin.Unit.INSTANCE; });
        }
    }

    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", PagesXmlGutterLineMarkerProvider.class);

    /**
     * 选择区内 maintainPage 候选（tag 起始 offset 在选区范围内，按 offset 排序）——
     * ConvertToSqlAction 选择区候选交互复用（与 Java/mapper 选择区候选语义一致）；
     * 纯 PSI 遍历零 resolve。
     *
     * @param file  待扫描的 XML 文件
     * @param start 选区起始 offset（含）
     * @param end   选区结束 offset（不含）
     * @return 命中的 maintainPage 标签列表（按起始 offset 升序）
     */
    public static List<XmlTag> statementsInRange(XmlFile file, int start, int end) {
        List<XmlTag> all = new ArrayList<>(PsiTreeUtil.collectElementsOfType(file, XmlTag.class));
        List<XmlTag> result = new ArrayList<>();
        for (XmlTag it : all) {
            if ("maintainPage".equals(it.getName())
                    && it.getTextRange().getStartOffset() >= start
                    && it.getTextRange().getStartOffset() < end) {
                result.add(it);
            }
        }
        result.sort(Comparator.comparingInt(t -> t.getTextRange().getStartOffset()));
        return result;
    }
}
