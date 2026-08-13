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
 * 到 `MyBatis-Plus SQL Visualizer` 配置目录。修改后由 [PluginSettingsConfigurable.apply] 写回即生效。
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

        /** 代码左侧 gutter 预览 SQL 图标开关（M10；性能/视觉兜底，关闭后不再生成图标）。 */
        var showGutterIcon: Boolean = true,

        /** 插件界面语言（M14）：默认 `zh`（简体中文）；`en`=English；`AUTO`=跟随 IDE。 */
        var language: String = DEFAULT_LANGUAGE,

        /**
         * 逻辑删除注入（T-M15-07）：实体含 @TableLogic 字段时，查询/删除自动追加 `AND deleted = 0`
         * （对齐 MyBatis-Plus 全局 `logic-delete-field` 配置）；false 时保持纯 wrapper 语义。
         */
        var injectLogicDelete: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        const val DEFAULT_DIALECT = "MySQL"

        /** 默认界面语言（M14，用户要求默认中文）。 */
        const val DEFAULT_LANGUAGE = "zh"

        /**
         * 获取全局配置实例。
         */
        @JvmStatic
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
