package cn.song.mybatisplus.visualizer.model;

/**
 * 集合关联类型（T-J7/T-J18）。
 */
public enum CollectionKind {
    /** @OneToMany：独立 SELECT（默认 LAZY），单向 + @JoinColumn 时外键在目标表。 */
    ONE_TO_MANY,

    /** @ManyToMany：经中间表关联。 */
    MANY_TO_MANY,

    /** @ElementCollection：元素集合表（独立第二查询，N+1 高发面）。 */
    ELEMENT_COLLECTION,
}
