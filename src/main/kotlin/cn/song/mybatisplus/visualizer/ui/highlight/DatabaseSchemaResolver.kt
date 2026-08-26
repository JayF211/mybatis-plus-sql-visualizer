package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 数据源 schema 解析（M35，方案 B：反射隔离，Community 兼容）。
 *
 * 当 IntelliJ IDEA Ultimate / DataGrip 加载了 `com.intellij.database` 插件时，
 * 从已配置的数据源读取指定表的真实字段注释（DB COMMENT），供补全合并——
 * 数据源注释比实体类 `@TableName/@ApiModelProperty` 注释更权威（贴近线上 DDL）。
 *
 * 全反射访问，不 import `com.intellij.database` 任何类——Community 运行时该插件不存在
 * 也能编译/加载（与 [ExecuteSqlSupport] 同一隔离策略）。插件可用性经 `Class.forName`
 * 探测标记类判定，不可用时空转（补全不展示数据源注释，仍用实体注释）。
 *
 * 数据结构：`DataSourceManager.getManagers(project)` → `getDataSources()` → 每个
 * `DbDataSource.getDasObject().getModel().traverser()` 遍历 `DasTable`（按名匹配），
 * `DasTable.getDasChildren(DasColumn)` → 每列 `DasObject.getName()/getComment()`。
 */
object DatabaseSchemaResolver {

    private val LOG = Logger.getInstance(DatabaseSchemaResolver::class.java)

    /** database 插件标记类（探测用，M17 同 ExecuteSqlSupport）。 */
    private const val MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager"

    /** DasTable / DasColumn / DasObject 的二进制名（ObjectKind 判类型用）。 */
    private const val DAS_TABLE = "com.intellij.database.model.DasTable"
    private const val DAS_COLUMN = "com.intellij.database.model.DasColumn"
    private const val DAS_OBJECT = "com.intellij.database.model.DasObject"

    /** 列名 → 数据源注释（表名匹配不到返回空 Map，调用方回落实体注释）。 */
    fun columnComments(project: Project, tableName: String): Map<String, String> {
        if (tableName.isBlank()) return emptyMap()
        if (!isDatabaseAvailable()) return emptyMap()
        val dataSources = dataSources(project) ?: return emptyMap()
        if (dataSources.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (ds in dataSources) {
            collectTableComments(ds, tableName, result)
            if (result.isNotEmpty()) break // 首个命中的数据源为准
        }
        return result
    }

    /** database 插件是否可用（Ultimate/DataGrip）。 */
    private fun isDatabaseAvailable(): Boolean = runCatching {
        Class.forName(MANAGER_CLASS)
        true
    }.getOrDefault(false)

    /** 获取所有数据源（反射 `DataSourceManager.getManagers(project)` → `getDataSources()`）。 */
    private fun dataSources(project: Project): List<Any>? = runCatching {
        val managerClass = Class.forName(MANAGER_CLASS)
        val managersMethod = managerClass.getMethod("getManagers", Project::class.java)
        val managers = managersMethod.invoke(null, project) as? List<*> ?: return@runCatching emptyList()
        val dsList = mutableListOf<Any>()
        for (m in managers) {
            val getDataSources = m?.javaClass?.getMethod("getDataSources") ?: continue
            val list = getDataSources.invoke(m) as? List<*> ?: continue
            dsList.addAll(list.mapNotNull { it })
        }
        dsList
    }.getOrNull()

    /**
     * 从一个数据源收集匹配表的列注释。
     * 反射遍历：dasObject.getModel().traverser()（JBIterable）筛 DasTable → 匹配表名 →
     * dasTable.getDasChildren(DasColumn) → 每列 getName()/getComment()。
     */
    private fun collectTableComments(ds: Any, tableName: String, out: MutableMap<String, String>) {
        runCatching {
            val dasObjClass = Class.forName(DAS_OBJECT)
            val dasTableClass = Class.forName(DAS_TABLE)
            val dasColumnClass = Class.forName(DAS_COLUMN)
            // ds 是 DbDataSource（interface），转 dasObject 拿 DasModel
            val dasObject = ds.javaClass.getMethod("getDasObject").invoke(ds) ?: return
            val getModel = dasObject.javaClass.getMethod("getModel")
            val model = getModel.invoke(dasObject) ?: return
            val traverser = model.javaClass.getMethod("traverser").invoke(model) ?: return
            // JBIterable.forEach
            val forEach = traverser.javaClass.methods.firstOrNull { it.name == "forEach" && it.parameterCount == 1 }
                ?: return
            // 迭代回调：lambda 接受 Object（DasTable），取出列
            val consumer = java.util.function.Consumer<Any> { obj ->
                if (dasTableClass.isInstance(obj)) {
                    val tName = dasObjClass.getMethod("getName").invoke(obj) as? String ?: return@Consumer
                    if (!tName.equals(tableName, ignoreCase = true)) return@Consumer
                    collectTable(obj, dasObjClass, dasColumnClass, dasTableClass, out)
                }
            }
            try {
                forEach.invoke(traverser, consumer)
            } catch (_: Throwable) {
                // forEach 签名不符时尝试多态回调；失败静默（补全不因 schema 中断）
            }
        }.onFailure { e ->
            LOG.debug("DatabaseSchemaResolver.collectTableComments failed: ${e.message}")
        }
    }

    /** 收集某 DasTable 的列名+注释。 */
    private fun collectTable(
        table: Any,
        dasObjClass: Class<*>,
        dasColumnClass: Class<*>,
        dasTableClass: Class<*>,
        out: MutableMap<String, String>,
    ) {
        runCatching {
            val getChildren = dasObjClass.getMethod("getDasChildren", Class::class.java)  // 实际是 ObjectKind
            // 直接反射 getDbChildren(DasColumn.class, ObjectKind.TABLE) 更稳，但 ObjectKind 枚举值需反射。
            // 简化：用 getDasChildren 无参重载不可靠——改走 dasTableClass 的列迭代。
            // 实际 DasObject 的列是 getDasChildren(ObjectKind.COLUMN)，ObjectKind 是枚举。
            val objectKindClass = Class.forName("com.intellij.database.model.ObjectKind")
            val columnKind = objectKindClass.enumConstants?.firstOrNull { it.toString() == "COLUMN" }
                ?: return
            val getDasChildren = dasObjClass.getMethod("getDasChildren", objectKindClass)
            val columns = getDasChildren.invoke(table, columnKind) as? Iterable<*> ?: return
            for (col in columns) {
                if (!dasColumnClass.isInstance(col)) continue
                val cName = dasObjClass.getMethod("getName").invoke(col) as? String ?: continue
                val comment = dasObjClass.getMethod("getComment").invoke(col) as? String
                if (cName.isNotBlank() && !comment.isNullOrBlank()) {
                    out[cName] = comment
                }
            }
        }.onFailure { e ->
            LOG.debug("DatabaseSchemaResolver.collectTable columns failed: ${e.message}")
        }
    }
}