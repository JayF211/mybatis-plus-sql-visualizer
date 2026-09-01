package cn.song.mybatisplus.visualizer.model;

/**
 * LIKE 模式中的 % 位置（对应 MP 的 like / likeLeft / likeRight）。
 */
public enum LikePosition {
    /** 两侧都加 %：`%value%` */
    ANYWHERE,
    /** 左侧加 %：`%value` */
    LEFT,
    /** 右侧加 %：`value%` */
    RIGHT,
    /** 不加 %（notLike 且原样） */
    NONE
}
