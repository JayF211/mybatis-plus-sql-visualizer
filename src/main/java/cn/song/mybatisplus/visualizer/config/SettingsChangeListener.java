package cn.song.mybatisplus.visualizer.config;

/**
 * 设置变更监听器（订阅方据此即时刷新 UI）。
 */
@FunctionalInterface
public interface SettingsChangeListener {
    void settingsChanged();
}
