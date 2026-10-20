package cn.song.mybatisplus.visualizer.model;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * WrapperFlagOutcome.kt 顶层函数的 Java 承载（端口约定：Kotlin 顶层函数 → {@code XxxKt}）。
 */
public final class WrapperFlagOutcomeKt {

    private WrapperFlagOutcomeKt() {
    }

    /**
     * 由 {@code ConditionToggle.flagSource} 派生探镜结果；null = 不可证明是布尔重载（不标注）。
     *
     * <p>只按字面值 {@code "true"/"false"} 精确匹配——四类生产者对字面量的产出即这两个小写串
     * （ExpressionEvaluator 布尔字面量 {@code value.toString()}，恒小写）；其余非 null 一律按运行时占位符。
     */
    public static @Nullable WrapperFlagOutcome wrapperFlagOutcome(@Nullable String flagSource) {
        if (flagSource == null) {
            return null;
        }
        return switch (flagSource) {
            case "true" -> WrapperFlagOutcome.ALWAYS_TRUE;
            case "false" -> WrapperFlagOutcome.ALWAYS_FALSE;
            default -> WrapperFlagOutcome.DEFAULT_TRUE;
        };
    }

    /** 探镜标注文案（行尾短句）。 */
    public static @NotNull String wrapperFlagOutcomeText(@NotNull WrapperFlagOutcome outcome) {
        return switch (outcome) {
            case ALWAYS_TRUE -> MpSqlVisualizerBundle.message("panel.wrapper.outcome.alwaysTrue");
            case ALWAYS_FALSE -> MpSqlVisualizerBundle.message("panel.wrapper.outcome.alwaysFalse");
            case DEFAULT_TRUE -> MpSqlVisualizerBundle.message("panel.wrapper.outcome.defaultTrue");
        };
    }

    /** 探镜标注 tooltip（带出布尔标志原文，与 {@link #wrapperFlagOutcomeText} 同渲染面）。 */
    public static @NotNull String wrapperFlagOutcomeTooltip(@NotNull String flagSource,
                                                            @NotNull WrapperFlagOutcome outcome) {
        return MpSqlVisualizerBundle.message("panel.wrapper.outcome.tooltip", flagSource,
                wrapperFlagOutcomeText(outcome));
    }
}
