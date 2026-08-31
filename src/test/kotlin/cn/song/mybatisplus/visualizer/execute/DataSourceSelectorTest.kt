package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [DataSourceSelector] 冒烟测试（F-44 续 · 补用例大波次）：
 * 该类核心执行链完全基于反射隔离的 Database 插件 API（Community 测试 classpath 不含），
 * 故仅对**无状态指针守卫**做确定性校验——未准备控制台时 [isPrepared] 为 false、
 * [clearPrepared] 可安全重复调用且不抛异常。真正的执行链路由 Ultimate + Database 插件的
 * 集成/手动验证覆盖，本测试锁定“无准备态不误触”的安全不变量。
 */
class DataSourceSelectorTest {

    @Test
    fun testIsPrepared_默认未准备() {
        assertFalse("默认应无已准备控制台", DataSourceSelector.isPrepared())
    }

    @Test
    fun testClearPrepared_安全可重复调用() {
        DataSourceSelector.clearPrepared()
        assertFalse(DataSourceSelector.isPrepared())
        // 二次调用幂等，不抛异常
        DataSourceSelector.clearPrepared()
        assertFalse(DataSourceSelector.isPrepared())
    }
}
