package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T-Embed-04：JOIN 虚拟外键注册表（进程内会话数据，纯 JVM 零平台依赖）。
 *
 * <p>打开 Database 原生「显示图」前，把 SQL 的 ON 等值条件登记到这里；
 * {@link JoinVirtualFkProvider}（`com.intellij.database.modelRelationProvider` EP 实现）
 * 在 Database 图建边时按表对回查，把 ON 条件以原生虚拟外键形式画成连线。
 *
 * <p>仅进程内存驻留（不写任何持久化配置，杜绝污染用户 schema），由
 * 调用方在图关闭/换图时经 {@link #clear} 回收。
 */
public final class JoinFkRegistry {

    public static final JoinFkRegistry INSTANCE = new JoinFkRegistry();

    private JoinFkRegistry() {
    }

    /**
     * 一条虚拟外键：childTable（JOIN 表）的 columns 引用 refTable（主表）的 refColumns。
     * 表名一律小写存储。
     */
    public static final class Entry {
        private final String childTable;
        private final String refTable;
        private final List<String> columns;
        private final List<String> refColumns;

        public Entry(String childTable, String refTable, List<String> columns, List<String> refColumns) {
            this.childTable = childTable;
            this.refTable = refTable;
            this.columns = columns;
            this.refColumns = refColumns;
        }

        public String getChildTable() {
            return childTable;
        }

        public String getRefTable() {
            return refTable;
        }

        public List<String> getColumns() {
            return columns;
        }

        public List<String> getRefColumns() {
            return refColumns;
        }
    }

    private static final Map<String, List<Entry>> MAP = new ConcurrentHashMap<>();

    /** 键 = 两表名小写排序拼接（方向无关匹配；EP 回调方向可能与登记方向相反）。 */
    private static String key(String tableA, String tableB) {
        String[] pair = {tableA.trim().toLowerCase(), tableB.trim().toLowerCase()};
        Arrays.sort(pair);
        return pair[0] + "|" + pair[1];
    }

    /** 登记一条 JOIN 等值配对（child=JOIN 表，ref=主表）。 */
    public static void put(String childTable, String refTable, String childColumn, String refColumn) {
        String child = childTable.trim().toLowerCase();
        String ref = refTable.trim().toLowerCase();
        String childCol = childColumn.trim().toLowerCase();
        String refCol = refColumn.trim().toLowerCase();
        if (child.isEmpty() || ref.isEmpty() || childCol.isEmpty() || refCol.isEmpty() || child.equals(ref)) {
            return;
        }
        List<Entry> list = MAP.computeIfAbsent(key(child, ref), k -> new ArrayList<>());
        Entry entry = new Entry(child, ref, List.of(childCol), List.of(refCol));
        synchronized (list) {
            if (!containsEntry(list, entry)) {
                list.add(entry);
            }
        }
    }

    private static boolean containsEntry(List<Entry> list, Entry entry) {
        for (Entry e : list) {
            if (e.childTable.equals(entry.childTable) && e.refTable.equals(entry.refTable)
                    && e.columns.equals(entry.columns) && e.refColumns.equals(entry.refColumns)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查询两表间的全部等值配对（方向无关）；无则空列表。
     * 返回的 Entry 保证 columns 属于 childTable（请求方向与登记方向相反时自动翻转）。
     */
    public static List<Entry> find(String childTable, String refTable) {
        List<Entry> stored = MAP.get(key(childTable, refTable));
        if (stored == null) {
            return List.of();
        }
        String child = childTable.trim().toLowerCase();
        String ref = refTable.trim().toLowerCase();
        List<Entry> snapshot;
        synchronized (stored) {
            snapshot = new ArrayList<>(stored);
        }
        List<Entry> out = new ArrayList<>();
        for (Entry entry : snapshot) {
            if (entry.childTable.equals(child)) {
                out.add(entry);
            } else {
                out.add(new Entry(entry.refTable, entry.childTable, entry.refColumns, entry.columns));
            }
        }
        return out;
    }

    /**
     * 该表作为 child（FK 起点）的全部登记，<b>方向不翻转</b>；大小写不敏感，无则空列表。
     *
     * <p>与 {@link #find} 的分工：{@link #find} 答的是「这两张表之间有哪些配对」（方向无关、必要时翻转），
     * 本方法答的是「这张表引用了谁」（单向）——正是平台两参重载要问的问题，见 {@link #selectForCall}。
     */
    public static List<Entry> entriesForChild(String childTable) {
        String child = childTable.trim().toLowerCase();
        if (child.isEmpty()) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        for (List<Entry> stored : MAP.values()) {
            synchronized (stored) {
                for (Entry entry : stored) {
                    if (entry.childTable.equals(child)) {
                        out.add(entry);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 一次 EP 回调应返回的关系集合（方向已按调用形态规范化）。N6 的核心修正点。
     *
     * <p>{@code refTableName} 为 null = 平台的<b>两参重载</b>调用
     * （{@code ModelRelationManager.getForeignKeys(Project, DasTable)}）。其字节码恒定把 refTable 传成
     * {@code aconst_null}（243/253/262 同构），契约是「<b>不做引用表过滤、返回该表全部外键</b>」——
     * 平台内置的 {@code ModelRelationManager$DasForeignKeys} 就是这个三目：
     * {@code refTable == null ? 全部 : 按 refTable 过滤}。而 {@code DbDiagramDataModel.calculateEdges}
     * （唯一的建边入口）在 {@code VIRTUAL_FOREIGN_KEYS} 分类启用后走的正是这条两参链，所以这里若把
     * null 当成「无关系」返回空，原生图就只剩节点、连线恒空。
     *
     * <p>非 null = 三参回调（{@code getExportedKeys}/{@code computeExported} 的成对回问），按表对查询
     * {@link #find}。
     *
     * <p>语义收在此处（纯函数、零平台依赖）而非 provider 里，是为了让这条契约在无 database jar 的
     * {@code test} 任务下可被直接断言——否则守卫看不见它要防的那个分支。
     */
    public static List<Entry> selectForCall(String childTable, String refTableName) {
        return (refTableName == null) ? entriesForChild(childTable) : find(childTable, refTableName);
    }

    /** 回收全部登记（换图/关闭时调用，防止跨图污染）。 */
    public static void clear() {
        MAP.clear();
    }

    /** 仅供测试。 */
    public static void clearForTest() {
        clear();
    }
}
