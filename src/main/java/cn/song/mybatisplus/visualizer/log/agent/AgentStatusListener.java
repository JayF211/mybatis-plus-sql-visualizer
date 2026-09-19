// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt 移植（AgentStatusListener 顶层接口）
package cn.song.mybatisplus.visualizer.log.agent;

/**
 * T-SettingsTruth P2：增强捕获状态行广播（app 级 Topic，设置页面板订阅后刷新状态行）。
 */
public interface AgentStatusListener {
    void onStatusChanged();
}
