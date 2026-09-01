package cn.song.mybatisplus.visualizer.reverse;

/**
 * SQL 反向转换的 Wrapper 形态配置（T-M36.3 + T-M36.6 Wrappers 工厂形态）。
 *
 * 设置页「默认 Wrapper 形态」驱动生成结果。
 */
public enum ReverseWrapperFormat {
    /** 自动：有实体 → LambdaQueryWrapper；无实体 → QueryWrapper。 */
    AUTO("AUTO"),
    /** 恒字符串列名 QueryWrapper（跳过实体查找）。 */
    QUERY("QUERY"),
    /** 有实体 → LambdaQueryWrapper；无实体回落 QueryWrapper。 */
    LAMBDA("LAMBDA"),
    /** MP 官方静态工厂形态：有实体 → Wrappers.&lt;E&gt;lambdaQuery()；无实体 → Wrappers.&lt;E&gt;query()。 */
    WRAPPERS("WRAPPERS"),
    /** 有实体 → MPJLambdaWrapper；无实体回落 QueryWrapper。 */
    MPJ_LAMBDA("MPJ_LAMBDA");

    private final String persistedValue;

    ReverseWrapperFormat(String persistedValue) {
        this.persistedValue = persistedValue;
    }

    /** 设置持久化值。 */
    public String getPersistedValue() {
        return persistedValue;
    }

    /** 从设置持久化值解析形态（未知值回落 AUTO）。 */
    public static ReverseWrapperFormat fromPersisted(String value) {
        if (value != null) {
            for (ReverseWrapperFormat f : values()) {
                if (f.persistedValue.equals(value)) return f;
            }
        }
        return AUTO;
    }
}
