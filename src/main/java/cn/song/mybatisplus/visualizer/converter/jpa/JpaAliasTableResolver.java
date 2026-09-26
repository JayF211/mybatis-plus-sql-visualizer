package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * HQL/JPQL 别名作用域解析器（O-57）。
 *
 * 缺陷背景：属性→列名映射原先只遍历<b>根实体</b>列清单、且替换正则不带别名限定，于是
 * {@code join WmsPickTicketDetail detail on detail.pickTicketId = ...} 里的 {@code detail.pickTicketId}
 * 永远映射不到列名（真列是 {@code pick_ticket_id}），预览 SQL 复制出去执行报 {@code Unknown column}。
 * 副表实体的 TableInfo 其实已经算过一次（JpaFromEntityMapper 里的
 * {@code JpaEntityResolver().resolve}），只是当时只取了 {@code tableName} 就把列清单丢掉了。
 *
 * 本解析器把「FROM/JOIN 别名 → 该实体的 TableInfo」补上，供两条转换链按作用域分派属性映射。
 *
 * <p><b>扫描时点（关键）</b>：必须在实体名 → 表名替换<b>之前</b>——那时 FROM/JOIN 后面的实体标识符还在
 * SQL 里，一旦替换成小写表名，PSI 短名检索就不再命中。同时必须在 JpaJoinRewriter 的 JOIN
 * 改写<b>之后</b>，否则改写新增的副表别名（{@code JOIN u.rel r} → {@code INNER JOIN t_target r ON ...}）看不到。</p>
 *
 * <p><b>解析失败静默跳过</b>：不抛异常、不按命名策略盲猜列名（猜错列名比保留属性名更误导，沿用
 * JpaFromEntityMapper 的既有原则）。未解析出的别名回落调用方既有的根实体列清单行为，
 * 因此本改动对既有产物是「严格收窄 + 新增」，不产生「原本会被替换、现在不替换」的回归面
 * ——这条要求依赖 {@link JpaAliasPropertyMapper} 的正则<b>容忍限定符与点号之间的空白</b>
 * （{@code u .code} 这类合法写法改前会被旧实现替换，正则若要求紧邻就会漏掉，见该对象 KDoc）。</p>
 *
 * <p><b>已接受的边界</b>：关联路径 JOIN（{@code JOIN 根别名.关联属性 副表别名}）的副表别名解析不出来——
 * 扫描时点 JOIN 后面跟的是根别名（{@code pickTicket}）而非实体名；即便在 JpaJoinRewriter 改写之后
 * 扫描，看到的也只是改写产物里的表名（如 {@code t_department}），按短名检索不到实体类，同样走
 * 未映射兜底（= 与改前行为一致）。{@code where d.createdTime = ...} 这类关联路径副表属性仍不映射。
 * 也不支持逗号分隔的 FROM 列表（{@code from A a, B b}）——HQL 已废弃形态，同样走兜底。</p>
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
public final class JpaAliasTableResolver {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final JpaAliasTableResolver INSTANCE = new JpaAliasTableResolver();

    private JpaAliasTableResolver() {
    }

