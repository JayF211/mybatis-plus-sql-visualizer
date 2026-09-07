package cn.song.mybatisplus.visualizer.psource;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JdbcTemplate / NamedParameterJdbcTemplate 方法名检测器（JDBC 模板 SQL 字符串预览）。
 *
 * <p>纯函数（O(1) 文本匹配，零 PSI / 零 resolve），供 gutter 水印快速路径与 action 守卫共用。</p>
 *
 * <p>识别规则（对齐 Spring JDBC 模板的字符串 SQL 重载）：</p>
 * <ul>
 *  <li>{@code query*} 族（query / queryForObject / queryForList / queryForMap / queryForRowSet /
 *      queryForLong / queryForInt / queryForStream）</li>
 *  <li>{@code update} / {@code batchUpdate}</li>
 *  <li>{@code execute}（字符串 SQL 重载，非 CallableStatementCreator 回调）</li>
 * </ul>
 *
 * <p>排除（避免误标非 SQL 语义）：</p>
 * <ul>
 *  <li>{@code execute(CallableStatementCreator)} / {@code execute(ConnectionCallback)} 等回调重载 →
 *      由调用方在定位器层按实参类型二次判定（本检测器仅做方法名 O(1) 判定）</li>
 * </ul>
 *
 * <p>与既有 {@code CustomDaoHqlLocator}（{@code findByQuery}/{@code executeHql}）、HQL {@code createQuery} 互不冲突：
 * 方法名集合天然区分。</p>
 */
public final class JdbcTemplateMethodDetector {

    /** Kotlin object 单例兼容入口。 */
    public static final JdbcTemplateMethodDetector INSTANCE = new JdbcTemplateMethodDetector();

    private JdbcTemplateMethodDetector() {
    }

    /**
     * JdbcTemplate / NamedParameterJdbcTemplate / 团队自研 DB 工具层（如 DbHelper）的字符串 SQL 方法名集合。
     *
     * <p>大小写不敏感（Spring 方法名全小写，但用户可能写扩展方法）。
     * 除 Spring 原生方法外，含自研工具层常见的 {@code delete}/{@code insert}（Spring JdbcTemplate 无这两个
     * 方法，但团队 DAO 常用）——只要<b>第 1 实参为字符串 SQL</b>，方法名在此集合即预览候选。</p>
     *
     * <p>注意：第 1 实参约束在定位器层保证（非字符串实参 → 不聚合 → 不预览）；本集合仅做 O(1) 方法名判定。</p>
     */
    public static final Set<String> JDBC_TEMPLATE_METHODS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            // query 族
            "query",
            "queryForObject",
            "queryForList",
            "queryForMap",
            "queryForRowSet",
            "queryForLong",
            "queryForInt",
            "queryForStream",
            // 更新 / 删除 / 插入
            "update",
            "batchUpdate",
            "delete",
            "insert",
            // 通用执行
            "execute"
    )));

    /**
     * 自研 DAO / 团队框架层常见的「字符串 SQL 实参」方法名集合（T-DaoSql-01）。
     *
     * <p>对齐 MP BaseMapper/IService select 族命名与用户自研框架（{@code beanCruder.selectList(Class, sql, ...)}
     * / {@code beanQuery.selectListPagination(Class, sql, paramMap, index, size)}）真实形态。</p>
     *
     * <p>误标防线（三层）：① 该族方法名的 MP 形态实参是 Wrapper/实体/集合（无字符串形态实参），
     * 被 {@link cn.song.mybatisplus.visualizer.psi.PreviewReceiverResolver#hasStringLikeArg} 守卫排除；
     * ② 家族排除制守卫（CUSTOM_DAO/HIBERNATE/JPA 家族各有专属 provider）；
     * ③ 点击时 {@code JdbcTemplateSqlPreviewService.isLikelySql} 拦截非 SQL 字符串。</p>
     */
    public static final Set<String> DAO_STRING_SQL_METHODS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "selectList",
            "selectOne",
            "selectCount",
            "selectPage",
            "selectMaps",
            "selectObjs",
            "selectListPagination"
    )));

    /**
     * 判定方法名引用是否为 JdbcTemplate 模板方法候选。
     *
     * @param referenceName 方法名（如 {@code queryForList}、{@code update}、{@code delete}）
     * @return true = 候选；false = 非候选
     */
    public static boolean isJdbcTemplateMethodReference(String referenceName) {
        if (referenceName == null) {
            return false;
        }
        return JDBC_TEMPLATE_METHODS.contains(referenceName);
    }

    /**
     * 判定方法名是否为「字符串 SQL 调用」候选（T-DaoSql-01）：Spring JDBC 模板方法名
     * ∪ 自研 DAO select 族。gutter 快速路径 / 定位器 / action 统一入口。
     *
     * @param referenceName 方法名
     * @return true = 候选；false = 非候选
     */
    public static boolean isStringSqlMethodCandidate(String referenceName) {
        if (referenceName == null) {
            return false;
        }
        return JDBC_TEMPLATE_METHODS.contains(referenceName) || DAO_STRING_SQL_METHODS.contains(referenceName);
    }
}
