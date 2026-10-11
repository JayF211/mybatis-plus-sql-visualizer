// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/XmlInlineSqlGutterLineMarkerProvider.kt 移植
package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlInlineSqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector;
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

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.Icon;

/**
 * 通用 XML 内嵌 SQL 预览图标（O-72 T-XmlInlineSql）：叶子标签文本首词命中 SQL
 * 关键字（CDATA 或普通文本，根元素与标签名不限）→ 该标签行显示预览图标。
 *
 * <p>作为 mapper / pages / hbm 三套白名单之后的<b>兜底通道</b>，认领条件与排除集合
 * 见 {@link XmlInlineSqlDetector}；总开关 {@code xmlInlineSqlEnabled}（默认开）。</p>
 *
 * <p><b>性能红线</b>：快速路径只做局部语法判定（叶子判定 + 截断前缀首词），零 resolve、
 * 零全文件遍历、零转换；点击转换走 {@link SqlPreviewService#previewAsync} 后台线程。</p>
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="XML" .../>}。</p>
 */
public class XmlInlineSqlGutterLineMarkerProvider implements LineMarkerProvider {

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 必须注册在 leaf 元素：候选标签的 name token
        if (!(element instanceof XmlToken)) return null;
        XmlToken token = (XmlToken) element;
        if (token.getTokenType() != XmlTokenType.XML_NAME) return null;
        XmlTag tag = token.getParent() instanceof XmlTag ? (XmlTag) token.getParent() : null;
        if (tag == null) return null;
        // F51（T-PerfQuick5）：F19 快路径（xmlInlineSqlEnabled+总闸）与 R2 分组显隐并闸——
        // 方法头单次取 state 后传 (Class, State?) 重载，每到达元素 app 级服务查找 2→1
        // （原形态：gutterFastPathEnabled 内 1 次 + enabledFor(Class, Boolean) 包装内 1 次）。
        // 语义逐值等价：state 不可读承接两道闸原 runCatching 兜底（全放行）；可读时合取式
        // xmlInlineSqlEnabled && showGutterIcon && 组开关 与原两道闸串联逐值相同
        // （GutterGroupsTest testF51_合成对照 真值表在案）。
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            gutterState = null;
        }
        if (gutterState != null && (!gutterState.getXmlInlineSqlEnabled() || !gutterState.getShowGutterIcon())) {
            return null;
        }
        if (!GutterGroups.enabledFor(getClass(), gutterState)) return null; // R2 分组显隐
        if (!XmlInlineSqlDetector.isSqlCarrierTag(tag)) return null;
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.xmlInlineSql.tooltip"),
                XmlInlineSqlNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.xmlInlineSql.accessible.name")
        );
    }

    /** 点击处理：后台解析 + 渲染（HQL 转换失败回退原文直显），结果走统一展示入口。 */
    private static final class XmlInlineSqlNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final XmlInlineSqlNavigationHandler INSTANCE = new XmlInlineSqlNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            XmlTag tag = elt.getParent() instanceof XmlTag ? (XmlTag) elt.getParent() : null;
            if (tag == null) return;
            SqlPreviewService.previewAsync(project,
                    () -> XmlInlineSqlPreviewService.INSTANCE.preview(project, tag),
                    result -> {
                        SqlPreviewService.showResult(project, result);
                        return kotlin.Unit.INSTANCE;
                    });
        }
    }

    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", XmlInlineSqlGutterLineMarkerProvider.class);

    /**
     * 选择区内通用 SQL 载体候选（tag 起始 offset 在选区范围内，按 offset 排序）——
     * ConvertToSqlAction 选择区候选交互复用；排除集合保证与 mapper 语句候选不重叠；
     * 纯 PSI 遍历零 resolve。
     *
     * @param file  待扫描的 XML 文件
     * @param start 选区起始 offset（含）
     * @param end   选区结束 offset（不含）
     * @return 命中的通用 SQL 载体标签列表（按起始 offset 升序）
     */
    public static List<XmlTag> carriersInRange(@NotNull XmlFile file, int start, int end) {
        List<XmlTag> all = new ArrayList<>(PsiTreeUtil.collectElementsOfType(file, XmlTag.class));
        List<XmlTag> result = new ArrayList<>();
        for (XmlTag tag : all) {
            int startOffset = tag.getTextRange().getStartOffset();
            if (startOffset >= start && startOffset < end && XmlInlineSqlDetector.isSqlCarrierTag(tag)) {
                result.add(tag);
            }
        }
        result.sort(Comparator.comparingInt(t -> t.getTextRange().getStartOffset()));
        return result;
    }
}
