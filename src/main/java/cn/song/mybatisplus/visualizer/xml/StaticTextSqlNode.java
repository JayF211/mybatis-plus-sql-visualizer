package cn.song.mybatisplus.visualizer.xml;

/** 静态文本（无 ${}），原样输出。 */
public final class StaticTextSqlNode implements SqlNode {

    private final String text;

    public StaticTextSqlNode(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    @Override
    public boolean apply(DynamicContext context) {
        context.appendSql(text);
        return true;
    }
}
