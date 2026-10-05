package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FROM/JOIN 子句实体名 → 表名扫描器（J13，覆盖矩阵 §3「标量子查询 / IN(子查询) / EXISTS」行）。
 *
 * 背景：既有 [cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter.mapEntityToTable] 与
 * JpqlConverter 同名方法只替换 **主实体类名**（全局正则）——主实体在子查询 FROM 中复现
 * （`EXISTS (SELECT 1 FROM UserEntity u2 ...)`）天然覆盖；但子查询引用 **其他实体**
 * （`... EXISTS (SELECT o FROM OrderEntity o WHERE o.userId = u.id)`）时，主实体的
 * TableInfo 不携带 OrderEntity 的表名映射，字符串管道无从替换。
 *
 * 本扫描器在调用方提供 Project 时，扫描 SQL 全文所有 `FROM &lt;标识符&gt;` / `JOIN &lt;标识符&gt;`
 * 出现点（含括号内的子查询——正则全文匹配），对「大写字母开头的实体形态标识符」
 * 经 PSI 按短名查找实体类，用 [JpaEntityResolver] 解析真实表名后替换。
 * 只做**有元数据依据**的替换：查不到实体类的标识符原样保留（不做命名策略盲猜——
 * 猜错表名比保留实体名更误导），因此无 Project 时调用方应跳过本扫描，行为与既有管道一致。
 *
 * O-57 起调用方还可传入 {@code knownTableNames}（别名作用域扫描已解析出的「实体名 → 表名」）：
 * 命中即免一次 PSI 检索；无 Project 而 {@code knownTableNames} 非空时仍可完成替换
 * （解析来源不同，替换结果一致）。
 *
 * 边界（刻意不做）：
 *  - 不解析子查询结构（EXISTS/IN 子查询按「识别 + 透传」处理，见 SelectClauseExpander）
 *  - 不为小写标识符（已映射表名/原生 SQL 表名）做任何替换
 *  - 重复出现不重复替换：正则 `\b类名\b` 全局替换一次收敛，且替换产物（snake_case 表名）
 *    不再命中大写实体形态，天然幂等
 *
 * PSI 读取只封装在本文件内，只读不修改 psi/jpa/ 下任何文件。
 * 无状态单例，解析失败静默跳过，绝不抛异常。
 */
public final class JpaFromEntityMapper {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final JpaFromEntityMapper INSTANCE = new JpaFromEntityMapper();

    private JpaFromEntityMapper() {
    }

    /**
     * FROM / JOIN 后的大写开头标识符（HQL/JPQL 实体名约定形态）。
     * 大小写不敏感仅作用于 FROM/JOIN 关键字（`(?i:...)` 局部作用域），
     * 实体名组保持大小写敏感——已映射表名（小写）不得再被命中。
     */
    private static final Pattern ENTITY_REFERENCE =
            Pattern.compile("(?i:\\b(?:FROM|JOIN)\\s+)([A-Z][a-zA-Z0-9_]*)\\b");

    /**
     * 扫描并替换子查询内的其他实体名为表名（Project 形态——生产入口）。
     *
     * @param sql    管道中的 SQL 文本（主实体名已被既有 mapEntityToTable 替换）
     * @param table  主表信息（其 entityClassName 已处理，跳过）
     * @param project 项目（PSI 按名查找实体类；null 时原样返回——无元数据不做盲猜）
     * @return 替换后的 SQL；无可替换实体时原样返回（幂等）
     */
    public static String mapSubqueryEntities(String sql, TableInfo table, Project project) {
        return mapSubqueryEntities(sql, table, project, java.util.Map.of());
    }

    /**
     * 扫描并替换子查询内的其他实体名为表名（Project + 已知表名映射形态——O-57 生产入口）。
     *
     * @param sql    管道中的 SQL 文本（主实体名已被既有 mapEntityToTable 替换）
     * @param table  主表信息（其 entityClassName 已处理，跳过）
     * @param project 项目（PSI 按名查找实体类）
     * @param knownTableNames 调用方已解析出的「实体名 → 表名」（O-57：别名作用域扫描的副产物）。
     *        命中即免一次 PSI 检索；缺省空表时行为与历史版本一致。
     *        O-57 起 {@code project == null} 不再直接短路（改为「project 与 knownTableNames 同时为空才短路」）：
     *        该守卫**只在注入形态下才会真正放开**——生产 {@code convert(..., project = null)} 时
     *        {@code knownTableNames} 唯一可能的键是根实体名，而本扫描器明确过滤 {@code entityName != table.entityClassName}，
     *        故对既有调用方逐字等价（独立复核确认）。
     * @return 替换后的 SQL；无可替换实体时原样返回（幂等）
     */
    public static String mapSubqueryEntities(String sql, TableInfo table, Project project,
                                             java.util.Map<String, String> knownTableNames) {
        if (project == null && knownTableNames.isEmpty()) return sql;
        return mapSubqueryEntities(sql, table, entityName -> {
            String known = knownTableNames.get(entityName);
            if (known != null) return known;
            if (project == null) return null;
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
                if (clazz == null) return null;
                String tableName = new JpaEntityResolver().resolve(clazz).getTableName();
                return tableName != null && !tableName.isBlank() && !"unknown_table".equals(tableName)
                        ? tableName : null;
            } catch (Throwable ignored) {
                return null;
            }
        });
    }

    /**
     * 扫描并替换子查询内的其他实体名（表名解析函数注入形态——核心实现）。
     *
     * [resolveTableName] 收到实体类名，返回真实表名；返回 null 表示无元数据（跳过不改）。
     * 调用方已有 PsiClass / 解析缓存时用本形态，免按名检索
     * （LightPlatform 测试环境无需依赖索引）。
     */
    public static String mapSubqueryEntities(String sql, TableInfo table,
                                             Function<String, String> resolveTableName) {
        // 普查批：字面量掩码——字面量内的 `from OrderEntity`（FROM/JOIN 假象）不得驱动扫描，
        // 字面量内的实体名原文不得被替换正则命中
        JpaSqlLiteralMasking.Masked maskResult = JpaSqlLiteralMasking.mask(sql);
        String masked = maskResult.text();
        List<String> literals = maskResult.literals();
        Set<String> entityNames = new LinkedHashSet<>();
        Matcher matcher = ENTITY_REFERENCE.matcher(masked);
        while (matcher.find()) {
            String entityName = matcher.group(1);
            if (!entityName.equals(table.getEntityClassName())) {
                entityNames.add(entityName);
            }
        }
        if (entityNames.isEmpty()) return JpaSqlLiteralMasking.unmask(masked, literals);

        String result = masked;
        for (String entityName : entityNames) {
            Pattern word = Pattern.compile("\\b" + Pattern.quote(entityName) + "\\b");
            // 已无该实体名残留（如实体名与表名同形被主映射替换）时跳过，保证幂等
            if (!word.matcher(result).find()) continue;
            String tableName = resolveTableName.apply(entityName);
            if (tableName == null) continue;
            result = word.matcher(result).replaceAll(Matcher.quoteReplacement(tableName));
        }
        return JpaSqlLiteralMasking.unmask(result, literals);
    }
}
