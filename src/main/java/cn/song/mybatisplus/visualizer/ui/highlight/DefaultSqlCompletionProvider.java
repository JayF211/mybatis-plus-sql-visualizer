package cn.song.mybatisplus.visualizer.ui.highlight;

import java.util.List;

/**
 * 方案 A 默认实现：关键字 + 实体上下文。
 */
public final class DefaultSqlCompletionProvider implements SqlCompletionProvider {

    public static final DefaultSqlCompletionProvider INSTANCE = new DefaultSqlCompletionProvider();

    @Override
    public List<String> keywords() {
        return List.copyOf(SqlLexer.KEYWORDS);
    }

    private DefaultSqlCompletionProvider() {
    }
}
