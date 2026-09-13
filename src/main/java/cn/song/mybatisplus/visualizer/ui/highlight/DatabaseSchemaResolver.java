package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源 schema 解析（M35，方案 B：反射隔离，Community 兼容）。
 *
 * <p>当 IntelliJ IDEA Ultimate / DataGrip 加载了 `com.intellij.database` 插件时，
 * 从已配置的数据源读取指定表的真实字段注释（DB COMMENT），供补全合并——
 * 数据源注释比实体类 `@TableName/@ApiModelProperty` 注释更权威（贴近线上 DDL）。
 *
 * <p>全反射访问，不 import `com.intellij.database` 任何类——Community 运行时该插件不存在
 * 也能编译/加载（与 ExecuteSqlSupport 同一隔离策略）。插件可用性经 `Class.forName`
 * 探测标记类判定，不可用时空转（补全不展示数据源注释，仍用实体注释）。
 *
 * <p>遍历路径：`DataSourceManager.getManagers(project)` → `getDataSources()` → 每个
 * `DasDataSource.getModel()` → `DasModel.traverser()` 筛 `DasTable` →
 * 按名匹配 → `DasTable.getDasChildren(ObjectKind.COLUMN)` → 每列 `getName()/getComment()`。
 */
public final class DatabaseSchemaResolver {

    public static final DatabaseSchemaResolver INSTANCE = new DatabaseSchemaResolver();

    private static final Logger LOG = Logger.getInstance(DatabaseSchemaResolver.class);

    /** database 插件标记类（探测用，M17 同 ExecuteSqlSupport）。 */
    private static final String MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager";

    /** DasTable / DasColumn / DasObject 的二进制名（ObjectKind 判类型用）。 */
    private static final String DAS_TABLE = "com.intellij.database.model.DasTable";
    private static final String DAS_COLUMN = "com.intellij.database.model.DasColumn";
    private static final String DAS_OBJECT = "com.intellij.database.model.DasObject";
    private static final String OBJECT_KIND = "com.intellij.database.model.ObjectKind";

    // T-Embed-10 真机二轮：Method 一律从「声明接口/类」获取，禁止 obj.javaClass.getMethod——
    // 实现类（LocalDataSource 等）在 database-plugin 内部包（非导出），跨插件 classloader 反射
    // invoke 抛 IllegalAccessException（15:54 沙盒日志实证）。getManagers 从接口拿所以一直成功。
    private static final String DAS_DATA_SOURCE = "com.intellij.database.model.DasDataSource";
    private static final String DAS_MODEL = "com.intellij.database.model.DasModel";
    private static final String DAS_TYPED_OBJECT = "com.intellij.database.model.DasTypedObject";
    private static final String DATA_TYPE = "com.intellij.database.model.DataType";

