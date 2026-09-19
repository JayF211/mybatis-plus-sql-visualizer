// T-SyncK2JMaster309 自 execute/ExecDocAutosaveGate.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * T-PreviewFast P4：执行文件自动落盘的「程序性写入」闸门。
 *
 * <p>背景（真机日志 20260917）：T-ExecDocSync2 F5 的 1.5s 防抖自动落盘是为<b>用户手改控制台</b>兜底
 * （插件拦不到的未保存源），但它监听的是文档变更本身——插件的程序性写入（预览回填渲染、
 * 复用链同步）同样触发一次落盘。计数实证：每次预览多出一次 {@code exec doc autosaved}（8/14 次预览），
 * 纯属自己与自己抢：内容刚由写入方落盘，1.5 秒后再写一遍。</p>
 *
 * <p>用法：写入方在<b>程序性写入的调用栈内</b>开闸（{@link #suppressDuring}），闸门开着时自动落盘监听直接
 * 跳过。文档变更事件在写入线程内同步派发，故计数式闸门足以覆盖（不依赖时间窗）。用户键入路径
 * 不经闸门，语义不变。</p>
 */
public final class ExecDocAutosaveGate {

    private static final AtomicInteger depth = new AtomicInteger(0);

    public static final ExecDocAutosaveGate INSTANCE = new ExecDocAutosaveGate();
    private ExecDocAutosaveGate() {}

    /** 当前是否处于程序性写入中（自动落盘监听用）。 */
    public static boolean isSuppressed() {
        return depth.get() > 0;
    }

    /** 在闸门内执行程序性写入（异常安全，finally 复位）。 */
    public static <T> T suppressDuring(Supplier<T> block) {
        depth.incrementAndGet();
        try {
            return block.get();
        } finally {
            depth.decrementAndGet();
        }
    }

    /** 在闸门内执行程序性写入（无返回值形态，等价 {@code suppressDuring { ... }} 的 Unit 块）。 */
    public static void suppressDuring(Runnable block) {
        depth.incrementAndGet();
        try {
            block.run();
        } finally {
            depth.decrementAndGet();
        }
    }
}
