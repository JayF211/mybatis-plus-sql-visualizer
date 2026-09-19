package cn.song.mybatisplus.visualizer.feedback;

import cn.song.mybatisplus.visualizer.update.PluginVersion;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.ErrorReportSubmitter;
import com.intellij.openapi.diagnostic.IdeaLoggingEvent;
import com.intellij.openapi.diagnostic.SubmittedReportInfo;
import com.intellij.util.Consumer;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.awt.Component;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * errorHandler EP 实现（T-ErrReport-04）：IDE Internal Errors 对话框对本插件错误的
 * 「报告」按钮接官网需求池。
 *
 * <p>平台按错误归属插件解析本 EP（IdeErrorsDialog.getSubmitter(Throwable, PluginId)，
 * {@code com.intellij.errorHandler}，dynamic——243 平台 jar javap 实证，见开发计划 §四）；
 * 无需自行过滤事件来源。Freeze 事件已被归属插件（真机截图实证），同通道可达。
 *
 * <p>流程：指纹 24h 去重（命中直接 DUPLICATE 回调、不弹框）→ EDT 弹报告确认框 →
 * 用户确认后后台提交（{@link BatchSubmitter} 按条数分流）→
 * consumer 回调 NEW_ISSUE（带 FR 编号与详情页）/ FAILED。
 *
 * <p>T-FeedbackErr：提交进度与失败原因先落回<b>对话框状态行</b>（失败留窗可改可重试），
 * 平台 consumer 与通知随后照旧回调——真机上「只有一个 Toast、没有对话框」的现场即由此修掉。
 *
 * <p>T-FeedbackBatch（用户定案「不能被轻易限流」）：events 数组整体进入同一对话框——
 * events[0] 走主预填，全部事件作为批次条目（可在窗内逐条取消勾选），
 * 提交时由 {@link BatchSubmitter} 决定「合并成一条」还是「首发带合并附件 + 其余错峰补发」。
 *
 * <p>T-FeedbackBatch2（2026-09-15 用户真机「报告全部还是多次弹窗」）：上面「events 是多条」
 * 的前提在 262 平台不成立——IdeErrorsDialog「Report All」是逐错误簇循环（reportAll →
 * access$reportMessage，IdeErrorsDialog$reportAll$reported$1 字节码实证），每次
 * reportMessage 固定构造<b>长度 1</b> 的事件数组（anewarray 1 + cluster.getFirst()）调一次
 * submit。N 个错误 = N 次独立 submit = N 个弹窗，批次分节每次只有 1 条。修法：
 * submit() 不再直接弹框，而是把去抖窗口内的调用<b>累积合并成一波</b>（PendingCall 队列 +
 * 500ms debounce、自首条起 3s 强制兜底），flush 时合并全部新鲜事件开<b>一个</b>对话框，
 * consumer 合成复合回调（本波每个簇都被平台正确标记已提交）。单次调用路径参数与
 * 改造前逐项一致，仅多一次去抖延迟。
 */
public class IdeErrorReportSubmitter extends ErrorReportSubmitter {

    /** 弹框+提交流程 seam 的函数形态（测试注入捕获；生产 {@link #openDialogAndSubmit}）。 */
    @FunctionalInterface
    public interface OpenReportFlow {
        void open(ErrorReportComposer.Prefill prefill,
                  @Nullable String stackText,
                  List<String> fingerprints,
                  List<BatchSubmitter.BatchItem> batchItems,
                  Consumer<? super SubmittedReportInfo> consumer);
    }

    /** 去重判定 seam（测试注入；生产走 ErrorFingerprint 24h 窗口）。 */
    public volatile Predicate<String> dedupeGate = ErrorFingerprint::seenRecently;

    /** 弹框+提交流程 seam（测试注入捕获；生产 {@link #openDialogAndSubmit}）。 */
    public volatile OpenReportFlow openReportFlow =
            (prefill, stackText, fingerprints, batch, consumer) ->
                    openDialogAndSubmit(prefill, stackText, fingerprints, batch, consumer);

    /** 批次条目组装 seam（测试注入；生产 {@link #batchItemsOf}）。 */
    public volatile Function<IdeaLoggingEvent[], List<BatchSubmitter.BatchItem>> batchItemsProvider =
            this::batchItemsOf;

    /**
     * 去抖调度 seam（测试注入假调度器；null = 生产全局调度器，首次入队时才解析——
     * 保持构造零平台依赖，headless 测试直接 new 不触 AppExecutorUtil）。
     */
    public volatile @Nullable ScheduledExecutorService scheduler = null;

    /** 时钟 seam（3s 强制兜底的等待测量；测试注入固定时间）。 */
    public volatile LongSupplier clock = System::currentTimeMillis;