    /** 列名 → 数据源注释（表名匹配不到返回空 Map，调用方回落实体注释）。 */
    public static Map<String, String> columnComments(Project project, String tableName) {
        Map<String, ColumnMeta> columns = columnMeta(project, tableName);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ColumnMeta> e : columns.entrySet()) {
            out.put(e.getKey(), e.getValue().comment() != null ? e.getValue().comment() : "");
        }
        return out;
    }

    /**
     * T-Perf-09：仅读缓存的列注释（补全 autopopup 热路径专用）——命中返回缓存值；
     * 未命中返回空 Map（调用方回落实体注释），<strong>不触发数据源全量遍历</strong>。
     * 缓存由 JOIN 富化后台预取（DiagramSchemaEnricher.enrich）或首次 {@link #tableMeta} 调用填充。
     */
    public static Map<String, String> columnCommentsCached(Project project, String tableName) {
        if (project == null || tableName == null || tableName.isBlank()) return Map.of();
        if (!isDatabaseAvailable()) return Map.of();
        List<Object> dataSources = dataSources(project);
        if (dataSources == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        synchronized (metaCache) {
            for (Object ds : dataSources) {
                Map<String, TableMeta> perDs = metaCache.get(ds);
                if (perDs == null) continue;
                TableMeta meta = perDs.get(tableName.toLowerCase(java.util.Locale.ROOT));
                if (meta == null) continue;
                for (Map.Entry<String, ColumnMeta> e : meta.columns().entrySet()) {
                    String comment = e.getValue().comment();
                    out.putIfAbsent(e.getKey(), comment != null ? comment : "");
                }
            }
        }
        return out;
    }

    /**
     * 列名 → 列元数据（注释 + DB 真实类型串，T-Embed-02）。
     *
     * <p>类型串 = `DasTypedObject.getDataType().getSpecification()`（如 `int(11)` / `varchar(64)`，
     * javap 实证 DataType.getSpecification 即 Database「显示图」右对齐类型的渲染来源）。
     * 表名匹配不到返回空 Map，调用方回落实体推断值。
     */
    public static Map<String, ColumnMeta> columnMeta(Project project, String tableName) {
        return tableMeta(project, tableName).columns();
    }

    /**
     * T-Perf-09：TableMeta 缓存——键 = (dataSource 对象弱引用, tableName 小写)。
     * 数据源全量遍历（DasModel.traverser().toList()）代价随 schema 规模线性，JOIN 预览
     * 每打开一次、每个 JOIN 表各触发一次；命中后零遍历。数据源删除后条目随弱键回收；
     * 表匹配不到（通常=未连接/introspection 未完成）<b>不缓存负命中</b>——后续连接成功即可命中。
     */
    private static final Map<Object, Map<String, TableMeta>> metaCache =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * 表级元数据（表注释 + 列元数据，T-Embed-09 一次遍历同时取）。
     *
     * @param comment 数据源表 COMMENT（`DasTable.getComment()`，null/空白 = 数据库没写，调用方回落实体注释）
     */
    public static TableMeta tableMeta(Project project, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return TableMeta.EMPTY;
        }
        if (!isDatabaseAvailable()) {
            return TableMeta.EMPTY;
        }
        return ReadAction.compute(() -> tryCompute(project, tableName));
    }

    /** 数据源侧元数据（表注释为 null 时该维度缺失，调用方逐维度回落）。 */
    public record TableMeta(String comment, Map<String, ColumnMeta> columns) {
        public static final TableMeta EMPTY = new TableMeta(null, Map.of());
    }

    /** 数据源侧列元数据（均为 null 时表示该维度缺失，调用方逐维度回落）。 */
    public record ColumnMeta(String comment, String typeSpec) {
    }

    private static TableMeta tryCompute(Project project, String tableName) {
        List<Object> dataSources = dataSources(project);
        if (dataSources == null) {
            LOG.warn("DatabaseSchemaResolver: dataSources(project) reflection failed, table=" + tableName);
            return TableMeta.EMPTY;
        }
        if (dataSources.isEmpty()) {
            LOG.info("DatabaseSchemaResolver: no data sources configured, table=" + tableName);
            return TableMeta.EMPTY;
        }
        Class<?> dasTableClass;
        try {
            dasTableClass = Class.forName(DAS_TABLE);
        } catch (Throwable e) {
            return TableMeta.EMPTY;
        }
        String cacheKey = tableName.toLowerCase(java.util.Locale.ROOT);
        for (Object ds : dataSources) {
            // T-Perf-09：缓存命中直接返回（同一数据源同一表零遍历）
            TableMeta cached;
            synchronized (metaCache) {
                Map<String, TableMeta> perDs = metaCache.get(ds);
                cached = (perDs != null) ? perDs.get(cacheKey) : null;
            }
            if (cached != null) return cached;
            TableMeta found = collectFromDataSource(ds, tableName, dasTableClass);
            if (!found.columns().isEmpty() || found.comment() != null) {
                synchronized (metaCache) {
                    metaCache.computeIfAbsent(ds, k -> new java.util.HashMap<>()).put(cacheKey, found);
                }
                return found;
            }
        }
        // 表在所有数据源都找不到（通常=未连接/introspection 未完成/表不在当前库）
        // T-Perf-09：负命中不缓存——introspection 完成后同一查询应能命中
        LOG.info("DatabaseSchemaResolver: table=" + tableName + " not found in " + dataSources.size() + " data sources");
        return TableMeta.EMPTY;
    }

    /** database 插件是否可用（Ultimate/DataGrip）。 */
    private static boolean isDatabaseAvailable() {
        try {
            Class.forName(MANAGER_CLASS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 获取所有数据源（反射 `DataSourceManager.getManagers(project)` → `getDataSources()`）。 */
    private static List<Object> dataSources(Project project) {
        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            Method managersMethod = managerClass.getMethod("getManagers", Project.class);
            Object managers = managersMethod.invoke(null, project);
            if (!(managers instanceof List<?>)) {
                return List.of();
            }
            List<?> mgrList = (List<?>) managers;
            List<Object> dsList = new ArrayList<>();
            for (Object m : mgrList) {
                if (m == null) {
                    continue;
                }
                Method getDataSources = managerClass.getMethod("getDataSources");
                Object list = getDataSources.invoke(m);
                if (!(list instanceof List<?>)) {
                    continue;
                }
                for (Object it : (List<?>) list) {
                    if (it != null) {
                        dsList.add(it);
                    }
                }
            }
            return dsList;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 从单个数据源收集匹配表的表注释 + 列元数据（遍历 DasModel.traverser()）。 */
    private static TableMeta collectFromDataSource(Object ds, String tableName, Class<?> dasTableClass) {
        String tableComment = null;
        Map<String, ColumnMeta> out = new LinkedHashMap<>();
        // T-Embed-10.5 探针：B 路径 locateDbTables 直调同模型命中而本链 not found——
        // 分辨 elements 空遍历 / DasTable 筛不出 / 名称不匹配三种情形
        int[] scannedTables = {0};
        boolean[] hit = {false};
        try {
            Class<?> dasObjClass = Class.forName(DAS_OBJECT);
            Class<?> dasColumnClass = Class.forName(DAS_COLUMN);
            // T-Embed-09 真机修复：243 字节码实证 RawDataSource/DbDataSource 均无 getDasObject()，
            // getModel() 声明在父接口 DasDataSource（B 路径 locateDbTables 直调 ds.model 同源）——
            // 旧链 getDasObject().getModel() 反射 NoSuchMethodException 被吞 → 全量静默回落
            Object model = Class.forName(DAS_DATA_SOURCE).getMethod("getModel").invoke(ds);
            if (model == null) {
                return TableMeta.EMPTY;
            }
            Object traverser = Class.forName(DAS_MODEL).getMethod("traverser").invoke(model);
            if (traverser == null) {
                return TableMeta.EMPTY;
            }
            // 16:09 沙盒日志实证：DasModel.traverser() 返回 JBTreeTraverser（extends
            // FilteredTraverserBase，非 JBIterable 子类）——对 JBIterable 取 toList invoke 抛
            // IllegalArgumentException: object is not an instance of declaring class。
            // FilteredTraverserBase implements java.lang.Iterable，iterator() 为其 public final——
            // 用 Iterable/Iterator 接口方法手动收集（= Kotlin 直调 toList 扩展同语义）
            List<Object> elements = new ArrayList<>();
            try {
                // java.util.Iterator 为 JDK 接口，cast 后 hasNext/next 直调（避免再反射一层）
                Iterator<?> iterator = (Iterator<?>) Iterable.class.getMethod("iterator").invoke(traverser);
                while (iterator.hasNext()) {
                    elements.add(iterator.next());
                }
            } catch (Throwable e) {
                LOG.warn("DatabaseSchemaResolver: traverse elements failed", e);
                return TableMeta.EMPTY;
            }
            Class<?> objectKindClass = Class.forName(OBJECT_KIND);
            Object columnKind = null;
            for (Object ec : objectKindClass.getEnumConstants()) {
                if (ec instanceof Enum<?> && ((Enum<?>) ec).name().equals("COLUMN")) {
                    columnKind = ec;
                    break;
                }
            }
            if (columnKind == null) {
                return TableMeta.EMPTY;
            }
            Method getNameM = dasObjClass.getMethod("getName");
            Method getCommentM = dasObjClass.getMethod("getComment");
            Method getChildrenM = dasObjClass.getMethod("getDasChildren", objectKindClass);

            for (Object element : elements) {
                if (element == null || !dasTableClass.isInstance(element)) {
                    continue;
                }
                scannedTables[0]++;
                String tName = (String) getNameM.invoke(element);
                if (tName == null) {
                    continue;
                }
                if (!tName.equalsIgnoreCase(tableName)) {
                    continue;
                }
                hit[0] = true;
                // 表级 COMMENT 与列遍历同一元素读取（blank 视为数据库没写，保留 null 供调用方回落）
                String tc = (String) getCommentM.invoke(element);
                tableComment = (tc != null && !tc.isBlank()) ? tc : null;
                Object columnsObj = getChildrenM.invoke(element, columnKind);
                if (!(columnsObj instanceof Iterable<?>)) {
                    continue;
                }
                for (Object col : (Iterable<?>) columnsObj) {
                    if (col == null || !dasColumnClass.isInstance(col)) {
                        continue;
                    }
                    String cName = (String) getNameM.invoke(col);
                    if (cName == null || cName.isBlank()) {
                        continue;
                    }
                    String comment = (String) getCommentM.invoke(col);
                    // DasTypedObject.getDataType().getSpecification()（反射；列无类型信息时为 null）
                    String typeSpec = null;
                    try {
                        Object dataType = Class.forName(DAS_TYPED_OBJECT).getMethod("getDataType").invoke(col);
                        if (dataType != null) {
                            typeSpec = (String) Class.forName(DATA_TYPE).getMethod("getSpecification").invoke(dataType);
                        }
                    } catch (Throwable ignored) {
                        // 列无类型信息时为 null
                    }
                    if ((comment != null && !comment.isBlank()) || (typeSpec != null && !typeSpec.isBlank())) {
                        out.put(cName, new ColumnMeta(
                                comment != null && !comment.isBlank() ? comment : null,
                                typeSpec != null && !typeSpec.isBlank() ? typeSpec : null));
                    }
                }
            }
        } catch (Throwable e) {
            // 升 WARN+堆栈：真机排障依赖此行（仅消息无法定位炸点，16:09 日志教训）
            LOG.warn("DatabaseSchemaResolver.collectFromDataSource failed", e);
        }
        LOG.info("DatabaseSchemaResolver scan: table=" + tableName
                + ", tablesScanned=" + scannedTables[0]
                + ", hit=" + hit[0]
                + ", foundColumns=" + out.size()
                + ", tableComment=" + (tableComment != null));
        return new TableMeta(tableComment, out);
    }

    private DatabaseSchemaResolver() {
    }
}
