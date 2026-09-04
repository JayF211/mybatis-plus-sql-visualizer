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

    /** 回收全部登记（换图/关闭时调用，防止跨图污染）。 */
    fun clear() = map.clear()

    /** 仅供测试。 */
    fun clearForTest() = clear()
}