    /** 同一波待合并调用：新鲜事件 + 该次调用的平台 consumer（flush 后统一回调）。 */
    public static final class PendingCall {
        public final List<IdeaLoggingEvent> events;
        public final Consumer<? super SubmittedReportInfo> consumer;

        public PendingCall(List<IdeaLoggingEvent> events, Consumer<? super SubmittedReportInfo> consumer) {
            this.events = events;
            this.consumer = consumer;
        }
    }

    private final ArrayDeque<PendingCall> pendingCalls = new ArrayDeque<>();
    private final Object flushLock = new Object();

    /** 已排定的 flush 任务（每次入队取消重排 = debounce 语义；flush 执行时置 null）。 */
    private @Nullable ScheduledFuture<?> flushFuture = null;

    /** 首个待处理条目入队时刻（3s 强制兜底的起点）。 */
    private long firstPendingAt = 0L;

    // T-ReportL10n-02：这两处文案渲染在平台 Internal Errors 界面里，恒跟随 IDE 显示
    // 语言（messageForIde）不经插件语言设置——真机二次反馈：上轮只改 AUTO 分支落点，
    // 但插件语言默认 zh（非 AUTO），菜单项照样中文与周围英文界面割裂
    @Override
    public @NotNull String getReportActionText() {
        return MpSqlVisualizerBundle.messageForIde("error.report.action");
    }

    @Override
    public String getPrivacyNoticeText() {
        return MpSqlVisualizerBundle.messageForIde("error.report.privacy");
    }

