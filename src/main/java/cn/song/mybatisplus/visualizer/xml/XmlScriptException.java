package cn.song.mybatisplus.visualizer.xml;

/** XML 脚本构建错误（未知标签 / choose 多 otherwise / 缺失属性）。 */
public class XmlScriptException extends RuntimeException {

    public XmlScriptException(String message) {
        super(message);
    }
}
