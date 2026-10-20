package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.execute.DataSourceSettings
import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/** 用户实际选择并通过执行闸后被动学习的「模块稳定键 → 数据源 UUID」。不保存 URL。 */
@State(name = "MpSqlVisualizerModuleDataSourceBindings", storages = [Storage("mp-sql-visualizer-datasource.xml")])
@Service(Service.Level.PROJECT)
class ModuleDataSourceBindingStore : PersistentStateComponent<ModuleDataSourceBindingStore.State> {
    data class State(var bindings: MutableMap<String, String> = linkedMapOf())

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    fun remember(moduleKey: String, dataSourceId: String) {
        if (moduleKey.isBlank() || dataSourceId.isBlank()) return
        state.bindings[moduleKey] = dataSourceId
    }

    fun forget(moduleKey: String) { state.bindings.remove(moduleKey) }
    fun clear() { state.bindings.clear() }
    fun dataSourceId(moduleKey: String): String? = state.bindings[moduleKey]?.takeIf { it.isNotBlank() }

    fun learnedCandidate(project: Project, moduleKey: String): DialectDecisionEngine.Candidate? {
        val id = dataSourceId(moduleKey) ?: return null
        val lookup = dataSourceByIdForTest
        val ds = (if (lookup != null) lookup(project, id) else DataSourceSettings.getDataSourceById(project, id)) ?: run {
            forget(moduleKey)
            return null
        }
        return DialectDecisionEngine.Candidate(id, ExecutionDialectGate.fromDataSource(ds).dialectName)
    }

    companion object {
        /** Headless 夹具只替换 UUID→对象查找；生产恒走 DatabaseTools 存储。 */
        @Volatile internal var dataSourceByIdForTest: ((Project, String) -> Any?)? = null

        fun getInstance(project: Project): ModuleDataSourceBindingStore = project.getService(ModuleDataSourceBindingStore::class.java)
    }
}
