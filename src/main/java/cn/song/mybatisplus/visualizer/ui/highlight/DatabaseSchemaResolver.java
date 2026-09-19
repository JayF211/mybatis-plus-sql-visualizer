package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

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
 * <p>遍历路径：`DataSourceManager.getManagers(project)` → `getDataSources()` → 每个数据源的
 * `DasDataSource.getModel()` → `DasModel.traverser()` 迭代筛 `DasTable` → 按名匹配 →
 * `DasObject.getDasChildren(ObjectKind.COLUMN)` → 每列 `getName()/getComment()/getDataType()`。
 *
 * <p>消费面：JOIN 图富化（{@link cn.song.mybatisplus.visualizer.ui.diagram.DiagramSchemaEnricher}）、
 * EXPLAIN 目标表结构、MCP schema 工具，以及 <b>T-DbSchemaCompletion 的补全候选</b>
 * （{@link #columnsCached} 只读缓存 + {@link #prefetchTables} 后台预热——数据源列不再只当注释用，
 * 库里存在而实体类没有的列也进补全候选）。
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
     * T-DbSchemaCompletion：多表 schema 列并集（<b>只读缓存，不触发遍历</b>）。
     *
     * <p>补全 autopopup 热路径专用（T-Perf-09 红线：候选生成不做数据源遍历）——命中返回缓存值，
     * 未命中（或数据源未连接/未预热）返回空 Map，调用方回落到实体列。缓存由 {@link #prefetchTables}
     * 在预览打开、控制台就绪时后台填充（JOIN 富化路径经 {@link #tableMeta} 同源填充）。
     *
     * <p>多表并集按列名去重（同名跨表列取首次命中）：补全上下文是「本文档涉及的表」的扁平视图，
     * 跨表同名列不细分（别名限定补全见计划 §六「明确不做」）。
     */
    public static Map<String, ColumnMeta> columnsCached(Project project, java.util.Collection<String> tableNames) {
        if (project == null || tableNames.isEmpty()) return Map.of();
        if (!isDatabaseAvailable()) return Map.of();
        List<Object> dataSources = dataSources(project);
        if (dataSources == null) return Map.of();
        Map<String, ColumnMeta> out = new LinkedHashMap<>();
        synchronized (metaCache) {
            for (String name : tableNames) {
                if (name.isBlank()) continue;
                String key = name.toLowerCase(java.util.Locale.ROOT);
                for (Object ds : dataSources) {
                    Map<String, TableMeta> perDs = metaCache.get(ds);
                    if (perDs == null) continue;
                    TableMeta meta = perDs.get(key);
                    if (meta == null) continue;
                    for (Map.Entry<String, ColumnMeta> e : meta.columns().entrySet()) {
                        out.putIfAbsent(e.getKey(), e.getValue());
                    }
                }
            }
        }
        return out;
    }

    /**
     * T-DbSchemaCompletion：预热若干表的 schema 缓存（供 {@link #columnsCached} 零遍历读取）。
     *
     * <p><b>调用方负责线程</b>（BGT 执行；内部 {@link #tableMeta} 自带 ReadAction）；Community、无数据源、
     * 表名为空时立即返回 0。返回真正解析到内容的表数——调用方据此判定是否有必要刷新补全上下文。
     *
     * <p>数据源晚连接（`getModel()` 为 null）时本方法解析 0 张且<b>不留负缓存</b>（见 {@link #resolveFromSources}
     * 的 bail 语义），控制台就绪后重新触发即可命中。
     */
    public static int prefetchTables(Project project, Collection<String> tableNames) {
        if (project == null) return 0;
        if (!isDatabaseAvailable()) return 0;
        int resolved = 0;
        for (String name : tableNames) {
            if (name.isBlank()) continue;
            TableMeta meta;
            try {
                meta = tableMeta(project, name);
            } catch (Throwable e) {
                continue;
            }
            if (!meta.columns().isEmpty() || meta.comment() != null) resolved++;
        }
        return resolved;
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
     * 表匹配不到（通常=未连接/introspection 未完成）走 {@link #negativeCache} 负命中缓存
     * （T-MetaCheapSource：TTL + DDL 失效双保险，见下）。
     *
     * <p>T-DdlCacheInvalidate：DDL/事务提交语句执行提交前经 execute/MetadataInvalidator 全量失效
     * （本缓存与负命中缓存同为此前盘点出的自持 schema 面——ALTER TABLE 后补全/JOIN 富化拿旧结构
     * 是正确性破口，失效走 O(1) 引用清空）。
     */
    private static final Map<Object, Map<String, TableMeta>> metaCache =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** T-DdlCacheInvalidate：正缓存全量失效（引用置空语义，下次取数零遍历回填）。 */
    public static void invalidateAll() {
        synchronized (metaCache) {
            metaCache.clear();
        }
    }

    // ----------
    // T-MetaCheapSource（O-21 T-MC-01）：负命中缓存。
    // agent 场景同一幻觉表名会在一次会话内反复尝试，此前每次未命中都全量遍历 DasModel
    // （多数据源 × 大模型时一次调用可到秒级）。键 = (dataSource 弱引用, 表名小写)，值 = 记入时间戳；
    // TTL 30s 用时间戳惰性判定（不用定时器）防长会话陈旧——建表后 30s 内的误报由 MetadataInvalidator
    // 失效兜底（登记 label 见 execute/MetadataInvalidator init 块，DDL/事务后与正缓存一起清）。
    // 弱键同 metaCache 口径：数据源删除后条目随弱键回收。
    //
    // T-MC-02（表名索引：一次遍历建 name → DasTable 索引，把「逐名全扫」变「一次扫描多次查询」）
    // 为可选件，按计划 P3 自律**默认不启用**——真机数据显示 T-MC-01 后仍慢才引入，避免过度工程。
    // ----------

    /** 负缓存 TTL（毫秒）：时间戳惰性过期，过期即重扫，无常驻定时器。 */
    public static final long NEGATIVE_TTL_MS = 30_000L;

    /** 测试专用 TTL 旋钮（生产恒等于 {@link #NEGATIVE_TTL_MS}；调短/归零验证过期语义）。 */
    public static volatile long negativeTtlMs = NEGATIVE_TTL_MS;

    private static final Map<Object, Map<String, Long>> negativeCache =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 负缓存清空（MetadataInvalidator 登记入口；引用置空语义，幂等无害）。 */
    public static void clearNegativeCache() {
        synchronized (negativeCache) {
            negativeCache.clear();
        }
    }

    /** 负命中是否在 TTL 内（过期条目惰性移除，不留定时器）。 */
    private static boolean isNegativeCached(Object ds, String cacheKey) {
        Long stamped;
        synchronized (negativeCache) {
            Map<String, Long> perDs = negativeCache.get(ds);
            stamped = (perDs != null) ? perDs.get(cacheKey) : null;
        }
        if (stamped == null) return false;
        // 严格小于：TTL=0 语义=立即过期（同一毫秒内也不新鲜）——负缓存 TTL 测试的确定性
        // 依赖此边界（<= 形态下同毫秒调用判新鲜，分片负载下曾致 flake expected 2 was 1）
        if (System.currentTimeMillis() - stamped < negativeTtlMs) return true;
        synchronized (negativeCache) {
            Map<String, Long> perDs = negativeCache.get(ds);
            if (perDs != null) perDs.remove(cacheKey);
        }
        return false;
    }

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

    /**
     * 数据源侧元数据（表注释为 null 时该维度缺失，调用方逐维度回落）。
     *
     * @param comment    数据源表 COMMENT（`DasTable.getComment()`，null/空白 = 数据库没写，调用方回落实体注释）
     * @param columns    列名 → 列元数据
     * @param scanBailed T-DbSchemaCompletion：本次<b>没能查</b>（数据源模型未加载 / ObjectKind 取不到 /
     *   反射链异常），区别于「查了、确认没有此表」。调用方据此决定是否记负缓存——把"查不了"当
     *   "不存在"会让数据源晚连接时 30s 内的补全与富化全被负缓存挡掉（T-MetaCheapSource 语义缺口）
     */
    public record TableMeta(String comment, Map<String, ColumnMeta> columns, boolean scanBailed) {
        public static final TableMeta EMPTY = new TableMeta(null, Map.of(), false);

        /** 旧形态：scanBailed 缺省 false。 */
        public TableMeta(String comment, Map<String, ColumnMeta> columns) {
            this(comment, columns, false);
        }
    }

    /**
     * 数据源侧列元数据（均为 null 时表示该维度缺失，调用方逐维度回落）。
     *
     * @param comment  DB COMMENT（null/空白 = 数据库没写）
     * @param typeSpec DB 真实类型串（`getDataType().getSpecification()`；null = 无类型信息）
     * @param notNull  T-TestAssertReal：非空标记（`DasTypedObject.isNotNull()`；null = 反射失败/取不到）
     */
    public record ColumnMeta(String comment, String typeSpec, Boolean notNull) {
        /** 旧形态：notNull 缺省 null。 */
        public ColumnMeta(String comment, String typeSpec) {
            this(comment, typeSpec, null);
        }
    }

    // T-TestAssertReal：notNull 取自 DasTypedObject.isNotNull()（javap 实证，DasColumn extends
    // DasTypedObject）。收录条件放宽为「comment/typeSpec 非空 或 notNull==true」后，仅非空标记
    // 列（comment/typeSpec 双空）也会进 Map——只消费 comment 的调用点自行过滤 blank 保持放宽前语义。
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
        return resolveFromSources(tableName, dataSources);
    }

    /**
     * 按数据源逐个解析：正缓存命中 → 负缓存 TTL 内跳过 → 全扫（扫描探针可注入供表驱动测试，
     * 无 database 插件的单测环境走不了真实反射链；生产 scanProbe 传 null 走既有反射路径，
     * 正命中回填与返回行为与 T-Perf-09 原实现一致）。
     *
     * <p>T-DbSchemaCompletion：查不了（{@code scanBailed}）与"确认没有"分道——前者不写负缓存、不短路
     * 后续数据源、下次调用重试；聚合结果仅在<b>所有</b>数据源都查不了时才标 {@code scanBailed}。
     *
     * @param scanProbe 扫描探针（null = 走既有反射路径）
     */
    public static TableMeta resolveFromSources(String tableName, List<Object> sources,
                                               java.util.function.BiFunction<Object, String, TableMeta> scanProbe) {
        BiFunction<Object, String, TableMeta> scan = (scanProbe != null) ? scanProbe : (ds, name) -> {
            try {
                return collectFromDataSource(ds, name, Class.forName(DAS_TABLE));
            } catch (Throwable e) {
                return TableMeta.EMPTY;
            }
        };
        String cacheKey = tableName.toLowerCase(java.util.Locale.ROOT);
        int bailed = 0;
        for (Object ds : sources) {
            // T-Perf-09：缓存命中直接返回（同一数据源同一表零遍历）
            TableMeta cached;
            synchronized (metaCache) {
                Map<String, TableMeta> perDs = metaCache.get(ds);
                cached = (perDs != null) ? perDs.get(cacheKey) : null;
            }
            if (cached != null) return cached;
            // T-MetaCheapSource：TTL 内负命中跳过该数据源全扫（introspection 完成后由 DDL
            // 失效/TTL 过期两条路放开，见 negativeCache 注释）
            if (isNegativeCached(ds, cacheKey)) continue;
            TableMeta found = scan.apply(ds, tableName);
            if (!found.columns().isEmpty() || found.comment() != null) {
                synchronized (metaCache) {
                    metaCache.computeIfAbsent(ds, k -> new java.util.HashMap<>()).put(cacheKey, found);
                }
                return found;
            }
            // T-DbSchemaCompletion：查不了的（模型未加载 / ObjectKind 取不到）**不写负缓存**——
            // 否则数据源晚连接时会把 30s 内的补全、JOIN 富化全挡掉，且负缓存"过期前不再重试"
            // 恰好卡住刚连上的那一分钟。继续看下一个数据源，都不行就留给下次调用重试。
            if (found.scanBailed()) {
                bailed++;
                continue;
            }
            // 该数据源确认没有此表：记负缓存（时间戳，TTL 内同表查询不再全扫）
            synchronized (negativeCache) {
                negativeCache.computeIfAbsent(ds, k -> new java.util.HashMap<>()).put(cacheKey, System.currentTimeMillis());
            }
        }
        // 表在所有数据源都没拿到（通常=未连接/introspection 未完成/表不在当前库）
        if (bailed > 0) {
            LOG.info("DatabaseSchemaResolver: table=" + tableName + " 无法解析——" + bailed + "/" + sources.size()
                    + " 个数据源查不了（模型未加载/ObjectKind 取不到，原因见上方 scan 行），未记负缓存、下次调用重试");
        } else {
            LOG.info("DatabaseSchemaResolver: table=" + tableName + " not found in " + sources.size() + " data sources");
        }
        return new TableMeta(null, Map.of(), bailed > 0 && bailed == sources.size());
    }

    /** {@link #resolveFromSources} 便捷形态：scanProbe 缺省 null（生产反射路径）。 */
    public static TableMeta resolveFromSources(String tableName, List<Object> sources) {
        return resolveFromSources(tableName, sources, null);
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

    /**
     * 取 DBN `ObjectKind` 的具名常量（T-DbSchemaCompletion 根因修复）。
     *
     * <p><b>别再写 {@code enumConstants}</b>：`com.intellij.database.model.ObjectKind` 是 DBN 手写的
     * type-safe enum（`Object` 子类 implements `Comparable` + public static final 常量 +
     * `ourKinds` 注册表 + `values()`），<b>不是 {@code java.lang.Enum}</b>——262.10968.63 字节码实证：
     * 类文件中 {@code java/lang/Enum} 出现 0 次、无 {@code $VALUES}/{@code valueOf}/{@code ordinal}。
     * {@code Class.enumConstants} 对它恒返回 null，历史上本处正是这么写的，于是每次调用都在该行静默早退、
     * 列遍历一次都没执行过，而早退无日志，上层只打印误导性的 {@code not found in N data sources}
     * （真机 23 次调用 scan 行 0 次，同名表其实存在于 information_schema）。同仓非反射调用
     * （{@code DbTableSpecAdapter} 的 {@code ObjectKind.COLUMN}）一直正常，取静态字段即可。
     *
     * <p>取不到时返回 null 由调用方按「查不了」处理（不写负缓存 + 留日志），不做 API 形态猜测。
     */
    public static Object resolveObjectKind(Class<?> kindClass, String name) {
        try {
            return kindClass.getField(name).get(null);
        } catch (Throwable e) {
            return null;
        }
    }

    /** ObjectKind 可用常量名（排障用：取不到 COLUMN 时把实际字段列进日志，一眼看出 API 漂移）。 */
    private static String kindConstantNames(Class<?> kindClass) {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Field f : kindClass.getFields()) {
                if (sb.length() > 0) sb.append('/');
                sb.append(f.getName());
            }
            return sb.toString();
        } catch (Throwable e) {
            return "?";
        }
    }

    /** 数据源显示名（日志用；取不到返回 `?`）。 */
    private static String dsName(Object ds) {
        try {
            Object name = Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(ds);
            return (name instanceof String) ? (String) name : "?";
        } catch (Throwable e) {
            return "?";
        }
    }

    /**
     * 从单个数据源收集匹配表的表注释 + 列元数据（遍历 DasModel，命中即取列）。
     *
     * <p>T-DbSchemaCompletion：<b>任何"查不了"的情形都不再静默早退</b>——统一置 {@code bail} 原因，
     * 函数末尾那行 scan 日志必打（此前 model/traverser/columnKind 三处 null 直接 {@code return}，
     * 既无日志又丢掉了"没查"与"没有"的区别）。
     */
    private static TableMeta collectFromDataSource(Object ds, String tableName, Class<?> dasTableClass) {
        String tableComment = null;
        Map<String, ColumnMeta> out = new LinkedHashMap<>();
        // T-Embed-10.5 探针：B 路径 locateDbTables 直调同模型命中而本链 not found——
        // 分辨 elements 空遍历 / DasTable 筛不出 / 名称不匹配三种情形
        int scannedTables = 0;
        boolean hit = false;
        String bail = null;
        // runCatching{...}.onFailure{...} 等价：try/catch(Throwable)；lambda 内 return@runCatching
        // = 跳出本块且不触发 onFailure → 用带标签块 + break 落到末尾 scan 日志
        scanAttempt:
        try {
            Class<?> dasObjClass = Class.forName(DAS_OBJECT);
            Class<?> dasColumnClass = Class.forName(DAS_COLUMN);
            // T-Embed-09 真机修复：243 字节码实证 RawDataSource/DbDataSource 均无 getDasObject()，
            // getModel() 声明在父接口 DasDataSource（B 路径 locateDbTables 直调 ds.model 同源）——
            // 旧链 getDasObject().getModel() 反射 NoSuchMethodException 被吞 → 全量静默回落
            Object model = Class.forName(DAS_DATA_SOURCE).getMethod("getModel").invoke(ds);
            if (model == null) {
                // 数据源未连接/未内省（预览打开常早于建连）：不是"没有此表"，不能记负缓存
                bail = "数据源模型未加载(getModel=null)";
                break scanAttempt;
            }
            Object traverser = Class.forName(DAS_MODEL).getMethod("traverser").invoke(model);
            if (traverser == null) {
                bail = "DasModel.traverser=null";
                break scanAttempt;
            }
            // 16:09 沙盒日志实证：DasModel.traverser() 返回 JBTreeTraverser（extends
            // FilteredTraverserBase，非 JBIterable 子类）——对 JBIterable 取 toList invoke 抛
            // IllegalArgumentException: object is not an instance of declaring class。
            // FilteredTraverserBase implements java.lang.Iterable，iterator() 为其 public final——
            // 用 Iterable/Iterator 接口方法手动收集（= Kotlin 直调 toList 扩展同语义）
            List<Object> elements;
            try {
                // java.util.Iterator 为 JDK 接口，cast 后 hasNext/next 直调（避免再反射一层）
                Iterator<?> iterator = (Iterator<?>) Iterable.class.getMethod("iterator").invoke(traverser);
                List<Object> collected = new ArrayList<>();
                while (iterator.hasNext()) {
                    collected.add(iterator.next());
                }
                elements = collected;
            } catch (Throwable e) {
                bail = "遍历 DasModel 失败: " + e.getClass().getSimpleName();
                LOG.warn("DatabaseSchemaResolver: traverse elements failed", e);
                break scanAttempt;
            }
            Class<?> objectKindClass = Class.forName(OBJECT_KIND);
            // T-DbSchemaCompletion：ObjectKind 非 java.lang.Enum，不能走 enumConstants（恒 null）
            Object columnKind = resolveObjectKind(objectKindClass, "COLUMN");
            if (columnKind == null) {
                bail = "ObjectKind.COLUMN 取不到（实际常量：" + kindConstantNames(objectKindClass) + "）";
                break scanAttempt;
            }
            Method getNameM = dasObjClass.getMethod("getName");
            Method getCommentM = dasObjClass.getMethod("getComment");
            Method getChildrenM = dasObjClass.getMethod("getDasChildren", objectKindClass);

            for (Object element : elements) {
                if (element == null || !dasTableClass.isInstance(element)) {
                    continue;
                }
                scannedTables++;
                String tName = (getNameM.invoke(element) instanceof String) ? (String) getNameM.invoke(element) : null;
                if (tName == null) {
                    continue;
                }
                if (!tName.equalsIgnoreCase(tableName)) {
                    continue;
                }
                hit = true;
                // 表级 COMMENT 与列遍历同一元素读取（blank 视为数据库没写，保留 null 供调用方回落）
                Object tcObj = getCommentM.invoke(element);
                String tc = (tcObj instanceof String) ? (String) tcObj : null;
                tableComment = (tc != null && !tc.isBlank()) ? tc : null;
                Object columnsObj = getChildrenM.invoke(element, columnKind);
                if (!(columnsObj instanceof Iterable<?>)) {
                    continue;
                }
                for (Object col : (Iterable<?>) columnsObj) {
                    if (col == null || !dasColumnClass.isInstance(col)) {
                        continue;
                    }
                    Object cNameObj = getNameM.invoke(col);
                    String cName = (cNameObj instanceof String) ? (String) cNameObj : null;
                    if (cName == null || cName.isBlank()) {
                        continue;
                    }
                    Object commentObj = getCommentM.invoke(col);
                    String comment = (commentObj instanceof String) ? (String) commentObj : null;
                    // DasTypedObject.getDataType().getSpecification()（反射；列无类型信息时为 null）
                    String typeSpec = null;
                    try {
                        Object dataType = Class.forName(DAS_TYPED_OBJECT).getMethod("getDataType").invoke(col);
                        if (dataType != null) {
                            Object spec = Class.forName(DATA_TYPE).getMethod("getSpecification").invoke(dataType);
                            typeSpec = (spec instanceof String) ? (String) spec : null;
                        }
                    } catch (Throwable ignored) {
                        // 列无类型信息时为 null
                    }
                    // T-TestAssertReal：DasTypedObject.isNotNull()（javap 实证声明于 DasTypedObject 接口，
                    // DasColumn extends DasTypedObject）——Method 一律从声明接口获取（T-Embed-10 跨
                    // classloader 教训，禁止 obj.javaClass.getMethod）；反射失败为 null，不进非空清单
                    Boolean notNull = null;
                    try {
                        Object flag = Class.forName(DAS_TYPED_OBJECT).getMethod("isNotNull").invoke(col);
                        notNull = (flag instanceof Boolean) ? (Boolean) flag : null;
                    } catch (Throwable ignored) {
                        // 反射失败为 null，不进非空清单
                    }
                    // 收录条件放宽：clean DDL（无注释无类型串）的 NOT NULL 列此前会被过滤掉
                    if ((comment != null && !comment.isBlank()) || (typeSpec != null && !typeSpec.isBlank())
                            || Boolean.TRUE.equals(notNull)) {
                        out.put(cName, new ColumnMeta(
                                comment != null && !comment.isBlank() ? comment : null,
                                typeSpec != null && !typeSpec.isBlank() ? typeSpec : null,
                                notNull));
                    }
                }
            }
        } catch (Throwable e) {
            // 升 WARN+堆栈：真机排障依赖此行（仅消息无法定位炸点，16:09 日志教训）
            bail = "反射链异常: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.warn("DatabaseSchemaResolver.collectFromDataSource failed", e);
        }
        LOG.info("DatabaseSchemaResolver scan: table=" + tableName
                + ", ds=" + dsName(ds)
                + ", bail=" + (bail != null ? bail : "-")
                + ", tablesScanned=" + scannedTables
                + ", hit=" + hit
                + ", foundColumns=" + out.size()
                + ", tableComment=" + (tableComment != null));
        return new TableMeta(tableComment, out, bail != null);
    }

    private DatabaseSchemaResolver() {
    }
}
