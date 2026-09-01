package cn.song.mybatisplus.visualizer.xml;

import java.util.List;

/**
 * mapper 文件解析结果（语句清单 + 解析期错误）。
 *
 * @param namespace 命名空间（可能为 null）
 * @param statements 语句清单
 * @param errors 解析期错误
 */
public record XmlMapperModel(String namespace, List<XmlStatement> statements, List<String> errors) {
}
