package cn.song.mybatisplus.visualizer.reverse;

/**
 * 反向解析错误。
 *
 * @param offset 错误偏移量
 * @param key    bundle 文案 key（[SqlReverseParser.KEY_*] 常量，UI 层翻译）
 * @param detail 错误详情（结构名/语法说明；含行号的定位信息由 UI 层按 offset 补充）
 */
public class SqlReverseException extends RuntimeException {
    private final int offset;
    private final String key;
    private final String detail;

    public SqlReverseException(int offset, String key, String detail) {
        super(key + ": " + detail);
        this.offset = offset;
        this.key = key;
        this.detail = detail;
    }

    public int getOffset() {
        return offset;
    }

    public String getKey() {
        return key;
    }

    public String getDetail() {
        return detail;
    }
}
