// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/CrossNamespaceFragments.kt 移植
package cn.song.mybatisplus.visualizer.xml;

import java.util.Map;

/**
 * 跨命名空间 include 片段解析结果（T-IC-02）：由 psi 层
 * {@code cn.song.mybatisplus.visualizer.psi.XmlCrossNsFragmentResolver} 产出，交 {@link XmlIncludeExpander} 合并展开。
 *
 * <ul>
 *   <li>{@code fragments}：全限定 refid（{@code namespace.id}）→ 片段内部 XML 文本；</li>
 *   <li>{@code failures}：全限定 refid → 解析失败原因（未找到目标 namespace / 片段不存在 / 多文件重复声明），
 *       展开期据此在错误文案中给出具体原因（无此条目则沿用改动前的降级文案）。</li>
 * </ul>
 *
 * 空表（{@link #EMPTY}）即「不做跨命名空间解析」：纯文本链（无 project）与注解 {@code <script>} 链传空表，
 * 行为与 T-IncludeCrossNs 改动前逐字节一致。
 *
 * @param fragments 全限定 refid（{@code namespace.id}）→ 片段内部 XML 文本
 * @param failures  全限定 refid → 解析失败原因（未找到目标 namespace / 片段不存在 / 多文件重复声明）
 */
public record CrossNamespaceFragments(Map<String, String> fragments, Map<String, String> failures) {

    /** 伴生对象 EMPTY（Kotlin {@code val EMPTY = CrossNamespaceFragments()}）：不做跨命名空间解析的空表。 */
    public static final CrossNamespaceFragments EMPTY = new CrossNamespaceFragments();

    /** Kotlin 默认参数等价重载：两表均 {@code emptyMap()}。 */
    public CrossNamespaceFragments() {
        this(Map.of(), Map.of());
    }
}
