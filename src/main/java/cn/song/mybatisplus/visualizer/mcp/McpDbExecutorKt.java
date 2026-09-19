// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpDbExecutor.kt 顶层函数段移植
package cn.song.mybatisplus.visualizer.mcp;

import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * warnings 取尽内核（T-NoticeChannel 纯逻辑层）：{@link WarningSurface} 每项 = (getAllWarnings 调用,
 * clearWarnings 调用) 二元组，反射接线在 {@link McpDbExecutor#warningSurfaces}——本核不碰反射
 * 与平台类型，裸 JUnit 可直测。顶层函数（非 object 成员）：测试加载本文件门面类时不必
 * 触发 {@link McpDbExecutor} 的 Logger 初始化。
 *
 * 全程吞异常降级：个别驱动对 warnings 抛 UnsupportedOperationException 时降级为不产出
 * 该面消息，绝不连坐查询结果；clearWarnings 失败同样不丢已取到的消息（下一条语句执行前的
 * pre-drain 兜底，DBX postgres.rs 同款两件套）。
 */
public final class McpDbExecutorKt {

    private McpDbExecutorKt() {
    }

    /**
     * warnings 采集面二元组：getAllWarnings 产出原始消息清单（可空），clearWarnings 清空驱动缓冲。
     * Kotlin 侧为 {@code Pair<() -> List<*>?, () -> Unit>}，Java 侧以 record + 函数式接口同构表达。
     */
    public record WarningSurface(
        /** 取 warnings 原始清单（驱动反射接线；失败/为空可返回 null）。 */
        Supplier<List<?>> getAllWarnings,
        /** 清空 warnings（驱动反射接线）。 */
        Runnable clearWarnings
    ) {
    }

    /** warnings 取尽：语句级/连接级各面依次采集（每面采集后立刻 clear），过滤空白行后拼接。 */
    public static List<String> drainWarnings(WarningSurface... surfaces) {
        List<String> out = new ArrayList<>();
        for (WarningSurface surface : surfaces) {
            List<String> messages = new ArrayList<>();
            try {
                List<?> raw = surface.getAllWarnings().get();
                if (raw != null) {
                    for (Object w : raw) {
                        if (w == null) {
                            continue;
                        }
                        // 单条映射失败降级为丢弃该条（Kotlin runCatching.getOrNull 语义）
                        try {
                            messages.add(w instanceof SQLWarning sqlWarning ? sqlWarning.getMessage() : w.toString());
                        } catch (Throwable ignored) {
                            // 映射失败不产出该条
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 该面取 warnings 失败 → 降级为不产出该面消息（不连坐其他面）
            }
            try {
                surface.clearWarnings().run();
            } catch (Throwable ignored) {
                // clear 失败不丢已取到的消息
            }
            for (String message : messages) {
                if (!message.isBlank()) {
                    out.add(message);
                }
            }
        }
        return out;
    }
}
