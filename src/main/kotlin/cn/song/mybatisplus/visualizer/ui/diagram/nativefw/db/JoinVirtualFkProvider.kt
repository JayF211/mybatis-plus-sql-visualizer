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
        // T-Embed-10.5 探针：EP 是否被原生建边触达 + registry 命中数（连线缺失排障）
        LOG.info(
            "JoinVirtualFkProvider: ${table.name} -> ${refTable.name}, hits=${entries.size}",
        )
        if (entries.isEmpty()) return JBIterable.empty()
        val fks = entries.map { JoinVirtualFk(table, refTable, it) }
        return JBIterable.of(*fks.toTypedArray())
    }

    companion object {
        const val PROVIDER_ID = "SqlVisualizerJoin"

        private val LOG = com.intellij.openapi.diagnostic.Logger
            .getInstance(JoinVirtualFkProvider::class.java)
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

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(JoinVirtualFk::class.java)

    override fun getProviderId(): String = JoinVirtualFkProvider.PROVIDER_ID

    override fun getName(): String =
        "SQLV_JOIN_${childTable.name}_${childColumns.joinToString("_")}"

    override fun getColumnsRef(): MultiRef<DasTypedObject> = safeMultiRef(childTable, childColumns)

    override fun getRefColumns(): MultiRef<DasTypedObject> = safeMultiRef(refTableVal, refColumns)

    /**
     * T-Perf-20：构造时校验列可解析性——JOIN ON 引用列在表中缺失时过滤掉（全缺 = 空 MultiRef，
     * 原生建边拿到空引用自然跳过该边）+ warn，不再让 IllegalStateException 从平台建边回调
     * 上浮为 IDE internal error 气泡。
     */
    private fun safeMultiRef(table: DasTable?, names: List<String>): MultiRef<DasTypedObject> {
        if (table == null) return ColumnNamesMultiRef(null, names)
        val available = DasUtil.getColumns(table).toList().mapNotNull { it.name?.lowercase() }.toSet()
        val resolvable = names.filter { it.lowercase() in available }
        if (resolvable.size < names.size) {
            LOG.warn(
                "JoinVirtualFk: columns missing in ${table.name}, skip=${names.filter { it.lowercase() !in available}}",
            )
        }
        return ColumnNamesMultiRef(table, resolvable)
    }
}

/**
 * 名称驱动的 [MultiRef]：names() 供 Database 图边标签（getFkRelationshipLabel 只读 names，
 * 字节码实证）使用；resolveObjects()/resolve() 按名称从所属表列中懒解析。
 */
class ColumnNamesMultiRef(
    private val table: DasTable?,
    private val names: List<String>,
) : MultiRef<DasTypedObject> {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ColumnNamesMultiRef::class.java)

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
        } ?: run {
            // T-Perf-20：列缺失兜底（构造期已过滤，此处仅防御建边与解析之间 schema 变化）
            // ——不再 error() 抛 IllegalStateException 上浮 IDE internal error 气泡；
            // 退化为首个可用列（边标签语义降级但建图不断链）。表零列的绝对退化场景仍抛。
            LOG.warn("JoinVirtualFk: column '$name' not found in ${table?.name ?: "?"}, falling back to first column")
            table?.let { t -> DasUtil.getColumns(t).toList().firstOrNull() }
                ?: error("No columns available in virtual FK resolution: ${table?.name ?: "?"}.$name")
        }
}
