package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import kotlin.Unit;

import java.awt.event.MouseEvent;
import java.util.Set;
import javax.swing.Icon;

/**
 * hbm.xml 命名查询预览图标（T-H2，20260907-1630）：{@code <hibernate-mapping>} 下的
 * {@code <query>}（HQL）/ {@code <sql-query>}（原生 SQL）语句行显示预览图标，点击弹出 SQL 预览。
 *
 * <p><b>性能红线</b>：快速路径只做 O(1) 语法判定（tag 名 + 父标签名），零 resolve、零遍历；
 * 点击转换走 {@link SqlPreviewService#previewAsync} 后台线程。
 *
 * <p><b>去重保证</b>：与 {@link XmlSqlGutterLineMarkerProvider}（要求父标签 {@code mapper} 的
 * select/insert/update/delete）父标签名互斥，天然不重复。
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="XML" .../>}。
 */
public class HbmNamedQueryGutterLineMarkerProvider implements LineMarkerProvider {

    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", HbmNamedQueryGutterLineMarkerProvider.class);

    /** 认领的命名查询标签名（值 = hbm QueryKind）。 */
    public static final Set<String> QUERY_TAGS = Set.of("query", "sql-query");

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 必须注册在 leaf 元素：语句标签的 name token（O(1) 判定，零 resolve）
        if (!(element instanceof XmlToken)) return null;
        XmlToken token = (XmlToken) element;
        if (token.getTokenType() != XmlTokenType.XML_NAME) return null;
        if (!(token.getParent() instanceof XmlTag)) return null;
        XmlTag tag = (XmlTag) token.getParent();
        if (!QUERY_TAGS.contains(tag.getName())) return null;
        XmlTag parentTag = tag.getParentTag();
        if (parentTag == null || !"hibernate-mapping".equals(parentTag.getName())) return null;
        if (tag.getAttributeValue("name") == null) return null;
        if (!gutterEnabled()) return null;
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.hbmNamedQuery.tooltip"),
                HbmNamedQueryNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.hbmNamedQuery.accessible.name")
        );
    }

    /** 读取 gutter 图标开关（测试环境 service 未注册时按默认 true 处理）。 */
    private static boolean gutterEnabled() {
        try {
            return PluginSettings.getInstance().getState().getShowGutterIcon();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 从 hbm 标签名推断查询种类（gutter 点击复用）。 */
    @Nullable
    public static HbmXmlMapping.QueryKind kindOf(String tagName) {
        if ("query".equals(tagName)) return HbmXmlMapping.QueryKind.HQL;
        if ("sql-query".equals(tagName)) return HbmXmlMapping.QueryKind.NATIVE;
        return null;
    }

    /** 点击处理：按查询种类分派 HQL / 原生 SQL 转换（后台线程），结果走统一展示入口。 */
    private static final class HbmNamedQueryNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final HbmNamedQueryNavigationHandler INSTANCE = new HbmNamedQueryNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            if (!(elt.getParent() instanceof XmlTag)) return;
            XmlTag tag = (XmlTag) elt.getParent();
            SqlPreviewService service = project.getService(SqlPreviewService.class);
            SqlPreviewService.previewAsync(project,
                    () -> service.previewHbmNamedQueryTag(project, tag),
                    result -> {
                        SqlPreviewService.showResult(project, result);
                        return Unit.INSTANCE;
                    });
        }
    }
}
