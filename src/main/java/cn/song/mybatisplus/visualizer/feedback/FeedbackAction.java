package cn.song.mybatisplus.visualizer.feedback;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import org.jetbrains.annotations.NotNull;

/**
 * 帮助菜单反馈入口（T-ErrReport-05，原需求池计划 §八 Phase 2 落地）：
 * 打开手动形态报告对话框（无异常预填），提交直达需求池；对话框底部提供
 * 带 source/ideVersion/pluginVersion 参数的浏览器兜底链接。
 * 文案走 plugin.xml bundle 约定键 action.&lt;id&gt;.text/.description。
 */
public final class FeedbackAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        FeedbackReporting.openManualDialog(e.getProject());
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(true);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
