// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ParamKind.kt 移植（ParamKind.kt 内并列顶层声明）
// T-SyncK2JMaster187 增量（O-78 T-ForeachItemRender）：新增 elementKind 组件
package cn.song.mybatisplus.visualizer.xml;

/**
 * 参数类型信息（T-PT-01）：类别 + nullable 标记。
 *
 * @param kind        类型类别（映射不到为 {@link ParamKind#UNKNOWN}）
 * @param nullable    是否可空（PSI 原语类型视为非空，其余保守视为可空）
 * @param elementKind 集合参数的元素类别（O-78 T-ForeachItemRender）：{@code List<Integer>} → NUMBER——
 *                    供 foreach item 占位键（{@code __frch_<item>_<n>}）派生引号/类别；非集合、数组、
 *                    裸集合（无泛型实参）或实参类别映射不到时为 null（宁缺勿错，调用方按 UNKNOWN 兜底）
 */
public record ParamTypeInfo(ParamKind kind, boolean nullable, ParamKind elementKind) {

    /** Kotlin 默认参数等价重载：{@code nullable} 缺省为 {@code true}、{@code elementKind} 缺省为 {@code null}。 */
    public ParamTypeInfo(ParamKind kind) {
        this(kind, true, null);
    }

    /** Kotlin 默认参数等价重载：{@code elementKind} 缺省为 {@code null}。 */
    public ParamTypeInfo(ParamKind kind, boolean nullable) {
        this(kind, nullable, null);
    }
}
