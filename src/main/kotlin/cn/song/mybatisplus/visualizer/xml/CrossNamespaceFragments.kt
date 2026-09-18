package cn.song.mybatisplus.visualizer.xml

/**
 * 跨命名空间 include 片段解析结果（T-IC-02）：由 psi 层
 * [cn.song.mybatisplus.visualizer.psi.XmlCrossNsFragmentResolver] 产出，交 [XmlIncludeExpander] 合并展开。
 *
 * - [fragments]：全限定 refid（`namespace.id`）→ 片段内部 XML 文本；
 * - [failures]：全限定 refid → 解析失败原因（未找到目标 namespace / 片段不存在 / 多文件重复声明），
 *   展开期据此在错误文案中给出具体原因（无此条目则沿用改动前的降级文案）。
 *
 * 空表（[EMPTY]）即「不做跨命名空间解析」：纯文本链（无 project）与注解 `<script>` 链传空表，
 * 行为与 T-IncludeCrossNs 改动前逐字节一致。
 */
data class CrossNamespaceFragments(
    val fragments: Map<String, String> = emptyMap(),
    val failures: Map<String, String> = emptyMap(),
) {
    companion object {
        val EMPTY = CrossNamespaceFragments()
    }
}
