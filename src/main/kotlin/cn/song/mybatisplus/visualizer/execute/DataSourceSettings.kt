package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * 默认数据源管理（M36）：持久化用户选择的默认数据源，封装数据源枚举。
 *
 * 反射隔离：所有 `com.intellij.database.*` 引用经 [Class.forName] 加载，
 * Community 下永不触碰。持久化经 [PersistentStateComponent]（项目级）存储数据源 ID。
 *
 * LocalDataSource 方法：getUniqueId()=ID（AbstractDataSource），getName()=显示名。
 */
@State(
    name = "MpSqlVisualizerDataSource",
    storages = [Storage("mp-sql-visualizer-datasource.xml")],
)
@Service(Service.Level.PROJECT)
class DataSourceSettings : PersistentStateComponent<DataSourceSettings.State> {

    data class State(
        var defaultDataSourceId: String = "",
        var defaultDataSourceName: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        /** DataSourceStorage 类名（反射入口）。 */
        private const val DS_STORAGE_CLASS = "com.intellij.database.dataSource.DataSourceStorage"

        /** LocalDataSource 类名（反射用）。 */
        private const val LOCAL_DS_CLASS = "com.intellij.database.dataSource.LocalDataSource"

        fun getInstance(project: Project): DataSourceSettings =
            project.getService(DataSourceSettings::class.java)

        fun getDefaultId(project: Project): String? {
            val id = getInstance(project).state.defaultDataSourceId
            return if (id.isBlank()) null else id
        }

        fun getDefaultName(project: Project): String? {
            val name = getInstance(project).state.defaultDataSourceName
            return if (name.isBlank()) null else name
        }

        fun setDefault(project: Project, id: String, name: String) {
            getInstance(project).state = State(id, name)
        }

        fun clearDefault(project: Project) {
            getInstance(project).state = State()
        }

        fun hasDefault(project: Project): Boolean =
            getInstance(project).state.defaultDataSourceId.isNotBlank()

        /**
         * 获取当前项目所有已配置的数据源列表（反射调用 DataSourceStorage）。
         */
        fun getAllDataSources(project: Project): List<Any> {
            return runCatching {
                val storageClass = Class.forName(DS_STORAGE_CLASS)
                val getStorageMethod = storageClass.getMethod("getStorage", Project::class.java)
                val storage = getStorageMethod.invoke(null, project) ?: return emptyList()
                val getDataSourcesMethod = storageClass.getMethod("getDataSources")
                val dataSources = getDataSourcesMethod.invoke(storage) as? List<*> ?: return emptyList()
                dataSources.filterNotNull()
            }.getOrDefault(emptyList())
        }

        /**
         * 根据 ID 查找 LocalDataSource 对象（反射调用）。
         */
        fun getDataSourceById(project: Project, id: String): Any? {
            return runCatching {
                val storageClass = Class.forName(DS_STORAGE_CLASS)
                val getStorageMethod = storageClass.getMethod("getStorage", Project::class.java)
                val storage = getStorageMethod.invoke(null, project) ?: return null
                val getByIdMethod = storageClass.getMethod("getDataSourceById", String::class.java)
                getByIdMethod.invoke(storage, id)
            }.getOrNull()
        }

        /**
         * 获取默认数据源的 LocalDataSource 对象（反射）。
         */
        fun getDefaultDataSource(project: Project): Any? {
            val id = getDefaultId(project) ?: return null
            return getDataSourceById(project, id)
        }

        /**
         * 反射获取 LocalDataSource 的显示名称（getName()）。
         */
        fun getName(ds: Any): String {
            return runCatching {
                val getNameMethod = Class.forName(LOCAL_DS_CLASS).getMethod("getName")
                getNameMethod.invoke(ds) as? String ?: "Unknown"
            }.getOrDefault("Unknown")
        }

        /**
         * 反射获取 LocalDataSource 的唯一 ID（getUniqueId()）。
         */
        fun getUniqueId(ds: Any): String? {
            return runCatching {
                val getUniqueIdMethod = Class.forName(LOCAL_DS_CLASS).getMethod("getUniqueId")
                getUniqueIdMethod.invoke(ds) as? String
            }.getOrNull()
        }

        /**
         * 反射获取 LocalDataSource 的图标。
         */
        fun getIcon(ds: Any): javax.swing.Icon? {
            return runCatching {
                val getIconMethod = Class.forName(LOCAL_DS_CLASS).getMethod("getIcon")
                getIconMethod.invoke(ds) as? javax.swing.Icon
            }.getOrNull()
        }
    }
}
