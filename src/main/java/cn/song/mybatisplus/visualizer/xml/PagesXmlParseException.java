package cn.song.mybatisplus.visualizer.xml;

/** 非 <pages> 根元素或解析失败。 */
public class PagesXmlParseException extends RuntimeException {

    public PagesXmlParseException(String message) {
        super(message);
    }
}
