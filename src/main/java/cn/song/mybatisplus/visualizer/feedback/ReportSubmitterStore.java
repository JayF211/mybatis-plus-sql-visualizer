package cn.song.mybatisplus.visualizer.feedback;

import cn.song.mybatisplus.visualizer.config.PluginSettings;

/**
 * 报告提交者昵称/邮箱的持久化（T-ErrReport-03）：对话框 OK 时写入 State。
 * 直接原地改 state 属性不触发 XmlSerializer 持久化（T-TenantLine 根因），须
 * copy + loadState 替换引用；测试环境（service 未注册）静默跳过。
 */
public final class ReportSubmitterStore {

    /** Kotlin internal object 单例等价。 */
    public static final ReportSubmitterStore INSTANCE = new ReportSubmitterStore();

    private ReportSubmitterStore() {
    }

    public static String loadName() {
        try {
            return PluginSettings.getInstance().getState().getReportSubmitterName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String loadEmail() {
        try {
            return PluginSettings.getInstance().getState().getReportSubmitterEmail();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void save(String name, String email) {
        try {
            PluginSettings settings = PluginSettings.getInstance();
            PluginSettings.State current = settings.getState();
            if (!current.getReportSubmitterName().equals(name) || !current.getReportSubmitterEmail().equals(email)) {
                PluginSettings.State copy = current.copy();
                copy.setReportSubmitterName(name);
                copy.setReportSubmitterEmail(email);
                settings.loadState(copy);
            }
        } catch (Throwable ignored) {
            // 测试环境（service 未注册）静默跳过
        }
    }
}
