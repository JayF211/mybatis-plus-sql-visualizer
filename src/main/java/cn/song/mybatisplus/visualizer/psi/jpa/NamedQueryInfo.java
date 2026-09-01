package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;

/**
 * JPA 命名查询信息（@NamedQuery / @NamedQueries 产物）。
 *
 * @param name   查询名（如 "UserEntity.findByUsernameNamed"）
 * @param query  JPQL 查询字符串
 */
public record NamedQueryInfo(
        /** 查询名（如 "UserEntity.findByUsernameNamed"）。 */
        @NotNull String name,
        /** JPQL 查询字符串。 */
        @NotNull String query
) {
}
