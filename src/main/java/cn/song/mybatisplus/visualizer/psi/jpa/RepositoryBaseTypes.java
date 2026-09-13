package cn.song.mybatisplus.visualizer.psi.jpa;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Spring Data Repository 基类全限定名统一名单（SD6，M44 其他 ORM 第一批）。
 *
 * <p>单一事实源：JPA / JDBC / R2DBC 三族 Repository 的 receiver 家族判定与实体泛型解析
 * 此前在 DerivedMethodLocator、JpaCrudOperationConverter、QueryByExampleConverter、
 * SpecificationQueryConverter 四处各自维护副本，SD6 对齐时曾出现 JDBC/R2DBC 侧仅靠
 * 「接口名 Repository 后缀」启发式侥幸放行的缺口。本名单按 spring-data 4.1.1 jar javap 实证：
 *
 * <ul>
 *   <li>同步（JDBC/JPA 共用）：Repository / CrudRepository / PagingAndSortingRepository /
 *       ListCrudRepository（3.0 变体）/ ListPagingAndSortingRepository（3.0 变体）；</li>
 *   <li>JPA 专属：JpaRepository / QueryExecutorAdapter；</li>
 *   <li>Reactive（R2DBC 侧，SD4/SD6）：ReactiveCrudRepository / ReactiveSortingRepository
 *       （org.springframework.data.repository.reactive 包）、R2dbcRepository
 *       （org.springframework.data.r2dbc.repository 包，javap 证实自身零方法、纯聚合接口）。</li>
 * </ul>
 *
 * <p>匹配语义（各消费方共用）：canonicalText 前缀精确匹配（真实 IDE 解析态）+
 * {@link #SIMPLE_NAMES} 简名兜底（LightPlatform 测试 / 跨模块未解析态）。
 */
public final class RepositoryBaseTypes {

    /** Spring Data Repository 基类全限定名（superType 匹配用）。 */
    public static final Set<String> FQNS = new LinkedHashSet<>(Arrays.asList(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.PagingAndSortingRepository",
            "org.springframework.data.repository.ListCrudRepository",
            "org.springframework.data.repository.ListPagingAndSortingRepository",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.jpa.repository.QueryExecutorAdapter",
            // SD6：Reactive / R2DBC 族（javap org.springframework.data.r2dbc.repository.R2dbcRepository：
            // extends ReactiveCrudRepository + ReactiveSortingRepository，两基类在 repository.reactive 包）
            "org.springframework.data.repository.reactive.ReactiveCrudRepository",
            "org.springframework.data.repository.reactive.ReactiveSortingRepository",
            "org.springframework.data.r2dbc.repository.R2dbcRepository"));

    /** 基类简单名（canonicalText 前缀匹配失败时的兜底集合）。 */
    public static final Set<String> SIMPLE_NAMES;

    static {
        Set<String> names = new LinkedHashSet<>();
        for (String fqn : FQNS) {
            int dot = fqn.lastIndexOf('.');
            names.add(dot >= 0 ? fqn.substring(dot + 1) : fqn);
        }
        SIMPLE_NAMES = names;
    }

    private RepositoryBaseTypes() {
    }
}
