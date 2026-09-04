package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db

import com.intellij.database.model.DasForeignKey
import com.intellij.database.model.DasTable
import com.intellij.database.model.DasTypedObject
import com.intellij.database.model.ModelRelationManager
import com.intellij.database.model.MultiRef
import com.intellij.database.util.DasUtil
import com.intellij.openapi.project.Project
import com.intellij.util.containers.JBIterable

/**
 * T-Embed-04：JOIN 虚拟外键 EP 实现。
 *
 * 注册于 `com.intellij.database.modelRelationProvider`（database-optional.xml，仅
 * DatabaseTools 插件存在时加载）——Database 原生图 `DbDiagramDataModel` 建边时经
 * `ModelRelationManager.getForeignKeys` 聚合本 Provider，把 SQL JOIN 的 ON 等值条件
 * 以**原生虚拟外键**画成连线（边样式/标签/图例全原生）。
 *
 * 数据来自 [JoinFkRegistry]（进程内存，换图即清，不写持久化配置）。
 *
 * 隔离边界：本文件 import com.intellij.database.*，仅当 database-optional.xml 被加载
 * （DatabaseTools 存在）时平台才会实例化；Community 下不会触碰。
 */
class JoinVirtualFkProvider : ModelRelationManager.ModelRelationProvider {

    override fun getId(): String = JoinVirtualFkProvider.PROVIDER_ID

    // 签名按接口字节码可空性（refTable 无 @NotNull）
    override fun getForeignKeys(
        project: Project?,
        table: DasTable,
        refTable: DasTable?,
    ): JBIterable<DasForeignKey> {
        if (refTable == null) return JBIterable.empty()
        val entries = JoinFkRegistry.find(table.name, refTable.name)
        if (entries.isEmpty()) return JBIterable.empty()
        val fks = entries.map { JoinVirtualFk(table, refTable, it) }
        return JBIterable.of(*fks.toTypedArray())
    }

    companion object {
        const val PROVIDER_ID = "SqlVisualizerJoin"
    }
}

/**
 * 一条 JOIN 虚拟外键：JOIN 表（child）的列引用主表（ref）的列。
 * [ModelRelationManager.ExtraRelation] 基类实现 getTable/getRefTable/规则等；
 * 这里补 providerId、边名与列引用（名称驱动，无 DDL 实体）。
 */
class JoinVirtualFk(
    private val childTable: DasTable,
    private val refTableVal: DasTable,
    entry: JoinFkRegistry.Entry,
) : ModelRelationManager.ExtraRelation(childTable, refTableVal) {

    private val childColumns: List<String> = entry.columns
    private val refColumns: List<String> = entry.refColumns

    override fun getProviderId(): String = JoinVirtualFkProvider.PROVIDER_ID

    override fun getName(): String =
        "SQLV_JOIN_${childTable.name}_${childColumns.joinToString("_")}"

    override fun getColumnsRef(): MultiRef<DasTypedObject> = ColumnNamesMultiRef(childTable, childColumns)

    override fun getRefColumns(): MultiRef<DasTypedObject> = ColumnNamesMultiRef(refTableVal, refColumns)
}

/**
 * 名称驱动的 [MultiRef]：names() 供 Database 图边标签（getFkRelationshipLabel 只读 names，
 * 字节码实证）使用；resolveObjects()/resolve() 按名称从所属表列中懒解析。
 */
class ColumnNamesMultiRef(
    private val table: DasTable?,
    private val names: List<String>,
) : MultiRef<DasTypedObject> {

    override fun iterate(): MultiRef.It<DasTypedObject> {
        val source = names.iterator()
        return object : MultiRef.It<DasTypedObject> {
            private var last: String? = null
            override fun hasNext(): Boolean = source.hasNext()
            override fun next(): String {
                last = source.next()
                return last.orEmpty()
            }

            override fun resolve(): DasTypedObject = resolveByName(last.orEmpty())
            override fun remove() = throw UnsupportedOperationException("virtual FK ref is immutable")
        }
    }

    override fun names(): Iterable<String> = names

    override fun resolveObjects(): Iterable<DasTypedObject> =
        table?.let { t ->
            DasUtil.getColumns(t).filter { it.name.lowercase() in lowered }.toList()
        } ?: emptyList()

    override fun size(): Int = names.size

    private val lowered: Set<String> = names.map { it.lowercase() }.toSet()

    private fun resolveByName(name: String): DasTypedObject =
        table?.let { t ->
            DasUtil.getColumns(t).firstOrNull { it.name.lowercase() == name.lowercase() }
        } ?: error("Column not found in virtual FK resolution: ${table?.name ?: "?"}.$name")
}
