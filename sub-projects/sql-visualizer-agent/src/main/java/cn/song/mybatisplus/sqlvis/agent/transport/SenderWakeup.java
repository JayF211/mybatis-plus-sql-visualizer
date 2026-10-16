package cn.song.mybatisplus.sqlvis.agent.transport;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coalesced wake hint, not storage or event accounting. A process has one production sender.
 * At most one permit; a signal during wake consumption is harmless because work is checked again
 * before the next wait, which is bounded even if a hint is coalesced.
 */
final class SenderWakeup {
    private static final Semaphore AVAILABLE = new Semaphore(0);
    private static final AtomicBoolean SIGNALED = new AtomicBoolean();
    static void signal() { if (SIGNALED.compareAndSet(false, true)) AVAILABLE.release(); }
    static void await(long timeoutMs) throws InterruptedException {
        if (AVAILABLE.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) SIGNALED.set(false);
    }
    private SenderWakeup() { }
}
