// T-SyncK2JMaster309 自 execute/MetadataInvalidator.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper;
import net.sf.jsqlparser.statement.Statement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 元数据缓存失效器（T-DdlCacheInvalidate / O-12，吸收 DBX {@code object_cache.rs} 语义）：
 * 识别「可能改变对象元数据的 SQL」（DDL + 事务提交关键字），执行提交前主动失效插件自持的
 * schema 缓存——<b>失败也要失效</b>（Oracle 系 DDL 隐式提交，DDL 报错不代表没提交），失效
 * 动作本身失败只记日志、绝不吞执行结果。
 *
 * <p>判定两路任一命中即失效（DBX 对齐）：</p>
 * <ul>
 *  <li>AST 路：JSqlParser 解析成功后按语句类型前缀判 DDL（Create、Alter、Drop、Truncate、
 *      Rename、Comment 前缀 + Commit/Rollback——parser 权威，引号内关键字不误伤）；</li>
 *  <li>关键字路：语句首词 ∈ {COMMIT, END, ROLLBACK, ABORT}（AST 对 PL/SQL 块等形态解析不出
 *      具体类型时的兜底；误失效代价仅一次重取，fail safe 方向）。</li>
 * </ul>
 * 解析失败 <b>fail closed</b>：按「可能改变」处理（宁多失效不多缓存）。
 *
 * <p>自持缓存登记处（守卫约定——<b>新增 schema 缓存必须在此登记</b>，否则 DDL 后旧结构泄漏）：</p>
 * <ul>
 *  <li>{@code ui.highlight.DatabaseSchemaResolver}（表/列注释与类型 metaCache，T-DC-01 盘点自持面）</li>
 *  <li>{@code ui.highlight.DatabaseSchemaResolver} 负命中缓存（T-MetaCheapSource：不存在的表名在
 *      TTL 内不再全扫，DDL 建表后必须清空否则新表 30s 内查不到）</li>
 * </ul>
 *
 * <p>其余盘点结论（不登记的证据见开发计划 §八 T-DC-01 盘点表）：DataSourceSelector @Volatile 族
 * 持临时 PSI/方言幂等标记非 schema；DialectPreMapping 探测平台映射无自持缓存；WrapperCache
 * 为 PSI 解析产物；McpSchemaContextService 每次取数；RollbackSnapshotStore 是用户粘贴的
 * before-image 历史镜像（DDL 后反而更需要）。</p>
 */
public final class MetadataInvalidator {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(MetadataInvalidator.class);

    public static final MetadataInvalidator INSTANCE = new MetadataInvalidator();
    private MetadataInvalidator() {}

    /** DDL/提交语句类型前缀（AST 路；前缀匹配跨 JSqlParser 小版本类名差异稳健）。 */
    private static final List<String> DDL_TYPE_PREFIXES = List.of("Create", "Alter", "Drop", "Truncate", "Rename", "Comment");

    /** DDL/提交语句类型全名（AST 路）。 */
    private static final Set<String> DDL_TYPE_NAMES = Set.of("Commit", "RollbackStatement");

    /**
     * 不透明语句类型：JSqlParser 5.3 对残缺/方言语句的宽松容器（实证：{@code CREATE TABLE} 缺列清单
     * → UnsupportedStatement、{@code BEGIN … END} 块 → Block）——结构不可判，走关键字扫描兜底。
     */
    private static final Set<String> OPAQUE_TYPE_NAMES = Set.of("UnsupportedStatement", "Block");

    /**
     * 关键字扫描兜底（DBX object_cache.rs:41 对齐）：DDL 动词 + 事务提交词并集，词边界匹配
     * （大小写不敏感）。仅对不透明类型启用——结构可判语句（SELECT/INSERT 等）的字面量里的
     * 关键字不误伤；不透明文本误失效代价仅一次重取（fail safe 方向）。
     */
    private static final Pattern KEYWORD_FALLBACK_REGEX =
            Pattern.compile("\\b(CREATE|ALTER|DROP|TRUNCATE|RENAME|COMMENT|COMMIT|END|ROLLBACK|ABORT)\\b", Pattern.CASE_INSENSITIVE);

    /** 失效动作登记表（label 供日志定位；动作异常只记日志不吞执行结果）。 */
    private static final Map<String, Runnable> invalidationActions = new LinkedHashMap<>();

