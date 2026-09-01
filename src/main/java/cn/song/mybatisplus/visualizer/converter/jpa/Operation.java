package cn.song.mybatisplus.visualizer.converter.jpa;

/**
 * 操作类型枚举。
 */
public enum Operation {
    /** 查询（find/get/read...） */
    FIND,
    /** 计数（count） */
    COUNT,
    /** 存在（exists） */
    EXISTS,
    /** 删除（delete/remove） */
    DELETE
}
