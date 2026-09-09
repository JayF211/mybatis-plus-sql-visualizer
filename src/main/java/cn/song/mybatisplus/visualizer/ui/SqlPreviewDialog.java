package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Toolkit;

/**
 * SQL 预览弹窗（M3-02，F-07 弹窗展示方式）。
 *
 * 复用 SqlPreviewPanel 展示 SQL 与参数对照，底部提供"复制 / 关闭"。
 */
public class SqlPreviewDialog extends DialogWrapper {

    private final SqlPreviewPanel panel;

    public SqlPreviewDialog(@Nullable Project project, @NotNull PreviewResult result) {
        this(project, result, null);
    }

    public SqlPreviewDialog(@Nullable Project project, @NotNull PreviewResult result, @Nullable String sourceLocation) {
        super(project, false, DialogWrapper.IdeModalityType.MODELESS);
        this.panel = new SqlPreviewPanel(project);
        setTitle(MpSqlVisualizerBundle.message("dialog.preview.title"));
        panel.setSourceLocation(sourceLocation);
        panel.showResult(result);
        Disposer.register(getDisposable(), panel);
        panel.onClosePanelRequested = this::dispose;
        panel.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
        init();
        setCancelButtonText(MpSqlVisualizerBundle.message("dialog.preview.cancel"));
        Dimension size = initialSize();
        setSize(size.width, size.height);
    }

    @NotNull
    @Override
    protected JComponent createCenterPanel() {
        return panel;
    }

    /**
     * T-RefreshDbSteal F2：程序化关闭（供 SqlPreviewService 弹窗单例化关旧开新）。
     * DialogWrapper.dispose 在平台侧 protected，同类内提升为 public 包装——语义与用户
     * 点关闭一致（级联 Disposer 释放面板，rehost 监听随之移除）。
     */
    public void doDispose() {
        dispose();
    }

    /** internal 测试访问器（面板「关闭 SQL 面板」回调链路断言）。 */
    public SqlPreviewPanel panelForTest() {
        return panel;
    }

    @NotNull
    @Override
    protected Action[] createActions() {
        return new Action[]{
                new DialogWrapperAction(MpSqlVisualizerBundle.message("panel.executeSql")) {
                    @Override
                    protected void doAction(java.awt.event.ActionEvent actionEvent) {
                        panel.executeSql();
                    }
                },
                new DialogWrapperAction(MpSqlVisualizerBundle.message("panel.copySql")) {
                    @Override
                    protected void doAction(java.awt.event.ActionEvent actionEvent) {
                        panel.copySql();
                    }
                },
                getCancelAction()
        };
    }

    @Override
    public String getDimensionServiceKey() {
        return DIMENSION_KEY;
    }

    public static final String DIMENSION_KEY = "cn.song.mybatisplus.visualizer.SqlPreviewDialog";
    public static final int MIN_WIDTH = 640;
    public static final int MIN_HEIGHT = 420;
    public static final double SCREEN_RATIO_WIDTH = 0.62;
    public static final double SCREEN_RATIO_HEIGHT = 0.65;
    public static final double SCREEN_RATIO_CAP = 0.85;
    public static final int DEFAULT_WIDTH = 900;
    public static final int DEFAULT_HEIGHT = 600;

    /**
     * 计算弹窗默认尺寸（M14）：按屏幕比例（宽 62% / 高 65%），
     * 夹在「下限 900×600」与「屏幕 85% 上限」之间；小屏时上限优先（不超屏）。
     */
    public static Dimension initialSize() {
        return initialSize(screenWidthOf(), screenHeightOf());
    }

    public static Dimension initialSize(int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) return new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        int w = fitWithin((int) (screenWidth * SCREEN_RATIO_WIDTH), screenWidth, DEFAULT_WIDTH);
        int h = fitWithin((int) (screenHeight * SCREEN_RATIO_HEIGHT), screenHeight, DEFAULT_HEIGHT);
        return new Dimension(w, h);
    }

    private static int fitWithin(int ratioValue, int screenSize, int minDefault) {
        int cap = (int) (screenSize * SCREEN_RATIO_CAP);
        return minDefault <= cap ? Math.min(Math.max(ratioValue, minDefault), cap) : cap;
    }

    private static int screenWidthOf() {
        try {
            return Toolkit.getDefaultToolkit().getScreenSize().width;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int screenHeightOf() {
        try {
            return Toolkit.getDefaultToolkit().getScreenSize().height;
        } catch (Throwable t) {
            return 0;
        }
    }
}
