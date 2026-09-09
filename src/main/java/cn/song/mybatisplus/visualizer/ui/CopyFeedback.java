package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.icons.AllIcons;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.Timer;

/**
 * T-ExecCopyFix：复制成功反馈（文本按钮适配版）。
 *
 * <p>语义对齐 SqlClickPreview.flashCopied（图标按钮版，T-ClickPreview-05 交付）：复制动作
 * 成功后按钮给出可感知反馈——图标换 {@link AllIcons.Actions#Checked} 对号，文本按钮额外把
 * 文案短暂换成 {@code panel.copied}（「已复制」），一次性 {@link Timer} 到期恢复原文案与原图标。
 *
 * <p>用一次性 Timer 不引入常驻定时器（同先例）；按钮复用即过期，无 Disposer 义务。
 * 多次快速点击时后续调用重置前次的视觉态（原值取自按钮当前值，仍能正确恢复）。
 */
public final class CopyFeedback {

    private CopyFeedback() {
    }

    /** 默认反馈时长（ms）。 */
    static final int DEFAULT_DURATION_MS = 1500;

    public static void flashCopied(JButton button) {
        flashCopied(button, DEFAULT_DURATION_MS);
    }

    public static void flashCopied(JButton button, int durationMs) {
        String prevText = button.getText();
        Icon prevIcon = button.getIcon();
        String prevToolTip = button.getToolTipText();
        button.setIcon(AllIcons.Actions.Checked);
        button.setText(MpSqlVisualizerBundle.message("panel.copied"));
        button.setToolTipText(MpSqlVisualizerBundle.message("panel.copied"));
        Timer timer = new Timer(durationMs, null);
        timer.setRepeats(false);
        timer.addActionListener(e -> {
            button.setIcon(prevIcon);
            button.setText(prevText);
            button.setToolTipText(prevToolTip);
        });
        timer.start();
    }
}
