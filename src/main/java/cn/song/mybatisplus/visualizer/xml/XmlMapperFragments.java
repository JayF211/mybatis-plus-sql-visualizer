// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/XmlMapperParser.kt 移植（XmlMapperParser.kt 内并列顶层声明）
package cn.song.mybatisplus.visualizer.xml;

import java.util.Map;

/**
 * mapper 文件轻量片段扫描结果（T-IC-01）：根 namespace + 顶层 {@code <sql id>} 片段内部 XML 文本。
 *
 * @param namespace 根元素 namespace 属性（空串归一为 null）
 * @param fragments 顶层 {@code <sql id>} 片段：id → 片段内部 XML 文本
 */
public record XmlMapperFragments(String namespace, Map<String, String> fragments) {
}
