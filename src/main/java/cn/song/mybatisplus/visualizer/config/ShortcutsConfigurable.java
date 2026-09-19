// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/config/SectionConfigurable.kt 移植（ShortcutsConfigurable 子类）
package cn.song.mybatisplus.visualizer.config;

import java.util.Set;

/** T-ShortcutSettings：快捷键统一管理子页面（5 个动作快捷键内嵌录制编辑） */
public class ShortcutsConfigurable extends SectionConfigurable {
    public ShortcutsConfigurable() {
        super(Set.of(PluginSettingsPanel.SECTION_SHORTCUTS), "settings.section.shortcuts");
    }
}
