package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.psi.PsiAnnotation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JPA 命名查询信息（@NamedQuery / @NamedQueries 产物）。
 *
 * @param name       查询名（如 "UserEntity.findByUsernameNamed"）
 * @param query      JPQL 查询字符串
 * @param annotation 该命名查询所在的 @NamedQuery 注解（用于"光标下的那一条"定位 / 反查实体类）
 */
public final class NamedQueryInfo {
    /** 查询名（如 "UserEntity.findByUsernameNamed"）。 */
    private final @NotNull String name;
    /** JPQL 查询字符串。 */
    private final @NotNull String query;
    /** 该命名查询所在的 @NamedQuery 注解（用于"光标下的那一条"定位 / 反查实体类）。 */
    private final @Nullable PsiAnnotation annotation;

    public NamedQueryInfo(@NotNull String name, @NotNull String query) {
        this(name, query, null);
    }

    public NamedQueryInfo(@NotNull String name, @NotNull String query, @Nullable PsiAnnotation annotation) {
        this.name = name;
        this.query = query;
        this.annotation = annotation;
    }

    public @NotNull String getName() { return name; }
    public @NotNull String name() { return name; }
    public @NotNull String getQuery() { return query; }
    public @NotNull String query() { return query; }
    public @Nullable PsiAnnotation getAnnotation() { return annotation; }
    public @Nullable PsiAnnotation annotation() { return annotation; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NamedQueryInfo)) return false;
        NamedQueryInfo that = (NamedQueryInfo) o;
        return java.util.Objects.equals(this.name, that.name) && java.util.Objects.equals(this.query, that.query) && java.util.Objects.equals(this.annotation, that.annotation);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, query, annotation);
    }

    @Override
    public String toString() {
        return "NamedQueryInfo[" + "name=" + name + ", " + "query=" + query + ", " + "annotation=" + annotation + "]";
    }
}
