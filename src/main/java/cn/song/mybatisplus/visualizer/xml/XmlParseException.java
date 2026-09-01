package cn.song.mybatisplus.visualizer.xml;

/** 解析失败（非良构 XML / 根元素不是 mapper）。 */
public class XmlParseException extends RuntimeException {

    public XmlParseException(String message) {
        super(message);
    }
}
