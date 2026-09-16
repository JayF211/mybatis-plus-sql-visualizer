package cn.song.mybatisplus.visualizer.feedback;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 待发报告调度器（T-FeedbackBatch，2026-09-14 用户定案「不能被轻易限流」）。
 *
 * <p>背景：需求池对 {@code POST /feedback/posts} 的限流是 <b>5 次/小时/IP</b>（服务端恒定规则，插件左右不了）。
 * 用户在 IDE 错误列表里一次勾选十几条时，逐条提交必然撞 429——本条链路负责在<b>用户确认之后</b>
 * 把超出一轮额度的部分排队，按 12 分钟一发的节奏（1 小时 5 发）错峰送出，队列放内存不落盘。
 *
 * <p>语义约定（避免「静默替用户发东西」）：
 * <ul>
 * <li>入队发生在用户点击提交之后，内容已在该对话框里预览确认过；</li>
 * <li>首发成功才排后续（首发失败＝额度或网络有问题，继续排只会连环失败）；</li>
 * <li>排队不阻塞窗口，关窗仍会照发；应用退出即丢弃（不落盘、不重启后续发）。</li>
 * <li>T-PerfSweep2-05：发送期间新入队的批次并入队尾顺延补发（此前被静默丢弃）——内容同样
 *     已在对话框里确认过，只是排队位置靠后，红线不变。</li>
 * </ul>
 *
 * <p>调度形态（T-PerfSweep2-05）：单拍链式——每拍发一条，队列非空再 schedule 下一拍。
 * 此前是 drain 循环内 {@code Thread.sleep(720s)} 逐条间隔：占住唯一调度线程 12 分钟×N，
 * 且 drain 期间 {@link #enqueue} 被静默拒收。链式化后线程在两拍之间归还池，闲时零唤醒不变。
 */
@Service(Service.Level.APP)
public final class PendingReportScheduler {

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sql-visualizer-feedback-queue");
                thread.setDaemon(true);
                return thread;
            });

    /** 待发队列：{@link #enqueue} 并入、{@link #tick} 逐条消费。 */
    private final ArrayDeque<FeedbackApi.Submission> pending = new ArrayDeque<>();

    /**
     * 在途定时任务，唯一调度权凭证：{@code scheduled != null && !isDone} ⟺ 有（且仅有一个）
     * 在途或待触发的拍拥有队列。{@link #enqueue} 与 {@link #tick} 的重排判定都认它，双链不可能并存。
     */
    private ScheduledFuture<?> scheduled;

    /**
     * 入队（{@code delaySeconds} 默认见 {@link #INTERVAL_SECONDS}；测试传 0 走真实单线程池即时消费）。
     */
    public void enqueue(List<FeedbackApi.Submission> submissions, long delaySeconds) {
        if (submissions.isEmpty()) {
            return;
        }
        synchronized (this) {
            pending.addAll(submissions);
            if (scheduled != null && !scheduled.isDone()) {
                return; // 在途拍拥有队列，新条目由它接管
            }
            scheduled = executor.schedule(() -> tick(delaySeconds), delaySeconds, TimeUnit.SECONDS);
        }
    }

    /** {@link #enqueue(List, long)} 的默认参数重载（{@code delaySeconds = INTERVAL_SECONDS}）。 */
    public void enqueue(List<FeedbackApi.Submission> submissions) {
        enqueue(submissions, INTERVAL_SECONDS);
    }

    /**
     * 单拍：取队首发送（成功与否都继续，不重试）→ 队列非空必续链。
     * 收尾续链与 {@link #enqueue} 同锁互斥：发送期间 enqueue 若已另排新拍（旧 future 未 done），
     * 这里 cancel 掉再排自己的——保证任意时刻至多一条链（丢唤醒修复：T-PerfSweep2-05
     * 自测实证，误判「在途拍接管」会让后续批次无人消费）。
     */
    public void tick(long delaySeconds) {
        FeedbackApi.Submission next;
        synchronized (this) {
            next = pending.poll();
        }
        if (next == null) {
            synchronized (this) {
                if (pending.isEmpty()) {
                    scheduled = null;
                }
            }
            return;
        }
        try {
            FeedbackApi.submit(next);
        } catch (Throwable ignored) {
            // 单拍不重试：成功与否都继续（原 runCatching 语义）
        }
        synchronized (this) {
            ScheduledFuture<?> chained = pending.isEmpty()
                    ? null
                    : executor.schedule(() -> tick(delaySeconds), delaySeconds, TimeUnit.SECONDS);
            if (scheduled != null) {
                scheduled.cancel(false); // 当前拍的 future 必已 done（no-op）；enqueue 抢排的未触发拍被取消
            }
            scheduled = chained;
        }
    }

    /** {@link #tick(long)} 的默认参数重载（{@code delaySeconds = INTERVAL_SECONDS}）。 */
    public void tick() {
        tick(INTERVAL_SECONDS);
    }

    /** 队列状态（queued = 有在途拍；idle = 无排队；供测试与诊断）。 */
    public String describeQueue() {
        synchronized (this) {
            return scheduled != null && !scheduled.isDone() ? "queued" : "idle";
        }
    }

    /** 错峰间隔（秒）：见类 KDoc。 */
    public static final long INTERVAL_SECONDS = 12 * 60L;

    /** 应用级实例（测试环境无 service registry 时返回 null，调用方静默跳过）。 */
    public static void scheduleQuietly(List<FeedbackApi.Submission> submissions) {
        try {
            Application application = ApplicationManager.getApplication();
            PendingReportScheduler scheduler =
                    application == null ? null : application.getService(PendingReportScheduler.class);
            if (scheduler != null) {
                scheduler.enqueue(submissions);
            }
        } catch (Throwable ignored) {
            // 静默跳过（原 runCatching 语义）：测试环境无 service registry
        }
    }
}
