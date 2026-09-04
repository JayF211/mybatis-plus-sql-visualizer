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

    /** 回收全部登记（换图/关闭时调用，防止跨图污染）。 */
    public static void clear() {
        MAP.clear();
    }

    /** 仅供测试。 */
    public static void clearForTest() {
        clear();
    }
}
