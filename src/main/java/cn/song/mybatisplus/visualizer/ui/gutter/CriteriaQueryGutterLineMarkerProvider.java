package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import kotlin.Unit;

import java.awt.event.MouseEvent;
import javax.swing.Icon;

/**
 * JPA CriteriaQuery gutter 预览图标（波次一）：识别 JPA CriteriaBuilder 强类型查询调用链
 * （`cq.from(...)` / `cb.equal(...)` 等）所在行显示预览图标，点击弹出 SQL 预览。
 *
 * <p><b>性能红线</b>：快速路径只做 O(1) 语法判定——leaf 锚点为 PsiIdentifier（变量名）或 PsiMethodCallExpression，
 * 复用 {@link CriteriaQueryLocator#locate} 非 null 判定确认光标在 CriteriaQuery 调用链上；
 * 完整解析（CriteriaQueryModel 转换）仅在点击时走 {@link SqlPreviewService#previewAsync} 后台线程。
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="JAVA" .../>}。
 */
public class CriteriaQueryGutterLineMarkerProvider implements LineMarkerProvider {

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 框架只传递 leaf 元素：锚点为 PsiIdentifier（变量名 / 方法名 leaf）。
        // 其它 leaf（关键字、括号、字面量等）不可能位于 CriteriaQuery 调用链上——早退。
        if (!(element instanceof PsiIdentifier)) return null;
        if (!gutterEnabled()) return null;
        // T-PerfSweep F4：文件级门控——不含 criteria 特征子串的文件整体跳过 locate。
        // daemon 每趟对本 provider 传入打开文件的每个方法名 identifier，无门控时每个方法
        // 定义都要付一次 locate 全代码块 PSI 扫描（分析 P1-1 在 dev 的唯一残留）。
        if (!fileMayContainCriteria(element.getContainingFile())) return null;
        // 【去重关键】锚定在包含 CriteriaQuery 调用的方法名上——仅当 element 是方法名 token 时才创建 marker，
        // 避免方法体内每个 PsiIdentifier（变量名、字段名等）都产生重复图标。
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) return null;
        if (method.getNameIdentifier() != element) return null;
        // 复用 CriteriaQueryLocator.locate 非 null 判定（完整扫描代码块，开销可控）
        if (CriteriaQueryLocator.locate(element) == null) return null;
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                CRITERIA_SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.criteria.tooltip"),
                CriteriaQueryGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.criteria.accessible.name")
        );
    }

    private boolean gutterEnabled() {
        try {
            return PluginSettings.getInstance().getState().getShowGutterIcon();
        } catch (Throwable t) {
            return true;
        }
    }

    private static final class CriteriaQueryGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final CriteriaQueryGutterNavigationHandler INSTANCE = new CriteriaQueryGutterNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            SqlPreviewService service = project.getService(SqlPreviewService.class);
            SqlPreviewService.previewAsync(project, () -> service.previewCriteriaQuery(elt),
                    result -> { SqlPreviewService.showResult(project, result, null); return kotlin.Unit.INSTANCE; });
        }
    }

    private static final Icon CRITERIA_SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", CriteriaQueryGutterLineMarkerProvider.class);

    /** criteria API 特征子串：覆盖 CriteriaBuilder / CriteriaQuery / criteria 变量名与 import。 */
    private static final String CRITERIA_MARKER_SUBSTRING = "riteria";

    // T-PerfSweep F4：门控单条目缓存——daemon 逐文件回调，单条目即免掉逐叶子重算；
    // 非 daemon 多线程并发最坏重复计算一次子串匹配，无正确性影响，故不加锁。
    private static volatile PsiFile gatedFile = null;

    private static volatile long gatedStamp = -1;

    private static volatile boolean gatedVerdict = false;

    /**
     * 文件是否可能含 CriteriaQuery 链（保守超集：宁可放行）。判定 = 文本含
     * {@link #CRITERIA_MARKER_SUBSTRING}，按 (file, modificationStamp) 缓存；
     * file 为 null（分离元素）或文本读取失败均放行（回落现状路径，正确性无损）。
     */
    private static boolean fileMayContainCriteria(PsiFile file) {
        if (file == null) return true;
        long stamp = file.getModificationStamp();
        if (file == gatedFile && stamp == gatedStamp) return gatedVerdict;
        boolean verdict;
        try {
            verdict = file.getText().contains(CRITERIA_MARKER_SUBSTRING);
        } catch (Throwable t) {
            verdict = true;
        }
        gatedFile = file;
        gatedStamp = stamp;
        gatedVerdict = verdict;
        return verdict;
    }
}
