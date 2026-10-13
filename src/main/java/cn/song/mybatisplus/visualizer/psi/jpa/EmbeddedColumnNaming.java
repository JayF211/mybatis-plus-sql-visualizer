package cn.song.mybatisplus.visualizer.psi.jpa;

/**
 * {@code @Embedded} 展平列的命名约定（T-EmbeddedColPrefix）。
 *
 * <p>嵌入对象没有自己的表，其字段直接落在宿主表的列上，但<b>列名怎么拼</b>取决于工程的命名策略：
 * <ul>
 *   <li>{@link #NONE}：JPA/Hibernate 默认（{@code ImplicitNamingStrategyJpaCompliantImpl}）——列名就是
 *       内层字段名（{@code qty.baseQty} → {@code base_qty}）；</li>
 *   <li>{@link #COMPONENT_PATH}：Hibernate {@code ImplicitNamingStrategyComponentPathImpl}（配置关键字
 *       {@code component-path}，Hibernate 4 的 {@code DefaultComponentSafeNamingStrategy} 同源）——把属性路径
 *       各段拼进列名（{@code qty.baseQty} → {@code qty_base_qty}）。</li>
 * </ul></p>
 *
 * <p>语义经 hibernate-core 源码核对（5.6.4 {@code ImplicitNamingStrategyComponentPathImpl.transformAttributePath}）：
 * 隐式名用<b>原始属性名</b>按 {@code _} 连接（{@code qty} + {@code _} + {@code baseQty}），下划线化由
 * PhysicalNamingStrategy 完成，故最终形态等价于「各段 snake 后以 {@code _} 连接」。</p>
 */
public enum EmbeddedColumnNaming {
    NONE,
    COMPONENT_PATH
}
