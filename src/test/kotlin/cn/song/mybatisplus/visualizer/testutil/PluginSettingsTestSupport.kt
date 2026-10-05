package cn.song.mybatisplus.visualizer.testutil

import cn.song.mybatisplus.visualizer.config.PluginSettings

/**
 * T-TestPerf15（O-97 治理）：应用级 [PluginSettings] 全局态的测试隔离工具。
 *
 * ## 为什么需要（O-97 证据链，2026-10-05 复现实验钉死）
 *
 * `PluginSettings` 是 application 级单例，同一分片 JVM 内所有测试类共享。历史上多类在
 * setUp 改渲染链消费键后不恢复（如 SqlPreviewPanelBindStateTest 设 `formatSql = false`
 * 而 tearDown 无恢复），固定分区下泄漏源与受害类的相对顺序恰好无害；分区变化
 * （auto-handicap 补偿/画像刷新/新类进片）随机翻转先后即概率性炸——T13 R0 冷轮
 * OfficialApiTest 18/39、T14 R2 补偿轮 20 例，形态均为「期望多行格式化 SQL、实际单行」。
 * 2026-10-05 双类组合（BindState → OfficialApi 同 JVM 顺序执行）修复前 18/39 稳定复现。
 *
 * 两层语义：
 *  - [resetToFactoryDefaults]：写/读全局设置态的测试类在 setUp 调用——每类从出厂默认
 *    出发，截断「前面类泄漏 + finally 恢复进入时值」的污染穿透链（恢复进入时值会传递
 *    污染，恢复出厂默认才截断）；
 *  - [snapshot]/[restore]：写入者类 setUp 快照、tearDown 回放（照
 *    ProjectPluginSettingsConfigurableTest 既有范式）。
 */
object PluginSettingsTestSupport {

    /** 全局 state 恢复出厂默认（loadState 走 PersistentStateComponent 汇入点，语义同设置导入）。 */
    fun resetToFactoryDefaults() {
        PluginSettings.getInstance().loadState(PluginSettings.State())
    }

    /** 当前全局 state 快照（copy，隔离后续修改）。 */
    fun snapshot(): PluginSettings.State = PluginSettings.getInstance().state.copy()

    /** 回放快照。 */
    fun restore(saved: PluginSettings.State) {
        PluginSettings.getInstance().loadState(saved)
    }
}
