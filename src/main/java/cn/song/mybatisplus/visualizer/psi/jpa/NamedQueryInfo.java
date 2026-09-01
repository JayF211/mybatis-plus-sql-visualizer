package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;

/**
 * JPA 命名查询信息（@NamedQuery / @NamedQueries 产物）。
 *
 * @param name   查询名（如 "UserEntity.findByUsernameNamed"）
 * @param query  JPQL 查询字符串
 */
public final class NamedQueryInfo {
    /** 查询名（如 "UserEntity.findByUsernameNamed"）。 */
    private final @NotNull String name;
    /** JPQL 查询字符串。 */
    private final @NotNull String query;

    public NamedQueryInfo(@NotNull String name, @NotNull String query) {
        this.name = name;
        this.query = query;
    }

    public @NotNull String getName() { return name; }
    public @NotNull String name() { return name; }
    public @NotNull String getQuery() { return query; }
    public @NotNull String query() { return query; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NamedQueryInfo)) return false;
        NamedQueryInfo that = (NamedQueryInfo) o;
        return java.util.Objects.equals(this.name, that.name) && java.util.Objects.equals(this.query, that.query);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, query);
    }

    @Override
    public String toString() {
        return "NamedQueryInfo[" + "name=" + name + ", " + "query=" + query + "]";
    }
}
