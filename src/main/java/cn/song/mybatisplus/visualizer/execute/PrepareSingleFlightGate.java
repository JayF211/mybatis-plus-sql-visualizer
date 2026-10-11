package cn.song.mybatisplus.visualizer.execute;

import org.jetbrains.annotations.Nullable;

/**
 * T-FirstExecResultInvisible F1：准备链单飞 + 执行意图排队门（纯逻辑，JDK 原子类，可单测）。
 *
 * <p>根因（计划 §一 H1 主嫌）：打开预览发起的准备链（远程 PG 会话需数秒）未落地时点「执行 SQL」，
 * 旧代码在 {@code chooseAndPrepare} 无任何去重——会再建第二条临时文件/会话/控制台链。两条链并发时，
 * 准备链的 {@code executionGeneration++} 落进执行链代际捕获与取消判定之间 → 提交被取消
 * （{@code [M36] execution submit cancelled}）；即使已提交，准备链 onConsole 也会换入从未执行的
 * 全新控制台并折叠——用户见「无 loading 无报错下方空白」，第二次点击两链已收敛才正常。</p>
 *
 * <p>消除方式（结构性）：执行意图到达而已有准备链在飞时，<b>只排队不建链</b>——准备链完成时消费
 * 排队意图按 executePrepared 口径执行。无论 H1 是否终审定案都是纯增益（消除重复会话/连接）。</p>
 *
 * <p>线程纪律：{@code tryEnter}/{@code consumePendingExecute}/{@code finishPrepare} 只碰原子位与 volatile 字段，
 * 任意线程可调；排队意图回调的<b>触发</b>仍归 EDT（prepareAfterEditorResolved 消费点已在 EDT）。</p>
 */
public final class PrepareSingleFlightGate {

    /** 门持有链终结（完成/失败/复用收敛）即复位；复位后新链可重新持有。 */
    public final java.util.concurrent.atomic.AtomicBoolean prepareInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 排队执行意图在位标记（消费一次性；与 {@link #pendingIntent} 同步读写）。 */
    public final java.util.concurrent.atomic.AtomicBoolean pendingExecute =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 最后一个排队执行意图（同窗口连点执行时后到覆盖先到——最新用户意图优先）。 */
    public volatile QueuedExecuteIntent pendingIntent = null;

    /** 当前门持有链的起始时刻（卡死逃生判定基准；仅 PROCEED/接管时写）。 */
    private volatile long inFlightSinceMs = 0L;

    /**
     * 入口判定（chooseAndPrepare 调用，先于共享复用检查——冷启时 sharedConsole 尚未落地，
     * 复用必不命中，单飞是唯一闸门）。
     *
     * <p>判定序：① 空闲 → PROCEED（本链持有门，终结时必须 {@link #finishPrepare}）；
     * ② 在飞但悬挂超过 {@link #STUCK_ESCAPE_MS}（准备链消亡未复位，如 runner 失败无 session 回调）
     * → 接管放行新链自愈（清陈旧排队意图；否则执行按钮从此永久排队）；
     * ③ 在飞且本链为预览准备（executeAfterPrepare=false）→ PASS_THROUGH 直通不取所有权
     * （保持既有预览并发形态——拦截预览会让切换预览条目的 SQL 同步丢失）；
     * ④ 在飞且本链为显式执行 → 排队意图、QUEUE_ONLY（调用方 callback(true) 后返回，
     * 不再建第二条临时文件/会话/控制台）。</p>
     */
    public PrepareGateDecision tryEnter(boolean executeAfterPrepare) {
        return tryEnter(executeAfterPrepare, null);
    }

    /** Kotlin 默认参数等价重载：intent 缺省 null。 */
    public PrepareGateDecision tryEnter(boolean executeAfterPrepare, @Nullable QueuedExecuteIntent intent) {
        return tryEnter(executeAfterPrepare, intent, System.currentTimeMillis());
    }

    /** 规范入口：nowMs 缺省 {@code System.currentTimeMillis()}（Kotlin 默认参数由上面的重载补齐）。 */
    public PrepareGateDecision tryEnter(boolean executeAfterPrepare, @Nullable QueuedExecuteIntent intent, long nowMs) {
        if (prepareInFlight.compareAndSet(false, true)) {
            inFlightSinceMs = nowMs;
            return PrepareGateDecision.PROCEED;
        }
        if (nowMs - inFlightSinceMs > STUCK_ESCAPE_MS) {
            // 卡死逃生：接管所有权（CompareAndSet 已被旧链持有，直接 set 覆盖）并清陈旧排队
            prepareInFlight.set(true);
            pendingExecute.set(false);
            pendingIntent = null;
            inFlightSinceMs = nowMs;
            return PrepareGateDecision.PROCEED;
        }
        if (!executeAfterPrepare) return PrepareGateDecision.PASS_THROUGH;
        // F1 核心：已 in-flight 且本次 executeAfterPrepare=true → 只排队，不建第二链
        pendingIntent = intent;
        pendingExecute.set(true);
        return PrepareGateDecision.QUEUE_ONLY;
    }

    /**
     * 消费排队执行意图（一次性；无排队返回 null）。由门持有链在完成准备时调用
     * （prepareAfterEditorResolved 缓存落地后）；PASS_THROUGH 链不得调用。
     */
    @Nullable
    public QueuedExecuteIntent consumePendingExecute() {
        if (!pendingExecute.getAndSet(false)) return null;
        QueuedExecuteIntent intent = pendingIntent;
        pendingIntent = null;
        return intent;
    }

    /** 门持有链终结复位（成功/失败/复用收敛均须调用；PASS_THROUGH 链不得调用）。 */
    public void finishPrepare() {
        prepareInFlight.set(false);
    }

    /**
     * 单飞位悬挂逃生窗口：远程 PG 会话「数秒」量级（计划 §一），60s 内仍未终结视作死链
     * （runner 失败无 session 回调等历史挂死族，见 sharedConsole 字段注释的 M36.5.5 实证）。
     */
    public static final long STUCK_ESCAPE_MS = 60_000L;
}
