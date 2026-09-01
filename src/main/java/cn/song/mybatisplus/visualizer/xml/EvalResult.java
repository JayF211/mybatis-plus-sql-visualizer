package cn.song.mybatisplus.visualizer.xml;

/**
 * 求值结果（T-M24-02）：成功返回值，失败返回错误信息。
 */
public sealed interface EvalResult
        permits EvalResult.Ok, EvalResult.Err {

    /** 求值成功。 */
    record Ok(Object value) implements EvalResult {}

    /** 求值失败。 */
    record Err(String message) implements EvalResult {}
}
