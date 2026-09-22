package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db;

import com.intellij.database.model.DasForeignKey;
import com.intellij.database.model.DasTable;
import com.intellij.database.model.DasTypedObject;
import com.intellij.database.model.ModelRelationManager;
import com.intellij.database.model.MultiRef;
import com.intellij.database.util.DasUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.containers.JBIterable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * T-Embed-04：JOIN 虚拟外键 EP 实现。
 *
 * <p>注册于 `com.intellij.database.modelRelationProvider`（database-optional.xml，仅
 * DatabaseTools 插件存在时加载）——Database 原生图 `DbDiagramDataModel` 建边时经
 * `ModelRelationManager.getForeignKeys` 聚合本 Provider，把 SQL JOIN 的 ON 等值条件
 * 以<b>原生虚拟外键</b>画成连线（边样式/标签/图例全原生）。
 *
 * <p>数据来自 {@link JoinFkRegistry}（ON 条件语义，纯 JVM）与 {@link JoinFkRefTables}（已定位的引用表实例）。
 *
 * <p>隔离边界：本文件 import com.intellij.database.*，仅当 database-optional.xml 被加载
 * （DatabaseTools 存在）时平台才会实例化；Community 下不会触碰。
 */
public class JoinVirtualFkProvider implements ModelRelationManager.ModelRelationProvider {

    public static final String PROVIDER_ID = "SqlVisualizerJoin";

