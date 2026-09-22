package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db;

import com.intellij.database.model.DasTable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * N6：已定位的引用表实例持有者（表名小写 → {@link DasTable}）。
 *
 * <p><b>为什么需要它</b>：平台两参重载只给表名、不给 {@code DasTable}，而 {@code DbDiagramDataModel.calculateEdges}
 * 建边时以 {@code nodeMap.get(asDbElement(fk.getRefTable()))} 查引用表节点——{@code asDbElement} 对已是
 * {@code DbTable} 的对象走 {@code ObjectUtils.tryCast} <b>原样返回</b>，而 {@code nodeMap} 的键正是
 * {@code addElement} 传进去的那个实例（{@code addElementInner} 字节码实证
 * {@code myTablesAddedByUser.put(name, (DbTable) element)}，{@code calculateElementToNodeMap}
 * 直接以之为键）。所以虚拟外键必须交出<b>图里那个实例</b>，否则边会在这一步被静默丢掉——
 * 登记得再对也画不出线。
 *
 * <p><b>为什么与 {@link JoinFkRegistry} 分家</b>：注册表是「纯 JVM 零平台依赖」的会话数据，
 * {@code JoinFkRegistryTest} 在<b>无 database jar</b> 的 {@code test} 任务下运行。把 {@code DasTable}
 * 放进 {@code Entry} 会让注册表类带上平台类型引用，污染那条类加载边界（同类坑见
 * {@code pitfall-java-port-db-plugin-classloading}）。
 *
 * <p><b>使用纪律</b>：只允许从 database-optional.xml 加载链上的代码
 * （{@link NativeDatabaseDiagramSupport} / {@link JoinVirtualFkProvider}）触碰本对象；
 * <b>plain {@code test} 任务的代码一律不要引用它</b>，也不要把它写进 {@code JoinFkRegistryTest}
 * 的清理里——本仓的 {@code test} 分片没有 database jar。
 *
 * <p>生命周期与注册表一致：{@code registerJoinForeignKeys} 登记前成对清空，{@code clearRegisteredFk}
 * 成对回收。
 *
 * <p><b>「登记数 == 清理数」的守卫口径（T-PerfSweep6 T-PS6-08 / 性能报告发现 18）</b>：本对象不设自动
 * 计数守卫（{@code clear()} 返回 void），清理面靠「显式语义点回收」穷尽，共三处：
 * <ol>
 *  <li>建图前 {@code NativeDatabaseDiagramSupport.registerJoinForeignKeys} 的
 *      {@code clearJoinFkState}——新图恰好只留自己的 FK；</li>
 *  <li>面板卸载 JOIN 图 {@code SqlPreviewPanel.unloadJoinDiagram} →
 *      {@code NativeDatabaseDiagramSupport.clearRegisteredFk}；</li>
 *  <li>面板 {@code SqlPreviewPanel.dispose} → {@code NativeDatabaseDiagramSupport.clearRegisteredFk}。</li>
 * </ol>
 *
 * <p>残留后果不是「登记有、实例缺」（那条会走 provider 的 WARN 并跳过该边），而是<b>跨图残留</b>：
 * 新图的三参回问拿到旧图的 {@code DasTable} 实例，边被挂到别的图的对象上（静默画错线）。
 * 本轮盘点结论：常规宿主下不存在「建图后既不卸载也不 dispose」的路径（图要么随面板 dispose 收口，
 * 要么在下一次建图前被 {@code clearJoinFkState} 清空）。<b>日后新增第四种宿主形态时须回来登记</b>，
 * 并确认它有回收点——否则该形态的残留无兜底。
 */
public final class JoinFkRefTables {

    public static final JoinFkRefTables INSTANCE = new JoinFkRefTables();

    private JoinFkRefTables() {
    }

    private static final Map<String, DasTable> MAP = new ConcurrentHashMap<>();

    /** 登记表名 → 实例（表名小写）。 */
    public static void put(String tableName, DasTable table) {
        String name = tableName.trim().toLowerCase();
        if (name.isEmpty()) {
            return;
        }
        MAP.put(name, table);
    }

    /** 取已定位实例；未登记返回 null。 */
    public static DasTable get(String tableName) {
        return MAP.get(tableName.trim().toLowerCase());
    }

    /** 回收全部实例引用（防跨图残留；与 {@link JoinFkRegistry#clear} 成对调用）。 */
    public static void clear() {
        MAP.clear();
    }
}
