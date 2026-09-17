package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.Project

/**
 * M56 T-M56-09：consoleFilterProvider EP 双轨——EP 为主捕获通道（平台在控制台创建时自动
 * 装配本 provider 返回的 Filter，无轮询发现面），反射轮询降为兜底。
 *
 * 「EP 首行标志」生效判定：EP Filter 首次真正处理控制台行时置 [SqlCaptureGate.epActive]
 * （会话内粘滞）——桥的常驻轮询据此跳过 addMessageFilter 挂载（EP 已覆盖全部控制台，
 * 双挂载会双路解析）；定时器本身保留（控制台摘除检测等职责）。真机门禁：runIde 观测
 * EP 生效且轮询停转——留人工（本验收项计划标注「真机门禁先行」）。
 *
 * 每个 Filter 实例持有独立 [BridgeLineContext]（平台每控制台创建一次 provider 调用，
 * 实例即 per-console 状态）；重放守卫降级为直通（EP Filter 不持有 console 引用，取不到
 * 编辑器文档）——rehighlight 重放由去重口径兜底（同 SQL 同指纹）。
 *
 * 捕捉态机：走与桥同款 capturing 守卫（[SqlLogManager.capturing]），非捕捉态零处理。
 */
class SqlConsoleBridgeFilterProvider : com.intellij.execution.filters.ConsoleFilterProvider {

    override fun getDefaultFilters(project: Project): Array<Filter> {
        val manager = project.getService(SqlLogManager::class.java)
        return arrayOf(EpLineFilter(project, manager))
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SqlConsoleBridgeFilterProvider::class.java)

        /**
         * T-PS4-02：EP 通道 timePrefix 缓存（对齐桥侧 timePrefixCache 形态）——逐行不再读
         * PluginSettings；provider 级共享（单一设置项，跨控制台共享正确），懒初始化 +
         * [refreshEpTimePrefixCache]（SqlLogManager 设置变更处理器调用，与桥侧刷新同点）。
         */
        @Volatile
        private var epTimePrefixCache: String? = null

        private fun readTimePrefix(): String? =
            PluginSettings.getInstance().state.logTimePrefix.takeIf { it.isNotBlank() }

        fun refreshEpTimePrefixCache() {
            epTimePrefixCache = readTimePrefix()
        }
    }

    /** EP 行过滤器：实例即 per-console 解析状态。 */
    private class EpLineFilter(
        private val project: Project,
        private val manager: SqlLogManager,
    ) : Filter {

        private val ctx = BridgeLineContext(
            myBatisParser = MyBatisLogParser(
                sqlPrefix = parserConfig().sqlPrefix,
                paramPrefix = parserConfig().paramPrefix,
                mixedPrefix = parserConfig().mixedPrefix,
                maxParseLength = parserConfig().maxParseLength,
            ),
            hibernateParser = HibernateLogParser(
                sqlPrefix = parserConfig().hibernateSqlPrefix,
                paramPrefix = parserConfig().hibernateParamPrefix,
                mixedPrefix = parserConfig().mixedPrefix,
                maxParseLength = parserConfig().maxParseLength,
            ),
            jdbcParser = JdbcTemplateLogParser(
                enabled = parserConfig().jdbcEnabled,
                maxParseLength = parserConfig().maxParseLength,
            ),
            configProvider = { parserConfig() },
            hibernateEnabled = parserConfig().hibernateEnabled,
        )

        private fun parserConfig(): ParserConfig {
            val state = PluginSettings.getInstance().state
            return ParserConfig(
                sqlPrefix = state.logSqlPrefix,
                paramPrefix = state.logParamPrefix,
                mixedPrefix = state.logMixedPrefix,
                maxParseLength = state.logMaxParseLength,
                hibernateSqlPrefix = state.logHibernateSqlPrefix,
                hibernateParamPrefix = state.logHibernateParamPrefix,
                hibernateEnabled = state.logHibernateEnabled,
                jdbcEnabled = state.logJdbcEnabled,
            )
        }

        private var firstLineSeen = false

        override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
            // 捕捉态守卫（与桥同款状态机）：非捕捉态零处理
            if (!manager.capturing) return null
            if (!firstLineSeen) {
                firstLineSeen = true
                // 「EP 首行标志」：EP 通道实证生效 → 桥轮询跳过 filter 挂载（会话内粘滞）
                SqlCaptureGate.epActive = true
            }
            // T-PS4-02：timePrefix 走缓存（懒初始化 + 设置变更刷新），逐行不再读 PluginSettings
            val timePrefix = epTimePrefixCache ?: readTimePrefix()?.also { epTimePrefixCache = it }
            processConsoleLine(line, ctx, timePrefix, {}, onErrorText = ::bindConsoleError)
                .forEach { manager.entries.addEntry(it) }
            return null // 不做高亮/链接——纯捕获通道
        }

        /**
         * T-M55-02：错误反向绑定与桥同款（丢弃留 debug 痕）。EP 通道无重放守卫，
         * rehighlight 重放场景靠同文本幂等绑定兜底（文本相同不再替换实例）。
         */
        private fun bindConsoleError(errorText: String) {
            when (val result = manager.entries.bindErrorToRecent(errorText)) {
                SqlLogEntryBuffer.ErrorBindResult.BOUND -> {}
                else -> LOG.debug("EpLineFilter: 错误块绑定丢弃（$result）：${errorText.lineSequence().firstOrNull()?.take(120)}")
            }
        }
    }

}