    private static final Logger LOG = Logger.getInstance(JoinVirtualFkProvider.class);

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    // 签名按接口字节码可空性（refTable 无 @NotNull）
    @Override
    public JBIterable<DasForeignKey> getForeignKeys(Project project, DasTable table, DasTable refTable) {
        // N6：**不要**在此对 refTable == null 早退。该入参为 null 表示平台的「两参重载」调用
        // （ModelRelationManager.getForeignKeys(Project, DasTable) 恒定传 null），契约是
        // 「不做引用表过滤、返回该表全部外键」——而 DbDiagramDataModel.calculateEdges 建边走的正是这条链。
        // 分支语义收在 JoinFkRegistry.selectForCall（纯函数，test 任务可断言），此处只做实例翻译。
        List<JoinFkRegistry.Entry> entries = JoinFkRegistry.selectForCall(
                table.getName(), (refTable != null) ? refTable.getName() : null);
        List<DasForeignKey> fks = new ArrayList<>();
        for (JoinFkRegistry.Entry entry : entries) {
            // 三参回调自带实例（用它，它就是调用方给的那个对象）；两参回调只能回查登记时定位到的实例。
            DasTable ref = (refTable != null) ? refTable : JoinFkRefTables.get(entry.getRefTable());
            if (ref == null) {
                LOG.warn("JoinVirtualFk: ref table '" + entry.getRefTable() + "' 未定位到实例，跳过该边"
                        + "（child=" + entry.getChildTable()
                        + "）——登记有而实例缺 = JoinFkRefTables 未成对填充或被提前清理");
            } else {
                fks.add(new JoinVirtualFk(table, ref, entry));
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
        // 复位信号来自每次建图——NativeDatabaseDiagramSupport.registerJoinForeignKeys 调
        // JoinVirtualFkProbe.reset。
        String line = "JoinVirtualFkProvider: " + table.getName() + " -> "
                + ((refTable != null) ? refTable.getName() : "(all refs)")
                + ", entries=" + entries.size() + ", edges=" + fks.size();
        if (JoinVirtualFkProbe.claimFirstLine()) {
            LOG.info(line);
        } else {
            LOG.debug(line);
        }
        return JBIterable.of(fks.toArray(new DasForeignKey[0]));
    }
}

/**
 * T-PerfSweep6 T-PS6-07：建边日志探针的「本次建图首行」闸门（**纯 JVM，零平台类型引用**）。
 *
 * <p><b>为什么单独成顶层对象</b>：{@link JoinVirtualFkProvider} 实现 database 插件的
 * {@code ModelRelationManager.ModelRelationProvider}，<b>无 database jar 的 plain {@code test}
 * 分片加载不了它</b>；而本闸门的语义（每次建图恰一行 INFO）恰恰是可以在无平台环境下断言的那部分。
 * 顶层类型编译成独立类文件，测试只加载本类即可，不触碰 provider 的平台接口。
 *
 * <p><b>语义</b>：每次建图前 {@link #reset} 复位 → 该次建图第一条 {@code getForeignKeys} 回调
 * {@link #claimFirstLine} 返回 true（调用方打 INFO），其余一律 false（打 DEBUG）。
 *
 * <p><b>为什么保留首行 INFO</b>：{@code (all refs)} 形态出现一次即证明 EP 被建边链触达——这是
 * 「画布没有线」类问题的二分判据（EP 没被触达 vs 被触达但边被静默丢掉），比逐表 INFO 的边际信息量高得多。
 */
class JoinVirtualFkProbe {

    private static final java.util.concurrent.atomic.AtomicBoolean FIRST_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 本次建图的首行是否由本调用取得；true = 调用方打 INFO，false = 打 DEBUG。 */
    public static boolean claimFirstLine() {
        return FIRST_LOGGED.compareAndSet(false, true);
    }

    /** 每次建图前复位（NativeDatabaseDiagramSupport.registerJoinForeignKeys 调用）。 */
    public static void reset() {
        FIRST_LOGGED.set(false);
    }

    /** 测试缝：首行是否已被占用。 */
    public static boolean firstLineClaimedForTest() {
        return FIRST_LOGGED.get();
    }
}

/**
 * 一条 JOIN 虚拟外键：JOIN 表（child）的列引用主表（ref）的列。
 * {@link ModelRelationManager.ExtraRelation} 基类实现 getTable/getRefTable/规则等；
 * 这里补 providerId、边名与列引用（名称驱动，无 DDL 实体）。
 */
class JoinVirtualFk extends ModelRelationManager.ExtraRelation {

    private static final Logger LOG = Logger.getInstance(JoinVirtualFk.class);

    private final DasTable childTable;
    private final DasTable refTableVal;
    private final List<String> childColumns;
    private final List<String> refColumns;

    JoinVirtualFk(DasTable childTable, DasTable refTableVal, JoinFkRegistry.Entry entry) {
        super(childTable, refTableVal);
        this.childTable = childTable;
        this.refTableVal = refTableVal;
        this.childColumns = entry.getColumns();
        this.refColumns = entry.getRefColumns();
    }

    @Override
    public String getProviderId() {
        return JoinVirtualFkProvider.PROVIDER_ID;
    }

    @Override
    public String getName() {
        return "SQLV_JOIN_" + childTable.getName() + "_" + String.join("_", childColumns);
    }

    @Override
    public MultiRef<DasTypedObject> getColumnsRef() {
        return safeMultiRef(childTable, childColumns);
    }

    @Override
    public MultiRef<DasTypedObject> getRefColumns() {
        return safeMultiRef(refTableVal, refColumns);
    }

    /**
     * T-Perf-20：构造时校验列可解析性——JOIN ON 引用列在表中缺失时过滤掉（全缺 = 空 MultiRef，
     * 原生建边拿到空引用自然跳过该边）+ warn，不再让 IllegalStateException 从平台建边回调
     * 上浮为 IDE internal error 气泡。
     */
    private MultiRef<DasTypedObject> safeMultiRef(DasTable table, List<String> names) {
        if (table == null) return new ColumnNamesMultiRef(null, names);
        Set<String> available = new HashSet<>();
        for (DasTypedObject column : DasUtil.getColumns(table)) {
            String n = column.getName();
            if (n != null) available.add(n.toLowerCase());
        }
        List<String> resolvable = new ArrayList<>();
        for (String n : names) {
            if (available.contains(n.toLowerCase())) resolvable.add(n);
        }
        if (resolvable.size() < names.size()) {
            List<String> skipped = new ArrayList<>();
            for (String n : names) {
                if (!available.contains(n.toLowerCase())) skipped.add(n);
            }
            LOG.warn("JoinVirtualFk: columns missing in " + table.getName() + ", skip=" + skipped);
        }
        return new ColumnNamesMultiRef(table, resolvable);
    }
}

/**
 * 名称驱动的 {@link MultiRef}：names() 供 Database 图边标签（getFkRelationshipLabel 只读 names，
 * 字节码实证）使用；resolveObjects()/resolve() 按名称从所属表列中懒解析。
 */
class ColumnNamesMultiRef implements MultiRef<DasTypedObject> {

    private static final Logger LOG = Logger.getInstance(ColumnNamesMultiRef.class);

    private final DasTable table;
    private final List<String> names;
    private final Set<String> lowered;

    ColumnNamesMultiRef(DasTable table, List<String> names) {
        this.table = table;
        this.names = names;
        this.lowered = new HashSet<>();
        for (String n : names) {
            lowered.add(n.toLowerCase());
        }
    }

    @Override
    public MultiRef.It<DasTypedObject> iterate() {
        java.util.Iterator<String> source = names.iterator();
        return new MultiRef.It<DasTypedObject>() {
            private String last = null;

            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public String next() {
                last = source.next();
                return last != null ? last : "";
            }

            @Override
            public DasTypedObject resolve() {
                return resolveByName(last != null ? last : "");
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("virtual FK ref is immutable");
            }
        };
    }

    @Override
    public Iterable<String> names() {
        return names;
    }

    @Override
    public Iterable<DasTypedObject> resolveObjects() {
        if (table == null) {
            return List.of();
        }
        List<DasTypedObject> out = new ArrayList<>();
        for (DasTypedObject column : DasUtil.getColumns(table)) {
            if (lowered.contains(column.getName().toLowerCase())) {
                out.add(column);
            }
        }
        return out;
    }

    @Override
    public int size() {
        return names.size();
    }

    private DasTypedObject resolveByName(String name) {
        DasTypedObject found = null;
        if (table != null) {
            for (DasTypedObject column : DasUtil.getColumns(table)) {
                if (column.getName().toLowerCase().equals(name.toLowerCase())) {
                    found = column;
                    break;
                }
            }
        }
        if (found != null) return found;
        // T-Perf-20：列缺失兜底（构造期已过滤，此处仅防御建边与解析之间 schema 变化）
        // ——不再 error() 抛 IllegalStateException 上浮 IDE internal error 气泡；
        // 退化为首个可用列（边标签语义降级但建图不断链）。表零列的绝对退化场景仍抛。
        LOG.warn("JoinVirtualFk: column '" + name + "' not found in "
                + (table != null ? table.getName() : "?") + ", falling back to first column");
        if (table != null) {
            for (DasTypedObject column : DasUtil.getColumns(table)) {
                return column;
            }
        }
        throw new IllegalStateException("No columns available in virtual FK resolution: "
                + (table != null ? table.getName() : "?") + "." + name);
    }
}
