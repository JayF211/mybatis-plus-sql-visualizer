package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.application.ReadAction
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
 * 遍历路径：`DataSourceManager.getManagers(project)` → `getDataSources()` → 每个
 * `DbDataSource.getDasObject()` → `DasModel.traverser().toList()` 筛 `DasTable` →
 * 按名匹配 → `DasTable.getDasChildren(ObjectKind.COLUMN)` → 每列 `getName()/getComment()`。
 */
object DatabaseSchemaResolver {

    private val LOG = Logger.getInstance(DatabaseSchemaResolver::class.java)

    /** database 插件标记类（探测用，M17 同 ExecuteSqlSupport）。 */
    private const val MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager"

    /** DasTable / DasColumn / DasObject 的二进制名（ObjectKind 判类型用）。 */
    private const val DAS_TABLE = "com.intellij.database.model.DasTable"
    private const val DAS_COLUMN = "com.intellij.database.model.DasColumn"
    private const val DAS_OBJECT = "com.intellij.database.model.DasObject"
    private const val OBJECT_KIND = "com.intellij.database.model.ObjectKind"

    /** 列名 → 数据源注释（表名匹配不到返回空 Map，调用方回落实体注释）。 */
    fun columnComments(project: Project, tableName: String): Map<String, String> =
        columnMeta(project, tableName).mapValues { it.value.comment ?: "" }

    /**
     * 列名 → 列元数据（注释 + DB 真实类型串，T-Embed-02）。
     *
     * 类型串 = `DasTypedObject.getDataType().getSpecification()`（如 `int(11)` / `varchar(64)`，
     * javap 实证 DataType.getSpecification 即 Database「显示图」右对齐类型的渲染来源）。
     * 表名匹配不到返回空 Map，调用方回落实体推断值。
     */
    fun columnMeta(project: Project, tableName: String): Map<String, ColumnMeta> {
        if (tableName.isBlank()) return emptyMap()
        return if (!isDatabaseAvailable()) emptyMap() else ReadAction.compute<Map<String, ColumnMeta>, Throwable> {
            tryCompute(project, tableName)
        }
    }

    /** 数据源侧列元数据（均为 null 时表示该维度缺失，调用方逐维度回落）。 */
    data class ColumnMeta(val comment: String?, val typeSpec: String?)

    private fun tryCompute(project: Project, tableName: String): Map<String, ColumnMeta> {
        val dataSources = dataSources(project) ?: return emptyMap()
        if (dataSources.isEmpty()) return emptyMap()
        val dasTableClass = Class.forName(DAS_TABLE)
        for (ds in dataSources) {
            val found = collectFromDataSource(ds, tableName, dasTableClass)
            if (found.isNotEmpty()) return found
        }
        return emptyMap()
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

    /** 从单个数据源收集匹配表的列元数据（遍历 DasModel.traverser().toList()）。 */
    private fun collectFromDataSource(ds: Any, tableName: String, dasTableClass: Class<*>): Map<String, ColumnMeta> {
        val out = linkedMapOf<String, ColumnMeta>()
        runCatching {
            val dasObjClass = Class.forName(DAS_OBJECT)
            val dasColumnClass = Class.forName(DAS_COLUMN)
            val dasObject = ds.javaClass.getMethod("getDasObject").invoke(ds) ?: return emptyMap()
            val model = dasObject.javaClass.getMethod("getModel").invoke(dasObject) ?: return emptyMap()
            val traverser = model.javaClass.getMethod("traverser").invoke(model) ?: return emptyMap()
            // JBIterable.toList() — 反射遍历所有 DasObject
            val toList = traverser.javaClass.getMethod("toList")
            val elements = toList.invoke(traverser) as? List<*> ?: return emptyMap()
            val objectKindClass = Class.forName(OBJECT_KIND)
            val columnKind = objectKindClass.enumConstants?.firstOrNull { (it as Enum<*>).name == "COLUMN" }
                ?: return emptyMap()
            val getNameM = dasObjClass.getMethod("getName")
            val getCommentM = dasObjClass.getMethod("getComment")
            val getChildrenM = dasObjClass.getMethod("getDasChildren", objectKindClass)

            for (element in elements) {
                if (element == null || !dasTableClass.isInstance(element)) continue
                val tName = getNameM.invoke(element) as? String ?: continue
                if (!tName.equals(tableName, ignoreCase = true)) continue
                val columns = getChildrenM.invoke(element, columnKind) as? Iterable<*> ?: continue
                for (col in columns) {
                    if (col == null || !dasColumnClass.isInstance(col)) continue
                    val cName = getNameM.invoke(col) as? String ?: continue
                    if (cName.isBlank()) continue
                    val comment = getCommentM.invoke(col) as? String
                    // DasTypedObject.getDataType().getSpecification()（反射；列无类型信息时为 null）
                    val typeSpec = runCatching {
                        val dataType = col.javaClass.getMethod("getDataType").invoke(col)
                        dataType?.javaClass?.getMethod("getSpecification")?.invoke(dataType) as? String
                    }.getOrNull()
                    if (!comment.isNullOrBlank() || !typeSpec.isNullOrBlank()) {
                        out[cName] = ColumnMeta(comment = comment?.takeIf { it.isNotBlank() }, typeSpec = typeSpec?.takeIf { it.isNotBlank() })
                    }
                }
            }
        }.onFailure { e ->
            LOG.debug("DatabaseSchemaResolver.collectFromDataSource failed: ${e.message}")
        }
        return out
    }
}