    /**
     * T-PS5-01：批脚本廉价预筛阈值（分号计数近似语句数，超阈值才启用关键字否定排除）。
     * 50 条以下的批脚本直接进 AST（预筛本身也是一遍全文扫描，短文本上得不偿失）。
     * 测试缝（置 {@code Integer.MAX_VALUE} 即关预筛，用于「预筛路径与全解析判定等价」对照）。
     */
    public static volatile int batchPrefilterStatements = 50;

    /**
     * T-PS5-01：AST 解析单槽缓存（同文本连续两次判定只解析一次）。
     *
     * <p>场景：执行链<b>入口</b>（提交前预判）与<b>收尾</b>（成功/失败/超时三路兜底）对同一 SQL 文本
     * 各调一次 {@link #invalidateAfterExecution}，即各解析一次；数百条批脚本单次解析在 EDT 上
     * 几十~上百毫秒，两次叠加即用户可感的冻结窗口。
     *
     * <p>命中判据三重快判：引用相等（同一次 {@code document.text} 传入的常见形态）→ 长度 → 哈希，
     * 三者全等后再做一次<b>内容全等</b>收口——计划原案只到哈希（32 位碰撞理论可致漏判收尾兜底），
     * 这里补内容比较把误命中概率降为 0，代价是同长度异实例文本上一次字符串比较（远廉于解析）。
     * 单槽持有上一次判定文本的引用，规模上界=一次执行的 SQL 文本（编辑器 document 本身已持有
     * 同量级字符串），不随会话增长。
     *
     * <p>缓存只复用<b>解析与判定</b>，不复用失效动作——入口/收尾两次失效语义原样保留。
     */
    private static final class ParseSlot {
        final String text;
        final boolean result;

        ParseSlot(String text, boolean result) {
            this.text = text;
            this.result = result;
        }
    }

    private static volatile ParseSlot parseSlot = null;

    /**
     * T-PS5-01 测试缝：AST 解析器（默认 JSqlParser）。生产链路勿改；测试注入替身以断言
     * 「同文本两次判定只解析一次」与「预筛排除路径不触达解析」。
     *
     * <p>独立函数式接口（而非 {@code java.util.function.Function}）是因为
     * {@code CCJSqlParserUtil.parseStatements} 抛受检的 {@code JSQLParserException}——
     * 标准函数式接口的方法不允许抛出受检异常。</p>
     */
    @FunctionalInterface
    public interface StatementParser {
        List<Statement> parse(String sql) throws Exception;
    }

    public static volatile StatementParser parseStatementsForTest = SqlAstParser.INSTANCE::parseStatements;

    /** T-PS5-01 测试缝：清空单槽缓存（用例间隔离；生产链路无调用点）。 */
    public static void clearParseCacheForTest() {
        parseSlot = null;
    }

    static {
        // T-DC-01 盘点自持 schema 面（表/列注释与类型 metaCache）——登记失效
        register("DatabaseSchemaResolver.metaCache",
                () -> cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.INSTANCE.invalidateAll());
        // T-MetaCheapSource（T-MC-01）：负命中缓存与正缓存一起失效——DDL 建表后同会话立查
        // 必须能命中新表，负缓存滞留会让 agent 在 TTL 窗口内持续误报「表不存在」
        register("DatabaseSchemaResolver.negativeCache",
                () -> cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.INSTANCE.clearNegativeCache());
    }

