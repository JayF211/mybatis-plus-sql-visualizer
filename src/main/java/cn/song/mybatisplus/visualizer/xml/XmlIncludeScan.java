// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/XmlMapperParser.kt 移植（XmlMapperParser.kt 内并列顶层声明）
package cn.song.mybatisplus.visualizer.xml;

import java.util.Set;

/**
 * 当前 mapper 文本的 include 扫描结果（T-IC-02）：根 namespace + 本文件片段 id + 待跨命名空间解析的 refid。
 *
 * @param namespace        根元素 namespace 属性（空串归一为 null）
 * @param localFragmentIds 本文件顶层 {@code <sql id>} 片段 id 集
 * @param dottedRefIds     含 {@code .} 且本文件无同名片段的 include refid 集
 *                         （refid 中的 {@code ${name}} 已按 include 自带 property 静态替换，与展开期同语义）
 */
public record XmlIncludeScan(String namespace, Set<String> localFragmentIds, Set<String> dottedRefIds) {
}
