package cn.song.mybatisplus.visualizer.reverse;

/**
 * 形态分发（纯函数，可单测）：配置 + 是否有可用实体 → 最终生成形态。
 * LAMBDA / MPJ_LAMBDA / WRAPPERS 无实体时回落 QUERY（保持「任何 SQL 都能转换」的零降级体验）。
 */
public final class WrapperFormatKt {
    private WrapperFormatKt() {
    }

    /** 根据设置形态与是否有可用实体，解析最终生成的 Wrapper 形态。 */
    public static GeneratedWrapper resolveWrapperFormat(ReverseWrapperFormat format, boolean hasEntity) {
        return switch (format) {
            case AUTO -> hasEntity ? GeneratedWrapper.LAMBDA : GeneratedWrapper.QUERY;
            case QUERY -> GeneratedWrapper.QUERY;
            case LAMBDA -> hasEntity ? GeneratedWrapper.LAMBDA : GeneratedWrapper.QUERY;
            case WRAPPERS -> hasEntity ? GeneratedWrapper.WRAPPERS_LAMBDA : GeneratedWrapper.WRAPPERS_QUERY;
            case MPJ_LAMBDA -> hasEntity ? GeneratedWrapper.MPJ_LAMBDA : GeneratedWrapper.QUERY;
        };
    }
}
