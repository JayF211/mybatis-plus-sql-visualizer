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
