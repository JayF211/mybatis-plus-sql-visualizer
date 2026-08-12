package cn.song.mybatisplus.visualizer.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件配置状态（T-M5-01，F-16 配置项持久化）。
 *
 * application-level service（全局配置，跨项目共享），经 XmlSerializer 持久化
 * 到 `MP SQL Visualizer` 配置目录。修改后由 [PluginSettingsConfigurable.apply] 写回即生效。
 */
@State(name = "MpSqlVisualizerSettings", storages = [Storage("mp-sql-visualizer.xml")])
@Service(Service.Level.APP)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    /**
     * 可持久化状态（XmlSerializer 序列化；默认值即出厂配置）。
     */
    @Suppress("DataClassShouldBeImmutable")
    data class State(
        /** 数据库方言（对应 [cn.song.mybatisplus.visualizer.sql.DialectRegistry] 的名称）。 */
        var dialect: String = DEFAULT_DIALECT,

        /** 是否替换参数（F-16 配置项；true 时 psi 层变量追踪内联，false 保留 `?`）。 */
        var replaceParams: Boolean = true,

        /** 是否格式化输出（true=多行缩进，false=单行）。 */
        var formatSql: Boolean = true,

        /** 显示方式：DIALOG / TOOL_WINDOW / BOTH。 */
        var displayMode: String = DISPLAY_BOTH,

        /** 自动预览（悬停）开关（F-06）。 */
        var autoPreview: Boolean = true,

        /** 悬停延迟（毫秒，F-16 配置项）。 */
        var hoverDelay: Int = DEFAULT_HOVER_DELAY,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        const val DEFAULT_DIALECT = "MySQL"
        const val DISPLAY_DIALOG = "DIALOG"
        const val DISPLAY_TOOL_WINDOW = "TOOL_WINDOW"
        const val DISPLAY_BOTH = "BOTH"
        const val DEFAULT_HOVER_DELAY = 500

        /**
         * 获取全局配置实例。
         */
        @JvmStatic
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
