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
 * 数据来自 [JoinFkRegistry]（ON 条件语义，纯 JVM）与 [JoinFkRefTables]（已定位的引用表实例）。
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
        // N6：**不要**在此对 refTable == null 早退。该入参为 null 表示平台的「两参重载」调用
        // （ModelRelationManager.getForeignKeys(Project, DasTable) 恒定传 null），契约是
        // 「不做引用表过滤、返回该表全部外键」——而 DbDiagramDataModel.calculateEdges 建边走的正是这条链。
        // 分支语义收在 JoinFkRegistry.selectForCall（纯函数，test 任务可断言），此处只做实例翻译。
        val entries = JoinFkRegistry.selectForCall(table.name, refTable?.name)
        val fks = entries.mapNotNull { entry ->
            // 三参回调自带实例（用它，它就是调用方给的那个对象）；两参回调只能回查登记时定位到的实例。
            val ref = refTable ?: JoinFkRefTables.get(entry.refTable)
            if (ref == null) {
                LOG.warn(
                    "JoinVirtualFk: ref table '${entry.refTable}' 未定位到实例，跳过该边" +
                        "（child=${entry.childTable}）——登记有而实例缺 = JoinFkRefTables 未成对填充或被提前清理",
                )
                null
            } else {
                JoinVirtualFk(table, ref, entry)
            }
        }
        // T-Embed-10.5 / N6 探针：EP 是否被建边链触达 + 登记数 + 实际成边数。
        // `(all refs)` = 两参重载（建边链），有具体表名 = 三参（getExportedKeys 的成对回问）。
        // 三者必须同现：此前日志打在 null 守卫之后，建边路径一次不留痕，日志里只有成对回问的
        // hits=1，看起来一片健康而画布没有线——排障时先看这一行是否出现。
        //
        // T-PerfSweep6 T-PS6-07（性能报告发现 7）：N6 后本回调**每表**一行 INFO，建图时刷出
        // O(表数) 行 idea.log。现改为「**本次建图首行** INFO + 其余 DEBUG」：首行出现即证明 EP
        // 被建边链触达，诊断价值（「有没有线」的二分判据）完整保留，量级降到每图一行。
        // 复位信号来自每次建图——[NativeDatabaseDiagramSupport.registerJoinForeignKeys] 调
        // [JoinVirtualFkProbe.reset]。
        val line = "JoinVirtualFkProvider: ${table.name} -> ${refTable?.name ?: "(all refs)"}, " +
            "entries=${entries.size}, edges=${fks.size}"
        if (JoinVirtualFkProbe.claimFirstLine()) {
            LOG.info(line)
        } else {
            LOG.debug(line)
        }
        return JBIterable.of(*fks.toTypedArray())
    }

    companion object {
        const val PROVIDER_ID = "SqlVisualizerJoin"

        private val LOG = com.intellij.openapi.diagnostic.Logger
            .getInstance(JoinVirtualFkProvider::class.java)
    }
}

/**
 * T-PerfSweep6 T-PS6-07：建边日志探针的「本次建图首行」闸门（**纯 JVM，零平台类型引用**）。
 *
 * **为什么单独成顶层对象**：[JoinVirtualFkProvider] 实现 database 插件的
 * `ModelRelationManager.ModelRelationProvider`，**无 database jar 的 plain `test` 分片加载不了它**；
 * 而本闸门的语义（每次建图恰一行 INFO）恰恰是可以在无平台环境下断言的那部分。Kotlin 顶层对象
 * 编译成独立类文件，测试只加载本对象即可，不触碰 provider 的平台接口。
 *
 * **语义**：每次建图前 [reset] 复位 → 该次建图第一条 `getForeignKeys` 回调 [claimFirstLine] 返回 true
 * （调用方打 INFO），其余一律 false（打 DEBUG）。
 *
 * **为什么保留首行 INFO**：`(all refs)` 形态出现一次即证明 EP 被建边链触达——这是「画布没有线」
 * 类问题的二分判据（EP 没被触达 vs 被触达但边被静默丢掉），比逐表 INFO 的边际信息量高得多。
 */
internal object JoinVirtualFkProbe {

    private val firstLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 本次建图的首行是否由本调用取得；true = 调用方打 INFO，false = 打 DEBUG。 */
    fun claimFirstLine(): Boolean = firstLogged.compareAndSet(false, true)

    /** 每次建图前复位（[NativeDatabaseDiagramSupport.registerJoinForeignKeys] 调用）。 */
    fun reset() {
        firstLogged.set(false)
    }

    /** 测试缝：首行是否已被占用。 */
    internal fun firstLineClaimedForTest(): Boolean = firstLogged.get()
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