    /**
     * {@code FROM|JOIN <标识符> [AS] <别名>}。
     *
     * 别名捕获组缺省（两个组都空）表示该表无从属别名；此时下一个词可能是子句关键字
     * （{@code FROM UserEntity WHERE ...}），由 {@link #CLAUSE_KEYWORDS} 排除，不能当别名。
     */
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "(?i:\\b(?:FROM|JOIN)\\s+)([a-zA-Z_][a-zA-Z0-9_.$]*)" +
                    "(?:\\s+AS\\s+([a-zA-Z_][a-zA-Z0-9_]*)|\\s+([a-zA-Z_][a-zA-Z0-9_]*))?");

    /**
     * 子句关键字表（沿用 JpaJoinRewriter.normalizeAlias 的口径，补 JOIN 族与集合运算词）。
     * O-71 T-01 起由 {@link JpaAliasPropertyMapper} 共用（裸词替换的排除表之一）——master 侧提为
     * 文件顶层，本移植中留在外层类、内层类直接可见。
     */
    static final Set<String> CLAUSE_KEYWORDS = Set.of(
            "ON", "WHERE", "SET", "GROUP", "ORDER", "HAVING", "INNER", "LEFT",
            "RIGHT", "OUTER", "CROSS", "FULL", "JOIN", "FETCH", "AS", "UNION", "WITH",
            "BY", "AND", "OR", "NOT", "IN", "IS", "NULL", "EXISTS", "BETWEEN", "LIKE",
            "SELECT", "FROM", "DELETE", "UPDATE", "INSERT", "INTO", "VALUES", "NEW",
            "DISTINCT", "LIMIT", "OFFSET", "USING", "ASC", "DESC", "CASE", "WHEN", "THEN", "ELSE", "END");

    /**
     * 生产入口：按实体短名经 PSI 检索实体类后解析表信息。
     *
     * @param sql     已过参数替换与 JOIN 改写、<b>尚未做实体名→表名替换</b>的 SQL
     * @param table   根实体表信息（调用方已解析，直接绑定其别名，免重复 PSI）
     * @param project 项目；null 时全部副表别名解析不出（回落既有根实体列清单行为）
     */
    public static JpaAliasScope resolveAliases(@NotNull String sql, @NotNull TableInfo table,
                                               @Nullable Project project) {
        if (project == null) return resolveAliases(sql, table, entityName -> null);
        return resolveAliases(sql, table, entityName -> {
            try {
                PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
                PsiClass[] projectClasses =
                        cache.getClassesByName(entityName, GlobalSearchScope.projectScope(project));
                PsiClass clazz = projectClasses.length > 0 ? projectClasses[0] : null;
                if (clazz == null) {
                    PsiClass[] allClasses =
                            cache.getClassesByName(entityName, GlobalSearchScope.allScope(project));
                    clazz = allClasses.length > 0 ? allClasses[0] : null;
                }
                return clazz != null ? new JpaEntityResolver().resolve(clazz) : null;
            } catch (Throwable ignored) {
                return null;
            }
        });
    }

    /**
     * 解析函数注入形态（调用方已有 PsiClass / 解析缓存时直入，免按名检索；
     * LightPlatform 测试环境无需依赖索引）。
     *
     * @param resolveEntity 收到 FROM/JOIN 中的实体标识符（简单名），返回其表信息；
     *                      返回 null 表示无元数据（该别名走未映射兜底）
     */
    public static JpaAliasScope resolveAliases(@NotNull String sql, @NotNull TableInfo table,
                                               @NotNull kotlin.jvm.functions.Function1<String, TableInfo> resolveEntity) {
        Map<String, TableInfo> byAlias = new LinkedHashMap<>();
        Set<String> knownQualifiers = new LinkedHashSet<>();
        Map<String, String> entityTableNames = new LinkedHashMap<>();
        // 同一实体名只解析一次（PSI 检索 + 注解遍历都不便宜）
        Map<String, TableInfo> memo = new HashMap<>();

        if (!table.getEntityClassName().isBlank()) {
            memo.put(table.getEntityClassName(), table);
            registerTableName(entityTableNames, table.getEntityClassName(), table.getTableName());
        }

        Matcher matcher = TABLE_REFERENCE.matcher(sql);
        while (matcher.find()) {
            String rawEntity = matcher.group(1);
            if (CLAUSE_KEYWORDS.contains(rawEntity.toUpperCase(Locale.ROOT))) continue;
            String entity = rawEntity.substring(rawEntity.lastIndexOf('.') + 1);
            String g2 = matcher.group(2);
            String g3 = matcher.group(3);
            String captured = g2 != null && !g2.isEmpty() ? g2 : (g3 != null ? g3 : "");
            String alias = !captured.isEmpty() && !CLAUSE_KEYWORDS.contains(captured.toUpperCase(Locale.ROOT))
                    ? captured : null;

            if (!memo.containsKey(entity)) {
                TableInfo resolved;
                try {
                    resolved = resolveEntity.invoke(entity);
                } catch (Throwable ignored) {
                    resolved = null;
                }
                memo.put(entity, resolved);
            }
            TableInfo info = memo.get(entity);
            if (info != null) registerTableName(entityTableNames, entity, info.getTableName());

            // 登记限定符（含解析失败者）：ON 诊断据此区分「没出现过」与「出现过但解析不出」
            if (alias != null) knownQualifiers.add(alias);
            knownQualifiers.add(entity);
            if (info != null && !info.getTableName().isBlank()) knownQualifiers.add(info.getTableName());

            if (info == null) continue;
            // 有别名按别名绑定；无别名（或别名与实体名混用）用实体名/表名自身作键，
            // 覆盖 `from Entity where Entity.col` 与 `from 表名 where 表名.col` 两种限定写法
            if (alias != null) putIfAbsent(byAlias, alias, info);
            putIfAbsent(byAlias, entity, info);
            if (!info.getTableName().isBlank()) putIfAbsent(byAlias, info.getTableName(), info);
        }

        return new JpaAliasScope(byAlias, knownQualifiers, entityTableNames);
    }

    private static void registerTableName(@NotNull Map<String, String> target, @NotNull String entity,
                                          @NotNull String tableName) {
        if (!tableName.isBlank() && !"unknown_table".equals(tableName)) target.put(entity, tableName);
    }

    /** 先到先得：同一键（别名/实体名/表名撞名）以最先登记的绑定为准，避免后出现的覆盖已用绑定。 */
    private static void putIfAbsent(@NotNull Map<String, TableInfo> target, @NotNull String key,
                                    @NotNull TableInfo value) {
        if (!target.containsKey(key)) target.put(key, value);
    }

    /**
     * HQL/JPQL 别名作用域解析产物（O-57）。
     */
    public static final class JpaAliasScope {
        /**
         * 空作用域（master companion {@code EMPTY}）：属性映射回落「只看根实体列清单」的历史行为。
         */
        @NotNull
        public static final JpaAliasScope EMPTY =
                new JpaAliasScope(Map.of(), Set.of(), Map.of());

        /** 别名（以及无别名表的实体名/表名自身）→ 该实体的表信息；属性映射按限定符分派到它。 */
        @NotNull
        private final Map<String, TableInfo> byAlias;
        /**
         * FROM/JOIN 中出现过的全部限定符（别名、实体名、表名；<b>含解析失败者</b>）。
         *
         * 与 {@link #byAlias} 分开是因为两者语义不同：前者回答「这个限定符属于哪张表」，
         * 后者回答「这个限定符在 FROM/JOIN 里出现过吗」——ON 未映射诊断只该对「根本没出现过」
         * 的限定符报警，对「出现过但实体解析不出」保持沉默（那属于已知降级，不是异常）。
         */
        @NotNull
        private final Set<String> knownQualifiers;
        /** 实体标识符 → 真实表名；供 JpaFromEntityMapper 复用，免二次 PSI 检索。 */
        @NotNull
        private final Map<String, String> entityTableNames;

        public JpaAliasScope(@NotNull Map<String, TableInfo> byAlias, @NotNull Set<String> knownQualifiers,
                             @NotNull Map<String, String> entityTableNames) {
            this.byAlias = byAlias;
            this.knownQualifiers = knownQualifiers;
            this.entityTableNames = entityTableNames;
        }

        @NotNull
        public Map<String, TableInfo> getByAlias() { return byAlias; }
        @NotNull
        public Map<String, TableInfo> byAlias() { return byAlias; }
        @NotNull
        public Set<String> getKnownQualifiers() { return knownQualifiers; }
        @NotNull
        public Set<String> knownQualifiers() { return knownQualifiers; }
        @NotNull
        public Map<String, String> getEntityTableNames() { return entityTableNames; }
        @NotNull
        public Map<String, String> entityTableNames() { return entityTableNames; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof JpaAliasScope)) return false;
            JpaAliasScope that = (JpaAliasScope) o;
            return java.util.Objects.equals(this.byAlias, that.byAlias) && java.util.Objects.equals(this.knownQualifiers, that.knownQualifiers) && java.util.Objects.equals(this.entityTableNames, that.entityTableNames);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(byAlias, knownQualifiers, entityTableNames);
        }

        @Override
        public String toString() {
            return "JpaAliasScope[" + "byAlias=" + byAlias + ", " + "knownQualifiers=" + knownQualifiers + ", " + "entityTableNames=" + entityTableNames + "]";
        }
    }

    /**
     * 别名作用域属性映射（O-57 T-01/T-02/T-06 的替换核心；O-71 T-01/T-02 扩展）。
     *
     * 放在本文件而非两个转换器内，是因为 HqlConverter 与 JpqlConverter 必须逐字一致——
     * 逻辑只有一份才谈得上「两条链行为一致」。
     *
     * 点号路径单遍扫描：每个 {@code 限定符.中间段.属性} 命中时按限定符分派到已解析别名；未命中
     * （裸限定符 / 别名解析不出实体）回落根实体列清单。O-71 起该回落不再「原样透传」：
     * 限定符位是嵌入段（{@code updateInfo.beDelete}）时剥离，裸属性（无点号的独立词）按根实体列
     * 清单精确映射——<b>契约变更</b>：O-57 以「只收窄不丢失」为由保守排除裸属性（正则要求带
     * 点号），实测证明该排除使无别名/漏写前缀的 HQL 的 WHERE/SELECT 投影永不映射，产物必为
     * 无效 SQL，故推翻。O-57 的既有红线（不产生「原本会被替换、现在不替换」的回归）仍然成立：
     * 本次只新增替换面，点号路径的行为逐字未变。
     */
    public static final class JpaAliasPropertyMapper {

        /** 单例实例（兼容 Kotlin object 调用形式）。 */
        public static final JpaAliasPropertyMapper INSTANCE = new JpaAliasPropertyMapper();

        private JpaAliasPropertyMapper() {
        }

        /**
         * 单引号字符串字面量（本仓唯一一份口径，属性映射/ON 诊断/JpqlConverter 子查询检测共用）。
         *
         * 标准 {@code ''} 转义天然切成相邻两段（{@code 'it''s b.c'} → {@code 'it'} + {@code 's b.c'}），
         * 内层路径同样落在字面量分支里被保护。
         */
        public static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'");

        /** 属性路径分支的 pattern 文本（PROPERTY_OR_LITERAL 与诊断扫描共用的唯一来源）。 */
        private static final String PROPERTY_PATTERN =
                "\\b([a-zA-Z_][a-zA-Z0-9_]*)(\\s*\\.\\s*)" +
                        "((?:[a-zA-Z_][a-zA-Z0-9_]*\\s*\\.\\s*)*)([a-zA-Z_][a-zA-Z0-9_]*)\\b";

        /**
         * 「字符串字面量 | 限定符.属性」单遍交替。
         *
         * 字面量分支<b>在前且不带捕获组</b>，命中即原样返回——引号内的 {@code x.createdTime}
         * 永远不被当属性路径改写（{@code where z.remark like '% x .createdTime %'} 必须逐字不变）。
         * 需要「抹平→还原」两段式，也就不会改写字面量内容。
         *
         * 未闭合单引号（{@code where x = 'abc} 到语句末尾）时字面量分支匹配不上，扫描继续向后，
         * 其后的合法属性照常映射——避让字面量不得顺手吞掉正常映射。
         *
         * 捕获组（只属于属性分支）：1 限定符 | 2 空白+点号 | 3 中间段（每段自带其后的空白+点号）| 4 属性名。
         * 限定符与点号之间的空白允许且<b>原样保留</b>（{@code u .code} / {@code u\n.code} 是合法写法，
         * 不容忍就会从「旧实现会替换」变成「不替换」，即回归）；中间段同样允许并保留空白。
         * 中间段本身按<b>解析出的列</b>的 {@code embeddedParent} 决定去留（见 {@link #isFlattenedEmbeddedPath}）：
         * {@code @Embedded} 展平列的路径要去掉嵌入段（{@code pickTicket.updateInfo.createdTime} → {@code pickTicket.created_time}），
         * 其余情况原样保留。
         */
        private static final Pattern PROPERTY_OR_LITERAL = Pattern.compile(
                STRING_LITERAL.pattern() + "|" + PROPERTY_PATTERN);

        /**
         * O-71 T-01：裸标识符分支（无点号路径的独立词）——「字面量 | 裸词」交替，字面量分支
         * 在前且不带捕获组，手法与 {@link #PROPERTY_OR_LITERAL} 一致。词后紧跟 {@code (} 的负前瞻排除
         * 函数调用（{@code count(1)} / {@code count (1)} 均覆盖）。
         */
        private static final Pattern BARE_WORD_OR_LITERAL = Pattern.compile(
                STRING_LITERAL.pattern() + "|\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b(?!\\s*\\()");

        /**
         * T-01 裸词替换的补充排除表：布尔/常量字面量与 HQL 特有操作词（子句关键字见外层
         * {@link JpaAliasTableResolver#CLAUSE_KEYWORDS}；函数调用已由负前瞻排除）。{@code OF} 是给
         * {@code MEMBER OF} 降级注释里残留词的双保险——该注释在本映射之前生成，其内的英文词不参与裸词替换。
         */
        private static final Set<String> BARE_WORD_EXCLUDED =
                Set.of("TRUE", "FALSE", "EMPTY", "MEMBER", "ESCAPE", "SOME", "ANY", "ALL", "OF");

        /**
         * {@code ON <条件>} 区域：到下一个子句关键字为止（跨行，{@code .*?} 配 DOTALL）。
         *
         * 整体 {@code (?i)}——大小写不敏感必须覆盖<b>前瞻里的子句关键字</b>，否则小写 HQL（常态）下
         * 区域不停在 {@code join}/{@code where}，会一路扫到语句末尾：既点名根本不在 ON 里的限定符，
         * 又会把后续子句里的点号字符串字面量误判成 {@code 限定符.属性}。
         */
        private static final Pattern ON_CLAUSE = Pattern.compile(
                "(?i)\\bON\\s(.*?)(?=\\s*\\b(?:WHERE|GROUP\\s+BY|ORDER\\s+BY|HAVING|UNION|LIMIT|" +
                        "LEFT|RIGHT|INNER|OUTER|CROSS|FULL|JOIN|ON)\\b|$)",
                Pattern.DOTALL);

        /**
         * 把 {@code 限定符.属性} 映射为 {@code 限定符.列名}，再对裸属性做同口径映射（O-71 T-01）。
         *
         * @param sql       已完成实体名→表名映射的 SQL（限定符是别名，不受实体名替换影响）
         * @param rootTable 根实体表信息（未映射别名的兜底来源，也是裸词映射的唯一来源）
         * @param scope     别名作用域（见 JpaAliasTableResolver）
         */
        public static String mapProperties(@NotNull String sql, @NotNull TableInfo rootTable,
                                           @NotNull JpaAliasScope scope) {
            StringBuilder sb = new StringBuilder();
            Matcher matcher = PROPERTY_OR_LITERAL.matcher(sql);
            while (matcher.find()) {
                String replacement;
                // 字面量分支（无捕获组）→ 原样返回：引号内的路径不是属性引用
                if (matcher.group(1) == null || matcher.group(1).isEmpty()) {
                    replacement = matcher.group(0);
                } else {
                    String qualifier = matcher.group(1);
                    String dot = matcher.group(2);
                    String middle = matcher.group(3);
                    String field = matcher.group(4);
                    TableInfo owner = scope.getByAlias().get(qualifier);
                    ColumnInfo column = (owner != null ? owner : rootTable).columnByField(field);
                    if (column == null || column.getFieldName().equals(column.getColumnName())) {
                        replacement = matcher.group(0);
                    } else if (owner == null && qualifier.equals(column.getEmbeddedParent())) {
                        // O-71 T-02：无别名 HQL 下嵌入段占限定符位（`updateInfo.beDelete`）→ 剥离限定符
                        // 产出裸列名（嵌入对象没有自己的表，`updateInfo.BE_DELETE` 在 SQL 语义下被当成
                        // 「库.表」限定）。判据仍是列自身的 embeddedParent；带别名时 qualifier 是真别名
                        // （owner 命中），走下方 isFlattenedEmbeddedPath 分支，行为不变
                        replacement = column.getColumnName();
                    } else if (isFlattenedEmbeddedPath(middle, column)) {
                        // @Embedded 展平（计划 §六 D2 已实施）：嵌入对象没有自己的表，其字段直接落在宿主表的列上，
                        // 故路径里的嵌入段必须去掉——`pickTicket.updateInfo.createdTime` 的正确产物是
                        // `pickTicket.created_time`；保留 `updateInfo.` 会产出无效 SQL（SQL 语义下
                        // `pickTicket.updateInfo` 被当成「库.表」）。判据取自<b>列自身的 embeddedParent</b>
                        // （展平时写入），而不是「中间段不是本表字段就丢掉」——后者会误删别的合法中间段。
                        replacement = qualifier + dot + column.getColumnName();
                    } else {
                        // 只换属性名，限定符/中间段及其间空白原样拼回；替换产物是列名（小写 snake），
                        // 不再命中本规则，天然幂等
                        replacement = qualifier + dot + middle + column.getColumnName();
                    }
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            return appendUnmappedOnHint(mapBareProperties(sb.toString(), rootTable, scope), scope);
        }

        /**
         * O-71 T-01：裸属性（无点号路径的独立词）→ 列名映射。
         *
         * <p>覆盖两类形态：完全无别名的 HQL（{@code from WorkstationStatus where stationCode = ...}，用户报障
         * 场景），与有别名但个别属性漏写前缀的混合形态（{@code where z.createdTime > :t and beAutoZone = true}）。
         * 这两类产物此前原样透传，而 Hibernate 真实生成的列名是大写下划线——透传产物执行必报
         * {@code Unknown column}，故 O-57 的保守契约（裸属性不替换）被推翻（契约变更登记见类 KDoc）。</p>
         *
         * <p>替换口径从严（误伤面压到最小）：
         * <ul>
         *  <li>{@code columnByField} <b>精确命中</b>且 {@code fieldName != columnName} 才替换，不猜命名规则；</li>
         *  <li>子句关键字（{@link JpaAliasTableResolver#CLAUSE_KEYWORDS}）、布尔/常量词（{@link #BARE_WORD_EXCLUDED}）不替换，
         *      词后紧跟 {@code (} 的（函数调用，{@code count(1)}）由正则负前瞻排除；</li>
         *  <li>{@code scope.knownQualifiers}（FROM/JOIN 出现过的别名、实体名、表名）不替换——防「别名/实体名
         *      恰好撞字段名」的误伤；</li>
         *  <li>单引号字面量内不替换（与 {@link #PROPERTY_OR_LITERAL} 同款「字面量分支在前」手法；
         *      未闭合单引号其后裸词照常映射，与点号路径口径一致）；</li>
         *  <li>多别名歧义场景沿用根实体列清单（与既有「未命中回落根实体」原则一致，不猜副表）。</li>
         * </ul>
         *
         * <p>替换产物是列名，再次扫描不命中（fieldName 是驼峰、列名是 snake/大写），天然幂等。</p>
         */
        private static String mapBareProperties(@NotNull String sql, @NotNull TableInfo rootTable,
                                                @NotNull JpaAliasScope scope) {
            StringBuilder sb = new StringBuilder();
            Matcher matcher = BARE_WORD_OR_LITERAL.matcher(sql);
            while (matcher.find()) {
                String word = matcher.group(1);
                String replacement;
                // 字面量分支（无捕获组）→ 原样返回：引号内的词不是属性引用
                if (word == null || word.isEmpty()) {
                    replacement = matcher.group(0);
                } else {
                    String upper = word.toUpperCase(Locale.ROOT);
                    if (CLAUSE_KEYWORDS.contains(upper) || BARE_WORD_EXCLUDED.contains(upper)) {
                        replacement = matcher.group(0);
                    } else if (scope.knownQualifiers.contains(word)) {
                        replacement = matcher.group(0);
                    } else {
                        ColumnInfo column = rootTable.columnByField(word);
                        replacement = column == null || column.getFieldName().equals(column.getColumnName())
                                ? matcher.group(0) : column.getColumnName();
                    }
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            return sb.toString();
        }

        /**
         * 该匹配是否为「嵌入对象路径」——即 {@code column} 是 {@code middle} 首个嵌入段的展平列。
         *
         * 只认<b>首个</b>段：嵌套 {@code @Embedded} 展平后列名已按 {@code @AttributeOverride} 链算准，
         * 整条中间段都该丢；而 {@code @Embedded} 的字段名记录的是最外层那个字段（见
         * {@link ColumnInfo#getEmbeddedParent()} 的写入点），故与首段比较即可。
         *
         * 中间段为空（普通两段路径）恒为 false；中间段首段与列的 {@code embeddedParent} 不等时为 false
         * ——即<b>不丢</b>中间段，保持既有行为。
         */
        private static boolean isFlattenedEmbeddedPath(@NotNull String middle, @NotNull ColumnInfo column) {
            String parent = column.getEmbeddedParent();
            if (parent == null) return false;
            if (middle.isBlank()) return false;
            String firstSegment = middle.trim();
            int dot = firstSegment.indexOf('.');
            return (dot >= 0 ? firstSegment.substring(0, dot) : firstSegment).equals(parent);
        }

        /**
         * 收集文本里的属性路径限定符。
         *
         * 复用 PROPERTY_OR_LITERAL 的同一份匹配（过滤掉字面量分支），
         * 因此诊断与属性映射对「引号内的 {@code a.b} 不是限定符路径」是同一口径，不存在第二套实现。
         */
        private static List<String> propertyQualifiers(@NotNull String text) {
            List<String> result = new ArrayList<>();
            Matcher matcher = PROPERTY_OR_LITERAL.matcher(text);
            while (matcher.find()) {
                // group(1) 为 null 或空 = 命中的是字面量分支
                if (matcher.group(1) == null || matcher.group(1).isEmpty()) continue;
                result.add(matcher.group(1));
            }
            return result;
        }

        /**
         * T-06：ON 条件里出现「在 FROM/JOIN 中根本没出现过的限定符」时附一次性注释。
         *
         * 只提升可发现性，不改写属性（§八：不猜列名）；注释附在语句末尾，不改变 SQL 语义。
         * 判定口径刻意保守——「出现过但实体解析不出」不报警（那是已知降级，报警会淹没真实信号）。
         *
         * 只对 SELECT/FROM 语句追加：UPDATE/DELETE 的 HQL 不支持 JOIN ON，本提示对它们无意义，
         * 而尾注一旦落到 UPDATE 上，会让 JpaUpdateParser 的 WHERE 解析因尾注释降级为 Raw
         * （条件树渲染退化）。判定在语句类型分派之前完成，故必须在此处显式短路。
         *
         * <p><b>不变量（改本文案前必读）</b>：注释文本内不得出现 {@code FROM}/{@code JOIN} 后紧跟空白 + 标识符。
         * 下游 SelectClauseExpander 的 joinPattern 与 JpaFromEntityMapper 的 ENTITY_REFERENCE
         * 都是纯文本正则、不识别注释，会把它当成 FROM/JOIN 子句或实体名去解析。
         * 现文案写的是 {@code FROM/JOIN，}——{@code FROM}/{@code JOIN} 后紧跟的是 {@code /} 与全角逗号（非 {@code \s}），
         * 两个扫描器都不会命中，满足该不变量。</p>
         */
        private static String appendUnmappedOnHint(@NotNull String sql, @NotNull JpaAliasScope scope) {
            String head = sql.stripLeading().toUpperCase(Locale.ROOT);
            if (!head.startsWith("SELECT") && !head.startsWith("FROM")) return sql;
            Set<String> seen = new LinkedHashSet<>();
            Matcher matcher = ON_CLAUSE.matcher(sql);
            while (matcher.find()) {
                for (String qualifier : propertyQualifiers(matcher.group(1))) {
                    if (!scope.knownQualifiers.contains(qualifier)) seen.add(qualifier);
                }
            }
            if (seen.isEmpty()) return sql;
            return sql + " /* ON 条件中的 " + String.join("、", seen) + " 未出现在 FROM/JOIN，其属性未映射，请核对实体注解 */";
        }
    }
}
