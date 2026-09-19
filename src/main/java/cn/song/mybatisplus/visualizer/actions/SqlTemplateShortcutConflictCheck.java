// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SqlTemplateShortcutConflictCheck.kt 移植
package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.KeyStroke;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 快捷模板快捷键冲突检测（T-PreviewUx-Pack #3，吸收 DBX sqlShortcutActions 冲突检测思路）：
 *
 * <p>plugin.xml 静态注册三动作默认键位（Alt+Shift+E/C/T，同族 ConvertToSql=Alt+Shift+Q /
 * ConvertSqlToJava=Alt+Shift+J）；项目启动后<b>应用级单次</b>检测当前 activeKeymap 中各默认键位
 * 是否已被其他 action 占用（平台侧以 KeymapManager 实际 API 为准，PluginSettingsPanel
 * conflictActionName 同款遍历范式）。冲突即为本插件动作<b>追加</b>备选键位（Ctrl+Alt+Shift+同字母，
 * addShortcut 只加不改，不摘除任何既有绑定）并弹通知提示；改绑失败（当前 scheme 不可写等
 * 环境差异）降级为仅提示冲突，引导 Settings &gt; Keymap 手动调整。右键菜单入口不受键位冲突影响。
 */
public class SqlTemplateShortcutConflictCheck implements ProjectActivity {

    /** Kotlin suspend execute 的 Java 侧等价形态（无实际挂起点，直接同步执行）。 */
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (alreadyChecked) return Unit.INSTANCE;
        alreadyChecked = true;
        Keymap keymap;
        try {
            keymap = KeymapManager.getInstance().getActiveKeymap();
        } catch (Throwable t) {
            return Unit.INSTANCE;
        }
        if (keymap == null) return Unit.INSTANCE;
        // T-ShortcutSettings：仅检测「当前键位 == plugin.xml 默认值」的动作（用户已通过设置页自定义的跳过，不覆盖）
        List<String> actionIds = List.of(
                "cn.song.mybatisplus.visualizer.ExplainSelectedTable",
                "cn.song.mybatisplus.visualizer.CountSelectedTable",
                "cn.song.mybatisplus.visualizer.SampleSelectedTable");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < actionIds.size(); i++) {
            String actionId = actionIds.get(i);
            KeyStroke defaultKeystroke = DEFAULT_KEYSTROKES.get(i);
            KeyStroke current = null;
            try {
                for (Shortcut sc : keymap.getShortcuts(actionId)) {
                    if (sc instanceof KeyboardShortcut ks) {
                        current = ks.getFirstKeyStroke();
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            // 当前键位 == 默认值 → 用户未配置，走既有冲突检测；否则跳过
            String line = current != null && current.equals(defaultKeystroke)
                    ? resolveConflictLine(keymap, actionId)
                    : null;
            if (line != null) lines.add(line);
        }
        if (lines.isEmpty()) return Unit.INSTANCE;
        if (project.isDisposed()) return Unit.INSTANCE;
        NotificationGroupManager.getInstance()
                .getNotificationGroup(SelectedTableSqlTemplateAction.NOTIFICATION_GROUP_ID)
                .createNotification(
                        MpSqlVisualizerBundle.message("sqlTemplate.conflict.title"),
                        String.join("\n", lines),
                        NotificationType.WARNING)
                .notify(project);
        return Unit.INSTANCE;
    }

    /** 应用级单次守卫（StartupUpdateCheck alreadyChecked 同款静态）。 */
    private static volatile boolean alreadyChecked = false;

    /** 三动作在 plugin.xml 注册的默认键位（Alt+Shift+E/C/T），用于判定用户是否已自定义。 */
    private static final List<KeyStroke> DEFAULT_KEYSTROKES = List.of(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_E, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_T, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));

    /**
     * 单动作冲突处理（纯逻辑 + 受控副作用）：默认键位无冲突返回 null；有冲突则尝试追加
     * 备选键位并返回提示行文案（改绑成败在文案中如实区分）。
     */
    public static @Nullable String resolveConflictLine(Keymap keymap, String actionId) {
        KeyboardShortcut current = null;
        try {
            for (Shortcut sc : keymap.getShortcuts(actionId)) {
                if (sc instanceof KeyboardShortcut ks) {
                    current = ks;
                    break;
                }
            }
        } catch (Throwable t) {
            current = null;
        }
        if (current == null) return null;
        String owner = conflictOwner(keymap, actionId, current);
        if (owner == null) return null;
        KeyboardShortcut fallback = fallbackOf(current);
        boolean rebound = false;
        if (fallback != null && conflictOwner(keymap, actionId, fallback) == null) {
            try {
                keymap.addShortcut(actionId, fallback);
                rebound = true;
            } catch (Throwable ignored) {
            }
        }
        String displayName;
        try {
            AnAction action = ActionManager.getInstance().getAction(actionId);
            displayName = action != null ? action.getTemplatePresentation().getText() : null;
        } catch (Throwable t) {
            displayName = null;
        }
        if (displayName == null) displayName = actionId;
        String keyText = current.getFirstKeyStroke() != null ? current.getFirstKeyStroke().toString() : "";
        if (rebound && fallback != null) {
            return MpSqlVisualizerBundle.message(
                    "sqlTemplate.conflict.item.rebound",
                    displayName, keyText, owner, fallback.getFirstKeyStroke().toString());
        } else {
            return MpSqlVisualizerBundle.message(
                    "sqlTemplate.conflict.item.manual",
                    displayName, keyText, owner);
        }
    }

    /** keymap 中 shortcut 的占用者 actionId（排除自身）；无冲突返回 null（conflictActionName 同款遍历）。 */
    public static @Nullable String conflictOwner(Keymap keymap, String actionId, Shortcut shortcut) {
        try {
            for (String other : keymap.getActionIds()) {
                if (other.equals(actionId)) continue;
                for (Shortcut s : keymap.getShortcuts(other)) {
                    if (s.equals(shortcut)) return other;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 备选键位推导：在当前修饰符上叠加 Ctrl+Alt（Alt+Shift+E → Ctrl+Alt+Shift+E）。 */
    public static @Nullable KeyboardShortcut fallbackOf(KeyboardShortcut shortcut) {
        KeyStroke ks = shortcut.getFirstKeyStroke();
        if (ks == null) return null;
        int mods = ks.getModifiers() | InputEvent.CTRL_MASK | InputEvent.ALT_MASK | InputEvent.SHIFT_MASK;
        return new KeyboardShortcut(
                KeyStroke.getKeyStroke(ks.getKeyCode(), mods),
                shortcut.getSecondKeyStroke());
    }
}
