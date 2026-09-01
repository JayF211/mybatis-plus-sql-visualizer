package cn.song.mybatisplus.visualizer.xml;

/**
 * 解析结果（T-M24-02）：成功返回 AST，失败返回错误信息。
 */
public sealed interface ParseResult
        permits ParseResult.Ok, ParseResult.Err {

    /** 解析成功。 */
    record Ok(OgnlExpr expr) implements ParseResult {}

    /** 解析失败。 */
    record Err(String message) implements ParseResult {}
}
