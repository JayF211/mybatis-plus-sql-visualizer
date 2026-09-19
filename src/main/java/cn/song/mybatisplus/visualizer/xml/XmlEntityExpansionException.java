package cn.song.mybatisplus.visualizer.xml;

/**
 * 内联 DTD 子集膨胀防护拒绝（T-XDH-03）：实体声明数 / 引用嵌套深度 / 展开估算任一超限。
 *
 * <p>master 的 {@code class XmlEntityExpansionException}（与 {@code SecureXml.kt} 同文件）的 Java 承载——
 * 按本仓「一个公开类一个文件」惯例独立成件。</p>
 */
public class XmlEntityExpansionException extends RuntimeException {

    public XmlEntityExpansionException(String message) {
        super(message);
    }
}
