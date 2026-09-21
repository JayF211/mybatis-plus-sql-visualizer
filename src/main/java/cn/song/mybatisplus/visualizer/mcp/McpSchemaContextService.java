// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSchemaContextService.kt 移植
// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSchemaContextService.kt 移植（P4 / T-McpSchemaScope 整文件重写级：自建 DasModel「表名 → 目标库那张表」索引）
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.execute.JdbcUrlDatabase;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import kotlin.Pair;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * T-McpToolset sqlv_schema_context 取数适配层。
 *
 * <p><b>P4 / T-McpSchemaScope：表名匹配限定到「该数据源自己的 schema/库」。</b>
 *
 * <p>一期复用 DatabaseSchemaResolver 的既有通道，而那条通道<b>只按表名匹配</b>（不认 schema，也不按别名
 * 过滤数据源）。真机 2026-09-20 实测：`tables: "user"` + `dataSource: "mp_demo@localhost"` 的返回里
 * 同时出现 `mp_demo.user` 业务列与 `mysql.user` 系统列，且表注释取到的是 `mysql.user` 的
 * 「Users and global privileges」——对应 idea.log 那行
 * `scan: table=user, ds=jpa_demo@localhost, tablesScanned=335, hit=true, foundColumns=60`：
 * 一次遍历把<b>所有同名表的列合并进同一个 Map</b>（60 列 = 业务表 9 列 + 系统表 50+ 列），
 * 别名甚至没参与匹配（`user` 命中的是列表首个数据源）。
 *
 * <p>本层改为自己按 DasModel 建「表名 → 目标库那张表」索引：
 * <ol>
 *   <li>别名给出且命中时<b>只扫该数据源</b>（不再跨数据源按名撞第一个命中）；</li>
 *   <li>数据源内用 {@link JdbcUrlDatabase#nameFromUrl(String)}（连接 URL 里的库名，T-ConsoleDbContext
 *      同源口径）取目标库名，只收命名空间祖先链里含该库名的表——同名系统表（`mysql` /
 *      `information_schema` …）不再混入；</li>
 *   <li>目标库名取不到（URL 无库段 / Oracle SID 形态）时退回按名匹配（一期口径，不新增失败模式）；</li>
 *   <li>索引是<b>调用内</b>的：同一数据源一次调用只遍历一次模型（替代 T-PS5-06 的扫描账本，且对 N 张
 *      表都生效），<b>不读 DatabaseSchemaResolver 的正缓存</b>——那份缓存按表名存的就是合并结果，
 *      读它等于没修（预览/补全链会先把它填成污染值）。</li>
 * </ol>
 *
 * <p>反射链沿用 DatabaseSchemaResolver 已证实的调用面（DasDataSource.getModel → DasModel.traverser
 * 走 Iterable 而非 JBIterable、ObjectKind 非 java.lang.Enum 须 getField 取常量、列类型走
 * DasTypedObject.getDataType().getSpecification()）；Community / 无 database 插件时类缺失 →
 * 全链 runCatching 退化为空索引，最终仍以结构化 SCHEMA_NOT_AVAILABLE 报错（零弹窗 fail closed）。
 */
public final class McpSchemaContextService {

    private McpSchemaContextService() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpSchemaContextService INSTANCE = new McpSchemaContextService();

    private static final Logger LOG = Logger.getInstance(McpSchemaContextService.class);

    // DBN 反射类名（与 DatabaseSchemaResolver 同源；跨插件 classloader，一律按声明接口取方法）
    private static final String DAS_DATA_SOURCE = "com.intellij.database.model.DasDataSource";
    private static final String DAS_MODEL = "com.intellij.database.model.DasModel";
    private static final String DAS_OBJECT = "com.intellij.database.model.DasObject";
    private static final String DAS_TABLE = "com.intellij.database.model.DasTable";
    private static final String DAS_COLUMN = "com.intellij.database.model.DasColumn";
    private static final String DAS_TYPED_OBJECT = "com.intellij.database.model.DasTypedObject";
    private static final String DATA_TYPE = "com.intellij.database.model.DataType";
    private static final String OBJECT_KIND = "com.intellij.database.model.ObjectKind";
    private static final String RAW_CONNECTION_CONFIG = "com.intellij.database.model.RawConnectionConfig";

    /** 命名空间祖先链的最大回溯层数（库 → 模式 → 表；超出即视为取不到，不做无界遍历）。 */
    private static final int MAX_ANCESTOR_DEPTH = 4;

    /** 组装结果（error 非空 = 结构化错误；否则 text 为紧凑上下文）。 */
    public record Outcome(
        /** 紧凑上下文文本（结构化错误时为 null）。 */
        String text,
        /** 结构化错误文本（成功时为 null）。 */
        String error
    ) {
        /** Kotlin 默认参数等价重载：error 缺省 null（成功形态）。 */
        public Outcome(String text) {
            this(text, null);
        }
    }

    /**
     * 模型里的一张候选表（反射层产出；纯逻辑层按 {@code ancestorNames} 判定它属于哪个库）。
     *
     * @param ancestorNames 自表<b>父节点</b>向上的命名空间名链（库/模式/数据源；不含表名本身，空白名不入链）
     */
    public record ModelTable(
        /** 表名。 */
        String name,
        /** 自表父节点向上的命名空间名链。 */
        List<String> ancestorNames,
        /** 表注释（无则 null）。 */
        String comment,
        /** 列清单。 */
        List<McpSchemaFormatter.ColumnLine> columns
    ) {
    }

    /** 测试缝：模型读取替身（默认 null = 走真实 DBN 反射链；单测环境无 database 插件）。 */
    public static Function<Object, List<ModelTable>> modelTablesForTest = null;

    /** 测试缝：数据源连接 URL 替身（默认 null = 走 DBN 反射链）。 */
    public static Function<Object, String> dataSourceUrlForTest = null;

    public static Outcome buildContext(Project project, String dataSourceAlias, List<String> tableNames, int maxTables) {
        int requested = tableNames.size();
        int limit = McpSchemaFormatter.clampMaxTables(maxTables);

        // 别名存在性校验（别名给出但不匹配 → 明确报错列可选名）
        String alias = dataSourceAlias == null ? null : dataSourceAlias.trim();
        if (alias != null && alias.isEmpty()) {
            alias = null;
        }
        List<String> names = McpDbExecutor.listDataSourceNames(project);
        if (McpDbExecutor.isDatabaseAvailable()) {
            if (names.isEmpty()) {
                return new Outcome(
                    null,
                    McpToolErrors.code(
                        McpToolErrors.DATASOURCE_NOT_FOUND,
                        "no data sources configured in this project; configure one in the Database tool window."
                    )
                );
            }
            if (alias != null && !containsIgnoreCase(names, alias)) {
                return new Outcome(
                    null,
                    McpToolErrors.code(
                        McpToolErrors.DATASOURCE_NOT_FOUND,
                        "data source '" + alias + "' not found; available: " + String.join(", ", names) + "."
                    )
                );
            }
        }

        List<String> diagnostics = new ArrayList<>();
        Pair<List<McpSchemaFormatter.TableEntry>, List<String>> collected =
            collectEntries(targetSources(project, alias, names), tableNames, limit, diagnostics);
        return outcomeOf(collected.getFirst(), collected.getSecond(), limit, requested, diagnostics);
    }

    /**
     * 本次调用要扫的数据源：别名命中 → 仅该数据源（P4 的核心：别名不再只是存在性校验）；
     * 未指定别名 → 全部数据源（每个各按自己的 URL 库限定），与一期「无别名即全扫」行为一致。
     */
    private static List<Object> targetSources(Project project, String alias, List<String> knownNames) {
        Object only = McpDbExecutor.findDataSource(project, alias);
        if (only != null) {
            List<Object> single = new ArrayList<>(1);
            single.add(only);
            return single;
        }
        // 别名给定时 findDataSource 已在上层校验过存在性；这里只可能是「未指定别名 + 多数据源」
        List<Object> out = new ArrayList<>();
        for (String name : knownNames) {
            Object ds = McpDbExecutor.findDataSource(project, name);
            if (ds != null) {
                out.add(ds);
            }
        }
        return out;
    }

    /**
     * 组装结果（{@code entries} 为空时按「无元数据」结构化报错；否则走紧凑格式化）。
     *
     * <p>{@code diagnostics} 是本次扫描的逐数据源实况（`ds=… targetDb=… modelTables=… indexed=…`）。
     * 复核发现（20260920）：<b>scope 过滤掉全部候选表</b>时旧文案只说「可能未连接或内省未完成」，
     * 而真实原因是「表不在该数据源的当前库里」——agent 会照文案做无用重试（重连/等内省）。
     * 故文案补上「表可能不在当前库」这一支，并把实况原样附在末尾（`modelTables>0` 且 `indexed=0`
     * 即「扫到了但都被库名过滤掉」的自解释证据）。
     */
    public static Outcome outcomeOf(List<McpSchemaFormatter.TableEntry> entries, List<String> missing,
                                    int limit, int requested, List<String> diagnostics) {
        if (entries.isEmpty()) {
            String scan = (diagnostics == null || diagnostics.isEmpty())
                ? ""
                : " (scanned: " + String.join("; ", diagnostics) + ")";
            return new Outcome(
                null,
                McpToolErrors.code(
                    McpToolErrors.SCHEMA_NOT_AVAILABLE,
                    "no schema metadata for " + String.join(", ", missing) + "; the table may not exist in the " +
                        "data source's current database, or the data source may be disconnected or " +
                        "introspection has not finished — connect it in the Database tool window and retry." +
                        scan
                )
            );
        }
        String text = McpSchemaFormatter.format(entries, limit, requested);
        return new Outcome(text);
    }

    /** {@link #outcomeOf} 便捷形态：diagnostics 缺省空（无实况时不追加括号）。 */
    public static Outcome outcomeOf(List<McpSchemaFormatter.TableEntry> entries, List<String> missing,
                                    int limit, int requested) {
        return outcomeOf(entries, missing, limit, requested, List.of());
    }

    /**
     * 逐表取数：数据源<b>一次遍历建索引</b>，N 张表共用（T-PS5-06 的「同数据源一次调用最多全扫一次」
     * 由索引天然满足，且命中表也不再重复遍历）。表名按大小写不敏感匹配，先到先得。
     *
     * <p>public 供表驱动单测（单测环境无 database 插件，{@link #modelTablesForTest} /
     * {@link #dataSourceUrlForTest} 注入替身即可直测「限定目标库」整条链路；生产路径无注入，行为一致）。
     */
    public static Pair<List<McpSchemaFormatter.TableEntry>, List<String>> collectEntries(
        List<Object> sources,
        List<String> tableNames,
        int limit,
        List<String> diagnostics
    ) {
        List<McpSchemaFormatter.TableEntry> entries = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        // 身份键（DBN 数据源对象无稳定 equals 语义，沿用 DatabaseSchemaResolver 的 IdentityHashMap 口径）
        Map<Object, Map<String, McpSchemaFormatter.TableEntry>> indexBySource = new IdentityHashMap<>();
        for (String name : tableNames.subList(0, Math.min(limit, tableNames.size()))) {
            String key = name.toLowerCase(Locale.ROOT);
            McpSchemaFormatter.TableEntry hit = null;
            for (Object ds : sources) {
                Map<String, McpSchemaFormatter.TableEntry> index = indexBySource.get(ds);
                if (index == null) {
                    index = scanSource(ds, diagnostics);
                    indexBySource.put(ds, index);
                }
                McpSchemaFormatter.TableEntry found = index.get(key);
                if (found != null) {
                    hit = found;
                    break;
                }
            }
            // 标签沿用调用方请求的表名（与一期一致：agent 看到的是自己写下的名字）
            if (hit == null) {
                missing.add(name);
            } else {
                entries.add(new McpSchemaFormatter.TableEntry(name, hit.comment(), hit.columns()));
            }
        }
        return new Pair<>(entries, missing);
    }

    /** {@link #collectEntries} 便捷形态：diagnostics 缺省 null（不记实况）。 */
    public static Pair<List<McpSchemaFormatter.TableEntry>, List<String>> collectEntries(
        List<Object> sources,
        List<String> tableNames,
        int limit
    ) {
        return collectEntries(sources, tableNames, limit, null);
    }

    /** 单数据源扫描（一次遍历 → 表名索引）；{@code diagnostics} 非空时记一行实况供错误文案自解释。 */
    private static Map<String, McpSchemaFormatter.TableEntry> scanSource(Object ds, List<String> diagnostics) {
        List<ModelTable> candidates = (modelTablesForTest != null) ? modelTablesForTest.apply(ds) : null;
        if (candidates == null) {
            candidates = readModelTables(ds);
        }
        String target = targetDatabase(ds);
        Map<String, McpSchemaFormatter.TableEntry> index = indexModelTables(candidates, target);
        String line = "ds=" + dsName(ds) + " targetDb=" + (target != null ? target : "-")
            + " modelTables=" + candidates.size() + " indexed=" + index.size();
        LOG.info("McpSchemaContextService scan: " + line);
        if (diagnostics != null) {
            diagnostics.add(line);
        }
        return index;
    }

    /**
     * 候选表 → 表名索引（P4 纯逻辑层，可直测）。
     *
     * <p>同名表跨 schema 时<b>只收目标库那张</b>：不在 {@code targetDatabase} 命名空间下的候选直接丢弃
     * （不合并列——一期把两张表的列并进一个 Map，正是污染的直接成因）。
     * {@code targetDatabase} 为 null（URL 未带库）时不限定，保持一期按名匹配口径。
     */
    public static Map<String, McpSchemaFormatter.TableEntry> indexModelTables(
        List<ModelTable> candidates,
        String targetDatabase
    ) {
        Map<String, McpSchemaFormatter.TableEntry> out = new LinkedHashMap<>();
        for (ModelTable candidate : candidates) {
            if (targetDatabase != null && !inTargetSchema(candidate.ancestorNames(), targetDatabase)) {
                continue;
            }
            // 与一期「无列无注释 = 未找到」口径一致（未连接/内省未完成时不冒充命中）
            if (candidate.columns().isEmpty() && candidate.comment() == null) {
                continue;
            }
            String key = candidate.name().toLowerCase(Locale.ROOT);
            if (out.containsKey(key)) {
                continue;
            }
            out.put(key, new McpSchemaFormatter.TableEntry(
                candidate.name(), candidate.comment(), candidate.columns()));
        }
        return out;
    }

    /** 祖先命名空间链里是否含目标库名（大小写不敏感；MySQL 的「库」与 PG 的 schema 同链处理）。 */
    public static boolean inTargetSchema(List<String> ancestorNames, String targetDatabase) {
        for (String name : ancestorNames) {
            if (name.equalsIgnoreCase(targetDatabase)) {
                return true;
            }
        }
        return false;
    }

    /** 数据源连接 URL 里的目标库名（取不到 → null，调用方退回按名匹配）。 */
    private static String targetDatabase(Object ds) {
        if (dataSourceUrlForTest != null) {
            return JdbcUrlDatabase.nameFromUrl(dataSourceUrlForTest.apply(ds));
        }
        try {
            Object config = Class.forName(DAS_DATA_SOURCE).getMethod("getConnectionConfig").invoke(ds);
            Object url = (config != null)
                ? Class.forName(RAW_CONNECTION_CONFIG).getMethod("getUrl").invoke(config)
                : null;
            return JdbcUrlDatabase.nameFromUrl((url instanceof String) ? (String) url : null);
        } catch (Throwable e) {
            return null;
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
     * 读一个数据源 DasModel 里的全部表（表注释 + 列元数据 + 命名空间祖先链）。
     *
     * <p>反射链逐条对齐 DatabaseSchemaResolver.collectFromDataSource 的实测口径（243/262 双版本）：
     * `getModel()` 声明在 DasDataSource；`traverser()` 返回 JBTreeTraverser（<b>非 JBIterable</b>，
     * 只能走 `java.lang.Iterable.iterator()`）；`ObjectKind.COLUMN` 是 DBN 手写 type-safe enum，
     * `enumConstants` 恒 null 必须 `getField`；列类型 `DasTypedObject.getDataType().getSpecification()`；
     * `DasTypedObject.isNotNull()`。任何一步失败 → 空索引（上层报 SCHEMA_NOT_AVAILABLE），不抛。
     */
    private static List<ModelTable> readModelTables(Object ds) {
        // runCatching{...}.getOrElse{...} 等价：try/catch(Throwable) + LOG.warn + emptyList；
        // lambda 内 return@runCatching emptyList() = 提前返回空清单（同样不抛）
        try {
            Class<?> dasTableClass = Class.forName(DAS_TABLE);
            Class<?> dasColumnClass = Class.forName(DAS_COLUMN);
            Class<?> objectKindClass = Class.forName(OBJECT_KIND);
            Object columnKind = objectKindClass.getField("COLUMN").get(null);
            if (columnKind == null) {
                return List.of();
            }
            Object model = Class.forName(DAS_DATA_SOURCE).getMethod("getModel").invoke(ds);
            if (model == null) {
                return List.of();
            }
            Object traverser = Class.forName(DAS_MODEL).getMethod("traverser").invoke(model);
            if (traverser == null) {
                return List.of();
            }
            // traverser 是 JBTreeTraverser（非 JBIterable）：只能按 java.lang.Iterable 契约迭代
            if (!(traverser instanceof Iterable<?>)) {
                return List.of();
            }
            Iterator<?> iterator = ((Iterable<?>) traverser).iterator();

            Class<?> dasObjectClass = Class.forName(DAS_OBJECT);
            Method getName = dasObjectClass.getMethod("getName");
            Method getComment = dasObjectClass.getMethod("getComment");
            Method getDasParent = dasObjectClass.getMethod("getDasParent");
            Method getChildren = dasObjectClass.getMethod("getDasChildren", objectKindClass);
            Class<?> dasTypedObjectClass = Class.forName(DAS_TYPED_OBJECT);
            Method getDataType = dasTypedObjectClass.getMethod("getDataType");
            Method getSpecification = Class.forName(DATA_TYPE).getMethod("getSpecification");
            Method isNotNull = dasTypedObjectClass.getMethod("isNotNull");

            List<ModelTable> out = new ArrayList<>();
            while (iterator.hasNext()) {
                Object element = iterator.next();
                if (element == null) {
                    continue;
                }
                if (!dasTableClass.isInstance(element)) {
                    continue;
                }
                Object nameObj = getName.invoke(element);
                String name = (nameObj instanceof String) ? (String) nameObj : null;
                if (name == null || name.isBlank()) {
                    continue;
                }
                List<McpSchemaFormatter.ColumnLine> columns = new ArrayList<>();
                Object childrenObj = getChildren.invoke(element, columnKind);
                if (childrenObj instanceof Iterable<?>) {
                    for (Object col : (Iterable<?>) childrenObj) {
                        if (col == null || !dasColumnClass.isInstance(col)) {
                            continue;
                        }
                        Object columnNameObj = getName.invoke(col);
                        String columnName = (columnNameObj instanceof String) ? (String) columnNameObj : null;
                        if (columnName == null || columnName.isBlank()) {
                            continue;
                        }
                        Object commentObj = getComment.invoke(col);
                        String comment = (commentObj instanceof String) ? (String) commentObj : null;
                        String typeSpec = null;
                        try {
                            Object dataType = getDataType.invoke(col);
                            if (dataType != null) {
                                Object spec = getSpecification.invoke(dataType);
                                typeSpec = (spec instanceof String) ? (String) spec : null;
                            }
                        } catch (Throwable ignored) {
                            // runCatching{...}.getOrNull() 等价：列无类型信息时为 null
                        }
                        Boolean notNull = null;
                        try {
                            Object flag = isNotNull.invoke(col);
                            notNull = (flag instanceof Boolean) ? (Boolean) flag : null;
                        } catch (Throwable ignored) {
                            // runCatching{...}.getOrNull() 等价：反射失败为 null
                        }
                        // 收录条件与一期一致（放宽到 NOT NULL 列）：clean DDL 的列不被过滤掉
                        if ((comment != null && !comment.isBlank())
                            || (typeSpec != null && !typeSpec.isBlank())
                            || Boolean.TRUE.equals(notNull)) {
                            columns.add(new McpSchemaFormatter.ColumnLine(
                                columnName,
                                (typeSpec != null && !typeSpec.isBlank()) ? typeSpec : null,
                                (comment != null && !comment.isBlank()) ? comment : null,
                                notNull));
                        }
                    }
                }
                Object tableCommentObj = getComment.invoke(element);
                String tableComment = (tableCommentObj instanceof String) ? (String) tableCommentObj : null;
                out.add(new ModelTable(
                    name,
                    ancestorNames(element, getName, getDasParent),
                    (tableComment != null && !tableComment.isBlank()) ? tableComment : null,
                    columns));
            }
            return out;
        } catch (Throwable e) {
            LOG.warn("McpSchemaContextService: 读 DasModel 失败（本次调用按未找到披露）", e);
            return List.of();
        }
    }

    /** 自表向上的命名空间名链（{@link #MAX_ANCESTOR_DEPTH} 层为界；模型根非 DasObject 时自然终止）。 */
    private static List<String> ancestorNames(Object table, Method getName, Method getDasParent) {
        List<String> out = new ArrayList<>();
        Object current = null;
        try {
            current = getDasParent.invoke(table);
        } catch (Throwable ignored) {
            // runCatching{...}.getOrNull() 等价：取不到父节点即自然终止
        }
        int depth = 0;
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            String name = null;
            try {
                Object raw = getName.invoke(current);
                name = (raw instanceof String) ? (String) raw : null;
            } catch (Throwable ignored) {
                // 同上：单个节点取名失败不入链
            }
            if (name != null && !name.isBlank()) {
                out.add(name);
            }
            Object next = null;
            try {
                next = getDasParent.invoke(current);
            } catch (Throwable ignored) {
                // 同上：取不到父节点即自然终止
            }
            current = next;
            depth++;
        }
        return out;
    }

    /** 数据源显示名清单里是否含别名（大小写不敏感；别名存在性校验用）。 */
    private static boolean containsIgnoreCase(List<String> names, String alias) {
        for (String name : names) {
            if (name.equalsIgnoreCase(alias)) {
                return true;
            }
        }
        return false;
    }
}
