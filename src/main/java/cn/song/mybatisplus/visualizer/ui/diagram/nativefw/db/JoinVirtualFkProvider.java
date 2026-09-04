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
        return new ColumnNamesMultiRef(childTable, childColumns);
    }

    @Override
    public MultiRef<DasTypedObject> getRefColumns() {
        return new ColumnNamesMultiRef(refTableVal, refColumns);
    }
}

/**
 * 名称驱动的 {@link MultiRef}：names() 供 Database 图边标签（getFkRelationshipLabel 只读 names，
 * 字节码实证）使用；resolveObjects()/resolve() 按名称从所属表列中懒解析。
 */
class ColumnNamesMultiRef implements MultiRef<DasTypedObject> {

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
        if (table != null) {
            for (DasTypedObject column : DasUtil.getColumns(table)) {
                if (column.getName().toLowerCase().equals(name.toLowerCase())) {
                    return column;
                }
            }
        }
        throw new IllegalStateException("Column not found in virtual FK resolution: "
                + (table != null ? table.getName() : "?") + "." + name);
    }
}
