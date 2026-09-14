package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T-PerfTail #2：预热等待异步化——PrewarmAwait 三分支回调时序断言（纯调度逻辑，
 * dispatcher 注入同步记录，headless 可模拟预热线程）。
 *
 * 生产形态：预热线程 complete/fail；消费侧热态 joinOrNull 同步取、冷态 scheduleOnce
 * 注册 completeOnTimeout（超时预算语义保留）回调经 dispatcher（EDT invokeLater）派发。
 */
class PrewarmAwaitTest {

    /** 时序记录器：按实际执行顺序收集标记（dispatcher 直调模拟同步派发语义）。 */
    private class Recorder : (Runnable) -> Unit {
        val events = mutableListOf<String>()
        override fun invoke(runnable: Runnable) {
            events += "dispatch"
            runnable.run()
        }
    }

    @Test
    fun test预热成功_回调派发onReady() {
        val rec = Recorder()
        val await = PrewarmAwait<String>(60_000, rec)
        await.complete("product")
        assertTrue(await.isDone())
        assertEquals("product", await.joinOrNull())
        // 热态注册：completeOnTimeout 对已完成源立即派发
        var ready: String? = null
        var fallback = false
        assertTrue(await.scheduleOnce(onReady = { ready = it }, onFallback = { fallback = true }))
        assertEquals(listOf("dispatch"), rec.events)
        assertEquals("product", ready)
        assertFalse(fallback)
    }

    @Test
    fun test预热超时_预算到期回调onFallback_且完成后不二次派发() {
        val rec = Recorder()
        val await = PrewarmAwait<String>(30, rec)
        var ready: String? = null
        var fallback = false
        assertTrue(await.scheduleOnce(onReady = { ready = it }, onFallback = { fallback = true }))
        assertEquals("预算未到期前不应派发", emptyList<String>(), rec.events)
        // 预热线程未完成 → completeOnTimeout 到期以 null 完成回调
        Thread.sleep(150)
        assertEquals(listOf("dispatch"), rec.events)
        assertNull(ready)
        assertTrue("超时应走 onFallback", fallback)
        // 超时后再完成：不触发二次派发（一次性语义）
        await.complete("late")
        Thread.sleep(50)
        assertEquals("超时后完成不应二次派发", listOf("dispatch"), rec.events)
    }

    @Test
    fun test预热失败_回调派发onFallback() {
        val rec = Recorder()
        val await = PrewarmAwait<String>(60_000, rec)
        await.fail(IllegalStateException("prewarm failed"))
        assertTrue(await.isDone())
        assertNull("失败时 joinOrNull 应为 null", await.joinOrNull())
        var ready: String? = null
        var fallback = false
        await.scheduleOnce(onReady = { ready = it }, onFallback = { fallback = true })
        assertEquals(listOf("dispatch"), rec.events)
        assertNull(ready)
        assertTrue("失败应走 onFallback（快速降级语义）", fallback)
    }

    @Test
    fun test先注册后完成_回调在完成线程派发onReady() {
        val rec = Recorder()
        val await = PrewarmAwait<String>(60_000, rec)
        var ready: String? = null
        var fallback = false
        assertTrue(await.scheduleOnce(onReady = { ready = it }, onFallback = { fallback = true }))
        // 模拟预热线程异步完成
        val done = CountDownLatch(1)
        Thread {
            await.complete("async-product")
            done.countDown()
        }.start()
        assertTrue("预热线程应完成", done.await(2, TimeUnit.SECONDS))
        assertEquals("完成回调应经 dispatcher 派发", listOf("dispatch"), rec.events)
        assertEquals("async-product", ready)
        assertFalse(fallback)
    }

    @Test
    fun test调度幂等_重复注册不生效() {
        val rec = Recorder()
        val await = PrewarmAwait<String>(60_000, rec)
        var readyCount = 0
        assertTrue("首次注册应生效", await.scheduleOnce(onReady = { readyCount++ }, onFallback = {}))
        assertFalse("重复注册应被幂等拦截", await.scheduleOnce(onReady = { readyCount++ }, onFallback = {}))
        await.complete("p")
        assertEquals(1, readyCount)
    }

    @Test
    fun test未就绪时joinOrNull不阻塞() {
        val await = PrewarmAwait<String>(60_000, Recorder())
        assertFalse(await.isDone())
        assertNull(await.joinOrNull())
    }
}
