// 自 master ui/ExplainPlanDialog.kt 移植（T-PreviewSideQuery P2：EXPLAIN 结果非模态承载）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.Dimension;

/**
 * T-PreviewSideQuery P2：非模态承载 EXPLAIN 结果，不替换预览编辑器。
 *
 * <p>结构化视图（{@link ExplainPlanResultView}）与直送结果表格都通过本对话框展示；关闭后
 * 预览 SQL/参数仍在原处可继续编辑。</p>
 */
public class ExplainPlanDialog extends DialogWrapper {

    private final JComponent content;

    public ExplainPlanDialog(@NotNull Project project, @NotNull JComponent content) {
        super(project, false);
        this.content = content;
        setTitle(MpSqlVisualizerBundle.message("panel.explain"));
        setModal(false);
        init();
        // 插件语言独立于 IDE 语言：平台 cancelAction 文案跟随 IDE（英文 IDE 下显示 Cancel），
        // 必须按插件 bundle 覆盖（同 SqlPreviewDialog 的既有做法）。
        setCancelButtonText(MpSqlVisualizerBundle.message("panel.explain.close"));
    }

    @Override
    protected JComponent createCenterPanel() {
        JBScrollPane pane = new JBScrollPane(content);
        pane.setPreferredSize(new Dimension(900, 560));
        pane.setBorder(JBUI.Borders.empty());
        return pane;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getCancelAction()};
    }

    /** 测试访问器：承载内容。 */
    @NotNull
    public JComponent contentForTest() {
        return content;
    }

    /** 测试访问器：取消按钮文案（断言插件语言绑定，不受 IDE 语言影响）。 */
    @NotNull
    public String cancelButtonTextForTest() {
        javax.swing.JButton button = getButton(getCancelAction());
        return button != null && button.getText() != null ? button.getText() : "";
    }
}
