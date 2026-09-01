package cn.song.mybatisplus.visualizer.model;

/**
 * 字段角色（T-M23.3 角色分组排序键）：PRIMARY(0) &lt; BUSINESS(1) &lt; AUDIT(2) &lt; SYSTEM(3)，
 * 组内保持声明顺序（稳定排序）——`id, 业务字段…, create_time, update_time, deleted`。
 */
public enum ColumnRole {
    /** @TableId 主键——置顶。 */
    PRIMARY,
    /** 普通业务字段——居中。 */
    BUSINESS,
    /** @TableField(fill = ...) 自动填充字段——沉底组 1（审计字段惯例）。 */
    AUDIT,
    /** @TableLogic / @Version——沉底组 2。 */
    SYSTEM
}
