package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.diagnostic.Logger

/**
 * T-SqlLogPerf：log 包热路径耗时探针（阈值触发 INFO）。
 *
 * 背景（20261006-1020 开发计划 §阶段 0）：用户日常 IDE 实测并发压测下 8708 条历史文件加载
 * 15~16.6s、实时列表灌入 65 条/秒时面板动作等待 EDT 358~1110ms——但热路径零耗时打点，
 * 延迟无法从 idea.log 直接归因。本探针给 readFromFile/解析/列表填充/refreshList 等
 * 疑似瓶颈段统一打点；**阈值触发**（单次低于 [THRESHOLD_MS] 毫秒零输出）保证常态零噪音，
 * 风暴期才在 idea.log 留痕，事后可 `[T-SqlLogPerf]` 检索对账。
 *
 * 探针是**转正**的观测设施（不是临时调试代码）：供后续性能轮与真机报障复用。
 */
object LogPerfProbe {

    /** internal inline（非 public）才能在 inline 体内直读非公开成员；调用面限本包热路径。 */
    private val LOG = Logger.getInstance(LogPerfProbe::class.java)

    /** 单次耗时达到该毫秒数才输出 INFO（低于阈值的调用零成本输出、零日志噪音）。 */
    internal const val THRESHOLD_MS = 100L

    /**
     * 包裹 [block] 计时；达到 [THRESHOLD_MS] 输出 `[T-SqlLogPerf] name …ms (detail)`。
     * detail 惰性求值——未达阈值时零开销，不触发字符串拼接。
     */
    internal inline fun <T> measure(name: String, detail: () -> String = { "" }, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val costMs = (System.nanoTime() - start) / 1_000_000
        if (costMs >= THRESHOLD_MS) {
            val suffix = detail().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
            LOG.info("[T-SqlLogPerf] $name ${costMs}ms$suffix")
        }
        return result
    }
}
