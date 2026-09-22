package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db

import com.intellij.database.model.DasTable
import java.util.concurrent.ConcurrentHashMap

/**
 * N6：已定位的引用表实例持有者（表名小写 → [DasTable]）。
 *
 * **为什么需要它**：平台两参重载只给表名、不给 `DasTable`，而 `DbDiagramDataModel.calculateEdges`
 * 建边时以 `nodeMap.get(asDbElement(fk.getRefTable()))` 查引用表节点——`asDbElement` 对已是 `DbTable`
 * 的对象走 `ObjectUtils.tryCast` **原样返回**，而 `nodeMap` 的键正是 `addElement` 传进去的那个实例
 * （`addElementInner` 字节码实证 `myTablesAddedByUser.put(name, (DbTable) element)`，
 * `calculateElementToNodeMap` 直接以之为键）。所以虚拟外键必须交出**图里那个实例**，否则边会在这一步
 * 被静默丢掉——登记得再对也画不出线。
 *
 * **为什么与 [JoinFkRegistry] 分家**：注册表是「纯 JVM 零平台依赖」的会话数据，`JoinFkRegistryTest`
 * 在**无 database jar** 的 `test` 任务下运行。把 `DasTable` 放进 `Entry` 会让注册表类带上平台类型引用，
 * 污染那条类加载边界（同类坑见 `pitfall-java-port-db-plugin-classloading`）。
 *
 * **使用纪律**：只允许从 database-optional.xml 加载链上的代码（[NativeDatabaseDiagramSupport] /
 * [JoinVirtualFkProvider]）触碰本对象；**plain `test` 任务的代码一律不要引用它**，也不要把它写进
 * `JoinFkRegistryTest` 的清理里——本仓的 `test` 分片没有 database jar。
 *
 * 生命周期与注册表一致：`registerJoinForeignKeys` 登记前成对清空，`clearRegisteredFk` 成对回收。
 *
 * **「登记数 == 清理数」的守卫口径（T-PerfSweep6 T-PS6-08 / 性能报告发现 18）**：本对象不设自动
 * 计数守卫（`clear()` 返回 void），清理面靠「显式语义点回收」穷尽，共三处：
 *  1. 建图前 `NativeDatabaseDiagramSupport.registerJoinForeignKeys` 的 `clearJoinFkState`——
 *     新图恰好只留自己的 FK；
 *  2. 面板卸载 JOIN 图 `SqlPreviewPanel.unloadJoinDiagram` → `NativeDatabaseDiagramSupport.clearRegisteredFk`；
 *  3. 面板 `SqlPreviewPanel.dispose` → `NativeDatabaseDiagramSupport.clearRegisteredFk`。
 *
 * 残留后果不是「登记有、实例缺」（那条会走 provider 的 WARN 并跳过该边），而是**跨图残留**：
 * 新图的三参回问拿到旧图的 `DasTable` 实例，边被挂到别的图的对象上（静默画错线）。
 * 本轮盘点结论：常规宿主下不存在「建图后既不卸载也不 dispose」的路径（图要么随面板 dispose 收口，
 * 要么在下一次建图前被 `clearJoinFkState` 清空）。**日后新增第四种宿主形态时须回来登记**，
 * 并确认它有回收点——否则该形态的残留无兜底。
 */
object JoinFkRefTables {

    private val map = ConcurrentHashMap<String, DasTable>()

    /** 登记表名 → 实例（表名小写）。 */
    fun put(tableName: String, table: DasTable) {
        val name = tableName.trim().lowercase()
        if (name.isEmpty()) return
        map[name] = table
    }

    /** 取已定位实例；未登记返回 null。 */
    fun get(tableName: String): DasTable? = map[tableName.trim().lowercase()]

    /** 回收全部实例引用（防跨图残留；与 [JoinFkRegistry.clear] 成对调用）。 */
    fun clear() = map.clear()
}
