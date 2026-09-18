package cn.song.mybatisplus.visualizer.execute

import java.util.concurrent.atomic.AtomicInteger

/**
 * T-PreviewFast P4：执行文件自动落盘的「程序性写入」闸门。
 *
 * 背景（真机日志 20260917）：T-ExecDocSync2 F5 的 1.5s 防抖自动落盘是为**用户手改控制台**兜底
 * （插件拦不到的未保存源），但它监听的是文档变更本身——插件的程序性写入（预览回填渲染、
 * 复用链同步）同样触发一次落盘。计数实证：每次预览多出一次 `exec doc autosaved`（8/14 次预览），
 * 纯属自己与自己抢：内容刚由写入方落盘，1.5 秒后再写一遍。
 *
 * 用法：写入方在**程序性写入的调用栈内**开闸（[suppressDuring]），闸门开着时自动落盘监听直接
 * 跳过。文档变更事件在写入线程内同步派发，故计数式闸门足以覆盖（不依赖时间窗）。用户键入路径
 * 不经闸门，语义不变。
 */
object ExecDocAutosaveGate {

    private val depth = AtomicInteger(0)

    /** 当前是否处于程序性写入中（自动落盘监听用）。 */
    fun isSuppressed(): Boolean = depth.get() > 0

    /** 在闸门内执行程序性写入（异常安全，finally 复位）。 */
    fun <T> suppressDuring(block: () -> T): T {
        depth.incrementAndGet()
        try {
            return block()
        } finally {
            depth.decrementAndGet()
        }
    }
}
