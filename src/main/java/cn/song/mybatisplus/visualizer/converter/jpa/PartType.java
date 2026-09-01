package cn.song.mybatisplus.visualizer.converter.jpa;

/**
 * 条件类型枚举（27 种），按匹配优先级排序。
 *
 * 优先级规则：IS_NULL/IS_NOT_NULL 先匹配，SIMPLE_PROPERTY 兜底。
 */
public enum PartType {
    /** BETWEEN ? AND ? */
    BETWEEN,

    /** IS NOT NULL */
    IS_NOT_NULL,

    /** IS NULL */
    IS_NULL,

    /** < ? */
    LESS_THAN,

    /** <= ? */
    LESS_THAN_EQUAL,

    /** > ? */
    GREATER_THAN,

    /** >= ? */
    GREATER_THAN_EQUAL,

    /** < ?（时间/日期语义） */
    BEFORE,

    /** > ?（时间/日期语义） */
    AFTER,

    /** NOT LIKE ? */
    NOT_LIKE,

    /** LIKE ? */
    LIKE,

    /** LIKE ?% */
    STARTING_WITH,

    /** LIKE %? */
    ENDING_WITH,

    /** LIKE %?% */
    CONTAINING,

    /** NOT LIKE %?% */
    NOT_CONTAINING,

    /** IS NOT EMPTY */
    IS_NOT_EMPTY,

    /** IS EMPTY */
    IS_EMPTY,

    /** NOT IN (?) */
    NOT_IN,

    /** IN (?) */
    IN,

    /** 空间近邻（暂不支持，降级为 SIMPLE_PROPERTY） */
    NEAR,

    /** 空间范围内（暂不支持，降级为 SIMPLE_PROPERTY） */
    WITHIN,

    /** REGEXP */
    REGEX,

    /** EXISTS */
    EXISTS,

    /** = true */
    TRUE,

    /** = false */
    FALSE,

    /** != ? */
    NEGATING_SIMPLE_PROPERTY,

    /** = ?（默认/兜底） */
    SIMPLE_PROPERTY
}
