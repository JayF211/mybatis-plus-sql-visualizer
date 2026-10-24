package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-DialectInferPollution W9 护栏：EXPLAIN 链回落证据选择
 * （[DataSourceSelector.resolveExplainFallbackChoice]）纯逻辑表。
 *
 * 缺陷形态（2026-10-24 用户沙盒实测）：W4/W8 的「无脑回落 last-used」把 mp_demo 硬套给
 * jpa-jar-model-demo 模块（表在 jpa_demo）→ `mp_demo.wms_inventory_jar_demo doesn't exist`。
 * 修正语义：
 *  - 预览模块有学习绑定 → 用学习源（模块归属强于全局记忆，UUID 解析）；
 *  - 学习源与 last-used 同时存在且不同 → **冲突不猜**，返回 null（下游 NO_DATA_SOURCE 引导
 *    弹数据源选择器，用户选一次即自愈更新学习与记忆）；
 *  - 学习缺失 → last-used 兜底（demo 主模块等既有场景）。
 *
 * 本类零平台引用（testFast 快桶成员）。
 */
class ExplainLastUsedFallbackTest {

    @Test
    fun learnedEqualsLastIsConsistent() {
        assertEquals(
            "学习源与 last-used 同源 → 一致，取该源",
            "same-uuid",
            DataSourceSelector.resolveExplainFallbackChoice("same-uuid", "same-uuid"),
        )
    }

    @Test
    fun conflictBetweenLearnedAndLastIsAbandoned() {
        assertNull(
            "学习源与 last-used 不同源 → 冲突不猜（错源执行比引导选择更糟，W9 核心语义）",
            DataSourceSelector.resolveExplainFallbackChoice("dbx-test-pg", "mp-demo-mysql"),
        )
    }

    @Test
    fun lastUsedFallsBackWhenNoLearning() {
        assertEquals(
            "模块无学习 → last-used 兜底（W4/W8 既有场景）",
            "last-uuid",
            DataSourceSelector.resolveExplainFallbackChoice(null, "last-uuid"),
        )
        assertNull("两者皆无 → null（下游 NO_DATA_SOURCE 引导）", DataSourceSelector.resolveExplainFallbackChoice(null, null))
    }
}