    @Override
    public boolean submit(@NotNull IdeaLoggingEvent[] events,
                          @Nullable String additionalInfo,
                          @NotNull Component parentComponent,
                          @NotNull Consumer<? super SubmittedReportInfo> consumer) {
        if (events.length == 0) {
            return false;
        }
        // 逐事件指纹去重（T-ErrReport-02 语义不变）：命中的条目剔出本波，整条全命中
        // 才直接 DUPLICATE 回调——262 平台每次调用只有 1 条，行为与改造前等价
        List<IdeaLoggingEvent> fresh = new ArrayList<>();
        for (IdeaLoggingEvent event : events) {
            String throwableClassName = event.getThrowable() != null
                    ? event.getThrowable().getClass().getName() : null;
            if (!dedupeGate.test(ErrorFingerprint.of(throwableClassName, event.getThrowableText()))) {
                fresh.add(event);
            }
        }
        if (fresh.isEmpty()) {
            consumer.consume(new SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.DUPLICATE));
            return true;
        }
        enqueue(new PendingCall(fresh, consumer));
        return true;
    }

    /** 入队并重排去抖 flush（debounce：窗口自<b>最后一次</b>调用起算；超 {@link #MAX_WAIT_MS} 或队列达上限立即触发）。 */
    private void enqueue(PendingCall call) {
        long delayMs = DEBOUNCE_MS;
        ScheduledExecutorService scheduleExecutor = scheduler;
        if (scheduleExecutor == null) {
            scheduleExecutor = AppExecutorUtil.getAppScheduledExecutorService();
        }
        synchronized (flushLock) {
            if (pendingCalls.isEmpty()) {
                firstPendingAt = clock.getAsLong();
            }
            pendingCalls.addLast(call);
            if (flushFuture != null) {
                flushFuture.cancel(false);
            }
            // T-PerfSweep3 #2：队列达上限立即排 0ms flush——平台「报告全部」逐簇循环的理论次数无上界，
            // 队列不跟着无界；语义是「提前合并落框」而非丢弃（第 65 条起仍全部进 flush，只是不等窗口；
            // 64 条内维持去抖窗口语义不变）
            if (pendingCalls.size() > MAX_PENDING_CALLS || clock.getAsLong() - firstPendingAt >= MAX_WAIT_MS) {
                delayMs = 0;
            }
            final long scheduledDelay = delayMs;
            flushFuture = scheduleExecutor.schedule(this::flush, scheduledDelay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 合并落框（锁内整体 drain，锁外组装）：全部新鲜事件合成一次 {@link #openReportFlow}——
     * 首事件做主预填，全部事件做批次条目（T-FeedbackBatch 全含契约），consumer 收拢为
     * 复合回调（本波每个平台簇收到同一结果信息）。队列为空（重复触发/已落框）直接返回。
     */
    public void flush() {
        List<PendingCall> batch = new ArrayList<>();
        synchronized (flushLock) {
            flushFuture = null;
            while (!pendingCalls.isEmpty()) {
                batch.add(pendingCalls.pollFirst());
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        List<IdeaLoggingEvent> events = new ArrayList<>();
        for (PendingCall call : batch) {
            events.addAll(call.events);
        }
        IdeaLoggingEvent head = events.get(0);
        String headStack = head.getThrowableText();
        List<String> fingerprints = new ArrayList<>();
        for (IdeaLoggingEvent event : events) {
            String throwableClassName = event.getThrowable() != null
                    ? event.getThrowable().getClass().getName() : null;
            fingerprints.add(ErrorFingerprint.of(throwableClassName, event.getThrowableText()));
        }
        ErrorReportComposer.Prefill prefill = ErrorReportComposer.prefill(
                head.getThrowable() != null ? head.getThrowable().getClass().getName() : null,
                head.getMessage(),
                headStack);
        List<BatchSubmitter.BatchItem> batchItems = batchItemsProvider.apply(events.toArray(new IdeaLoggingEvent[0]));
        Consumer<SubmittedReportInfo> composite = info -> {
            for (PendingCall call : batch) {
                call.consumer.consume(info);
            }
        };
        openReportFlow.open(
                prefill,
                headStack != null && !headStack.isBlank() ? headStack : null,
                fingerprints,
                batchItems,
                composite);
    }

    /**
     * 用户勾选的错误事件 → 批次条目（纯函数，T-FeedbackBatch）：每个事件一条，
     * 标题=异常类全名、消息/堆栈取事件原值；无异常对象但堆栈非空同样收录。
     */
    public List<BatchSubmitter.BatchItem> batchItemsOf(IdeaLoggingEvent[] events) {
        List<BatchSubmitter.BatchItem> items = new ArrayList<>();
        for (IdeaLoggingEvent event : events) {
            String stack = event.getThrowableText();
            String className = event.getThrowable() != null ? event.getThrowable().getClass().getName() : null;
            if (className == null && (stack == null || stack.isBlank())) {
                continue;
            }
            items.add(new BatchSubmitter.BatchItem(
                    className != null ? className : "PluginError",
                    event.getMessage(),
                    stack));
        }
        return items;
    }

    /** 生产流程：EDT 弹确认框，确认后记指纹（全部合并条目）+ 后台提交 + consumer 回调。 */
    private void openDialogAndSubmit(ErrorReportComposer.Prefill prefill,
                                     @Nullable String stackText,
                                     List<String> fingerprints,
                                     List<BatchSubmitter.BatchItem> batchItems,
                                     Consumer<? super SubmittedReportInfo> consumer) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ErrorReportDialog dialog = new ErrorReportDialog(
                    prefill,
                    stackText,
                    fingerprints.isEmpty() ? null : fingerprints.get(0),
                    FeedbackReporting.ideVersion(),
                    PluginVersion.current(),
                    (submission, selectedItems, onResult) -> {
                        // T-FeedbackBatch2：合并波的每个条目都记指纹（原实现只记首条，其余条目的
                        // 24h 去重从未生效，重复出现会再次弹框）
                        for (String fingerprint : fingerprints) {
                            ErrorFingerprint.record(fingerprint);
                        }
                        // T-FeedbackBatch：条数决定「合并成一条」还是「首发 + 错峰补发」
                        BatchSubmitter.submit(submission, selectedItems, outcome -> {
                            onResult.accept(outcome);
                            consumer.consume(reportInfo(outcome.getResult()));
                            FeedbackReporting.notifyOutcome(null, outcome);
                        });
                    });
            dialog.setBatchItems(batchItems);
            dialog.show();
        });
    }

    /** 提交结果 → 平台报告信息（纯函数，internal 供测试）。 */
    public SubmittedReportInfo reportInfo(FeedbackApi.SubmitResult result) {
        if (result instanceof FeedbackApi.SubmitResult.Success) {
            FeedbackApi.SubmitResult.Success success = (FeedbackApi.SubmitResult.Success) result;
            return new SubmittedReportInfo(
                    FeedbackApi.detailUrl(success.getPostNo()),
                    success.getPostNo(),
                    SubmittedReportInfo.SubmissionStatus.NEW_ISSUE);
        }
        return new SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.FAILED);
    }

    /** debounce 窗口：自最后一次 submit 起等这么久再合并落框（平台逐簇循环间隔远小于此）。 */
    public static final long DEBOUNCE_MS = 500L;

    /** 强制落框兜底：自首个待处理条目起超过此时长不再顺延（防逐簇间隔波动致弹窗无限推迟）。 */
    public static final long MAX_WAIT_MS = 3_000L;

    /**
     * T-PerfSweep3 #2：去抖队列长度上限。64 与平台「报告全部」的现实簇数量级（个位数）
     * 之间留足裕量，正常使用永远触不到；触发即整波提前落框、零丢弃。
     */
    public static final int MAX_PENDING_CALLS = 64;
}
