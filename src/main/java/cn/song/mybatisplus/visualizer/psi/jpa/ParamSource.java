package cn.song.mybatisplus.visualizer.psi.jpa;

/**
 * 自定义 DAO 方法调用中参数传递方式枚举。
 *
 * 用于描述 HQL 之后的参数列表组织形式：
 *  - NAME_VALUE_PAIRS：("name1", v1, "name2", v2) 交替传递
 *  - NAME_VALUE_ARRAYS：new String[]{"name1","name2"} + new Object[]{v1, v2} 双数组传递（老式 DAO）
 *  - MAP：单 Map 参数包含所有键值
 *  - POSITIONAL：仅传值，按位置顺序
 *  - NONE：无额外参数
 */
public enum ParamSource {
    /** ("name1", v1, "name2", v2) 交替传递 */
    NAME_VALUE_PAIRS,
    /** new String[]{"name1","name2"} + new Object[]{v1, v2} 双数组传递（老式 DAO） */
    NAME_VALUE_ARRAYS,
    /** 单 Map 参数包含所有键值 */
    MAP,
    /** 仅传值，按位置顺序 */
    POSITIONAL,
    /** 无额外参数 */
    NONE
}
