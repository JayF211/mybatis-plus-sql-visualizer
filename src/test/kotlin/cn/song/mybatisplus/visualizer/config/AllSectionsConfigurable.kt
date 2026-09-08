package cn.song.mybatisplus.visualizer.config

/**
 * 测试专用：承载全部设置分区的 SectionConfigurable 子类
 * （20260907-1617：PluginSettingsConfigurable 死类删除后的面板工厂——
 * 生产路径五个子类各承载一个分区，测试需要全分区面板时用此类）。
 */
class AllSectionsConfigurable : SectionConfigurable(
    PluginSettingsPanel.SECTION_ALL,
    "plugin.name"
)
