package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 命名策略单元测试（F-13）。
 */
class NamingStrategyTest {

    @Test
    fun `驼峰转下划线_常规场景`() {
        assertEquals("share_scheme_id", NamingStrategy.toSnakeCase("shareSchemeId"))
        assertEquals("id", NamingStrategy.toSnakeCase("id"))
        assertEquals("car_device_info", NamingStrategy.toSnakeCase("carDeviceInfo"))
        assertEquals("user_name", NamingStrategy.toSnakeCase("userName"))
    }

    @Test
    fun `驼峰转下划线_边界`() {
        // 空串与单字符
        assertEquals("", NamingStrategy.toSnakeCase(""))
        assertEquals("a", NamingStrategy.toSnakeCase("a"))
        // 全大写缩写（MP 默认规则下为连续拆分，属预期行为）
        assertEquals("station_i_d", NamingStrategy.toSnakeCase("stationID"))
    }
}