    /**
     * 判定 SQL 是否可能改变对象元数据（纯逻辑，表驱动测试覆盖）。
     * 多语句任一命中即 true；解析失败 fail closed true。
     *
     * <p>T-PS5-01：同文本判定走单槽缓存（{@code parseSlot}）；超 {@link #batchPrefilterStatements} 条
     * （分号计数近似）的批脚本先做一次<b>否定式</b>廉价预筛——全文无任何 DDL/提交关键字
     * （词边界扫描，复用 {@link #KEYWORD_FALLBACK_REGEX} 形态）即直接判 false，不进 AST。
     * 预筛只做否定排除、不做肯定判定：关键字命中仍由 AST 权威判定（{@code SELECT 'DROP TABLE'}
     * 这类字面量命中关键字必须不误失效，与既有「字符串内关键字不误伤」语义一致），
     * 因此 fail-closed 方向不回退（解析失败照旧 true）。
     */
    public static boolean mayChangeMetadata(@org.jetbrains.annotations.NotNull String sql) {
        String cleaned = SqlCommentStripper.strip(sql);
        if (cleaned.isBlank()) return false;
        ParseSlot cached = parseSlot;
        if (cached != null) {
            if (cached.text == cleaned) return cached.result;
            if (cached.text.length() == cleaned.length()
                    && cached.text.hashCode() == cleaned.hashCode()
                    && cached.text.equals(cleaned)) {
                return cached.result;
            }
        }
        // T-PS5-01 批脚本廉价预筛（否定式）：大文本无关键字 → 任何语句都不可能是 DDL/提交类型
        // （AST 路的类型名由关键字派生），直接 false 省掉整次解析。
        if (isLargeBatch(cleaned) && !KEYWORD_FALLBACK_REGEX.matcher(cleaned).find()) {
            parseSlot = new ParseSlot(cleaned, false);
            return false;
        }
        // Kotlin 平台类型判空显式化（同 UnboundedWriteDetector.parseStatementsShared 注记）：
        // jsqlparser 5.3 同 JVM 已成功解析一次后，解析失败静默返回 null；null 在此转抛，
        // 落入下方 catch 的 fail-closed true，与 master 行为一致。
        List<Statement> statements;
        try {
            statements = Objects.requireNonNull(
                    parseStatementsForTest.parse(cleaned),
                    "parseStatementsForTest(cleaned) must not be null");
        } catch (Exception ignored) {
            parseSlot = new ParseSlot(cleaned, true);
            return true; // fail closed：宁多失效不多缓存（PL/SQL 块/方言形态解析不出即失效）
        }
        boolean mayChange = false;
        for (Statement statement : statements) {
            String typeName = statement.getClass().getSimpleName();
            if (DDL_TYPE_NAMES.contains(typeName) || startsWithAny(typeName, DDL_TYPE_PREFIXES)) {
                mayChange = true;
                break;
            }
            // 关键字兜底路：不透明类型（宽松容器/PL-SQL 块）结构不可判，按关键字扫描
            if (OPAQUE_TYPE_NAMES.contains(typeName) && KEYWORD_FALLBACK_REGEX.matcher(statement.toString()).find()) {
                mayChange = true;
                break;
            }
        }
        parseSlot = new ParseSlot(cleaned, mayChange);
        return mayChange;
    }

    /**
     * T-PS5-01 批脚本规模近似判定：分号计数 + 1 超阈值即按大文本处理（不解析、只数分号）。
     * 分号在字符串字面量内也会计数（高估），方向安全——高估只多走一次全文关键字扫描。
     */
    private static boolean isLargeBatch(String cleaned) {
        // 文本短于阈值时不可能有阈值个分号（每分号至少一字符），直接排除省一遍扫描
        if (cleaned.length() < batchPrefilterStatements) return false;
        int semicolons = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            if (cleaned.charAt(i) == ';' && ++semicolons >= batchPrefilterStatements) return true;
        }
        return false;
    }

    private static boolean startsWithAny(String typeName, List<String> prefixes) {
        for (String p : prefixes) {
            if (typeName.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * 执行链失效挂接点（T-DC-03）：执行提交前调用——{@link #mayChangeMetadata} 命中即逐项执行登记的
     * 失效动作，<b>在执行结果返回前完成失效</b>；每个动作 try-catch 只记日志，绝不影响执行链
     * （DBX 语义原样继承：失效失败不吞执行结果）。
     */
    public static void invalidateAfterExecution(@org.jetbrains.annotations.NotNull String sql) {
        if (!mayChangeMetadata(sql)) return;
        for (Map.Entry<String, Runnable> entry : invalidationActions.entrySet()) {
            String label = entry.getKey();
            Runnable action = entry.getValue();
            try {
                action.run();
            } catch (Exception e) {
                LOG.warn("[T-DdlCacheInvalidate] invalidation '" + label + "' failed (execution continues)", e);
            }
        }
    }

    /** 登记失效动作（label 唯一，重复登记覆盖旧动作——测试复位同入口）。 */
    public static void register(@org.jetbrains.annotations.NotNull String label, @org.jetbrains.annotations.NotNull Runnable action) {
        invalidationActions.put(label, action);
    }

    public static void unregister(@org.jetbrains.annotations.NotNull String label) {
        invalidationActions.remove(label);
    }
}
