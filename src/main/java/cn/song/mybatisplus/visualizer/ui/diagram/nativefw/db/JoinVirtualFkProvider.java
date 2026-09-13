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
 * <p>数据来自 {@link JoinFkRegistry}（进程内存，换图即清，不写持久化配置）。
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
        if (refTable == null) {
            return JBIterable.empty();
        }
        List<JoinFkRegistry.Entry> entries = JoinFkRegistry.find(table.getName(), refTable.getName());
        // T-Embed-10.5 探针：EP 是否被原生建边触达 + registry 命中数（连线缺失排障）
        LOG.info("JoinVirtualFkProvider: " + table.getName() + " -> " + refTable.getName()
                + ", hits=" + entries.size());
        if (entries.isEmpty()) {
            return JBIterable.empty();
        }
        List<DasForeignKey> fks = new ArrayList<>();
        for (JoinFkRegistry.Entry entry : entries) {
            fks.add(new JoinVirtualFk(table, refTable, entry));
        }
        return JBIterable.of(fks.toArray(new DasForeignKey[0]));
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
