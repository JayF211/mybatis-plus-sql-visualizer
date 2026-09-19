// T-SyncK2JMaster309 自 master sql/DialectInference.kt 移植
package cn.song.mybatisplus.visualizer.sql;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-DialectAutoInfer：智能推断纯逻辑层（优先级合并 + 结果模型）。
 *
 * <p>优先级（强→弱）：
 * <ol>
 *  <li>agent 最近连接 URL（运行中应用的真实数据源，最能代表「当前执行的 SQL 跑在哪个库」）</li>
 *  <li>IDE 数据源（DatabaseView 配置的连接 URL）</li>
 *  <li>未命中 → {@link Result#dialectName()} 为 null，消费方回退 MySQL（与既有 `byName(null)` 语义一致）</li>
 * </ol>
 *
 * <p>平台查询（agent 会话状态/数据源列表）由调用方注入 URL 候选，本层只做合并与映射——纯函数可单测。
 */
public final class DialectInference {

    private DialectInference() {}

    /**
     * 推断结果。
     *
     * @param dialectName 命中（DialectRegistry 命名空间）或 null=未命中
     * @param basis       展示用依据文案（i18n 前的原始键由 UI 层组装，此处为参数原文）
     */
    public record Result(
            /** 命中的方言名（DialectRegistry 命名空间）；null = 未命中。 */
            @Nullable String dialectName,
            /** 展示用依据文案（i18n 前的原始键由 UI 层组装，此处为参数原文）。 */
            @Nullable String basis
    ) {
    }

    /**
     * 按 URL 候选优先级推断。
     *
     * @param agentUrl      agent 最近一次捕获的连接 URL（null=增强未开/未捕获/旧版 agent）
     * @param dataSourceUrl IDE 数据源 URL（null=无数据源/探测失败）
     */
    @NotNull
    public static Result infer(@Nullable String agentUrl, @Nullable String dataSourceUrl) {
        String byAgent = UrlSubprotocolDialects.dialectName(agentUrl);
        if (byAgent != null) return new Result(byAgent, agentUrl);
        String byDataSource = UrlSubprotocolDialects.dialectName(dataSourceUrl);
        if (byDataSource != null) return new Result(byDataSource, dataSourceUrl);
        return new Result(null, null);
    }

    /** 便捷重载：单 URL（如仅有数据源场景）。 */
    @NotNull
    public static Result infer(@Nullable String dataSourceUrl) {
        return infer(null, dataSourceUrl);
    }
}
