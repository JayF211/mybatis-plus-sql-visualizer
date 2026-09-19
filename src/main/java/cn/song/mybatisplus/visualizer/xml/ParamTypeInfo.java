// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ParamKind.kt 移植（ParamKind.kt 内并列顶层声明）
package cn.song.mybatisplus.visualizer.xml;

/**
 * 参数类型信息（T-PT-01）：类别 + nullable 标记。
 *
 * @param kind     类型类别（映射不到为 {@link ParamKind#UNKNOWN}）
 * @param nullable 是否可空（PSI 原语类型视为非空，其余保守视为可空）
 */
public record ParamTypeInfo(ParamKind kind, boolean nullable) {

    /** Kotlin 默认参数等价重载：{@code nullable} 缺省为 {@code true}。 */
    public ParamTypeInfo(ParamKind kind) {
        this(kind, true);
    }
}
