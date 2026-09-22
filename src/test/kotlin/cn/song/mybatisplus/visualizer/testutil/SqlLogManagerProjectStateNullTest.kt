package cn.song.mybatisplus.visualizer.testutil

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-AgentResiduals R2 的前提钉住：`capture(null)` / `restore(null)` 必须是**静默 no-op**。
 *
 * 为什么需要这条（计划 §二 P-2）：R2 把「快照持有 manager 引用、tearDown 只 `restore(snapshot)`」
 * 作为消除 `@After` 取服务（temporarily-disposed 窗口高发时段）的手段。若 `capture` 对「服务取不到」
 * 的输入抛异常、或 `restore` 对 null 快照抛 NPE，调用方就不得不自己分支——那正是「在 tearDown 里
 * 再取一次服务」的老路。故先把退化语义钉死，再依赖它。
 *
 * **不继承平台测试基类、不碰 Project**：本用例只验证纯退化语义（无服务、无容器），
 * 因此它也顺带证明「服务未创建时这条链完全不需要服务」——这正是 R2 要的性质。
 */
class SqlLogManagerProjectStateNullTest {

    @Test
    fun 服务未创建_capture返回null且restore静默跳过() {
        val snapshot = SqlLogManagerProjectState.capture(null)
        assertNull("manager 为 null（服务未创建）时 capture 应返回 null 快照，而不是抛异常", snapshot)
        // 关键：不得抛（调用方因此无需自行分支，tearDown 里可以无脑 restore）
        SqlLogManagerProjectState.restore(snapshot)
        // 幂等：再走一次同样静默
        SqlLogManagerProjectState.restore(null)
    }
}
