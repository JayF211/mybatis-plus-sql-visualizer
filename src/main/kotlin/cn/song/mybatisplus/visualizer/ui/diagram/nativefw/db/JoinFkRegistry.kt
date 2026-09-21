package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db

import java.util.concurrent.ConcurrentHashMap

/**
 * T-Embed-04：JOIN 虚拟外键注册表（进程内会话数据，纯 JVM 零平台依赖）。
 *
 * 打开 Database 原生「显示图」前，把 SQL 的 ON 等值条件登记到这里；
 * [JoinVirtualFkProvider]（`com.intellij.database.modelRelationProvider` EP 实现）
 * 在 Database 图建边时按表对回查，把 ON 条件以原生虚拟外键形式画成连线。
 *
 * 仅进程内存驻留（不写任何持久化配置，杜绝污染用户 schema），由
 * 调用方在图关闭/换图时经 [clear] 回收。
 */
object JoinFkRegistry {

    /**
     * 一条虚拟外键：[childTable]（JOIN 表）的 [columns] 引用 [refTable]（主表）的 [refColumns]。
     * 表名一律小写存储。
     */
    data class Entry(
        val childTable: String,
        val refTable: String,
        val columns: List<String>,
        val refColumns: List<String>,
    )

    private val map = ConcurrentHashMap<String, MutableList<Entry>>()

    /** 键 = 两表名小写排序拼接（方向无关匹配；EP 回调方向可能与登记方向相反）。 */
    private fun key(tableA: String, tableB: String): String =
        listOf(tableA.trim().lowercase(), tableB.trim().lowercase()).sorted().joinToString("|")

    /** 登记一条 JOIN 等值配对（child=JOIN 表，ref=主表）。 */
    fun put(childTable: String, refTable: String, childColumn: String, refColumn: String) {
        val child = childTable.trim().lowercase()
        val ref = refTable.trim().lowercase()
        val childCol = childColumn.trim().lowercase()
        val refCol = refColumn.trim().lowercase()
        if (child.isEmpty() || ref.isEmpty() || childCol.isEmpty() || refCol.isEmpty() || child == ref) return
        val list = map.computeIfAbsent(key(child, ref)) { mutableListOf() }
        val entry = Entry(child, ref, listOf(childCol), listOf(refCol))
        synchronized(list) { if (entry !in list) list.add(entry) }
    }

    /**
     * 查询两表间的全部等值配对（方向无关）；无则空列表。
     * 返回的 Entry 保证 columns 属于 [childTable]（请求方向与登记方向相反时自动翻转）。
     */
    fun find(childTable: String, refTable: String): List<Entry> {
        val stored = map[key(childTable, refTable)] ?: return emptyList()
        val child = childTable.trim().lowercase()
        val ref = refTable.trim().lowercase()
        return synchronized(stored) { stored.toList() }.map { entry ->
            if (entry.childTable == child) entry
            else Entry(entry.refTable, entry.childTable, entry.refColumns, entry.columns)
        }
    }

    /**
     * 该表作为 child（FK 起点）的全部登记，**方向不翻转**；大小写不敏感，无则空列表。
     *
     * 与 [find] 的分工：[find] 答的是「这两张表之间有哪些配对」（方向无关、必要时翻转），
     * 本方法答的是「这张表引用了谁」（单向）——正是平台两参重载要问的问题，见 [selectForCall]。
     */
    fun entriesForChild(childTable: String): List<Entry> {
        val child = childTable.trim().lowercase()
        if (child.isEmpty()) return emptyList()
        val out = mutableListOf<Entry>()
        for (stored in map.values) {
            synchronized(stored) {
                for (entry in stored) if (entry.childTable == child) out.add(entry)
            }
        }
        return out
    }

    /**
     * 一次 EP 回调应返回的关系集合（方向已按调用形态规范化）。N6 的核心修正点。
     *
     * [refTableName] 为 null = 平台的**两参重载**调用
     * （`ModelRelationManager.getForeignKeys(Project, DasTable)`）。其字节码恒定把 refTable 传成
     * `aconst_null`（243/253/262 同构），契约是「**不做引用表过滤、返回该表全部外键**」——平台内置的
     * `ModelRelationManager$DasForeignKeys` 就是这个三目：`refTable == null ? 全部 : 按 refTable 过滤`。
     * 而 `DbDiagramDataModel.calculateEdges`（唯一的建边入口）在 `VIRTUAL_FOREIGN_KEYS` 分类启用后走的
     * 正是这条两参链，所以这里若把 null 当成「无关系」返回空，原生图就只剩节点、连线恒空。
     *
     * 非 null = 三参回调（`getExportedKeys`/`computeExported` 的成对回问），按表对查询 [find]。
     *
     * 语义收在此处（纯函数、零平台依赖）而非 provider 里，是为了让这条契约在无 database jar 的
     * `test` 任务下可被直接断言——否则守卫看不见它要防的那个分支。
     */
    fun selectForCall(childTable: String, refTableName: String?): List<Entry> =
        if (refTableName == null) entriesForChild(childTable) else find(childTable, refTableName)

    /** 回收全部登记（换图/关闭时调用，防止跨图污染）。 */
    fun clear() = map.clear()

    /** 仅供测试。 */
    fun clearForTest() = clear()
}
