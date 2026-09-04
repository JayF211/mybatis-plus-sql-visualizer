package cn.song.mybatisplus.visualizer.util;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * 命名策略单元测试（F-13）。
 */
public class NamingStrategyTest {

    @Test
    public void 驼峰转下划线_常规场景() throws Exception {
        assertEquals("share_scheme_id", NamingStrategy.INSTANCE.toSnakeCase("shareSchemeId"));
        assertEquals("id", NamingStrategy.INSTANCE.toSnakeCase("id"));
        assertEquals("car_device_info", NamingStrategy.INSTANCE.toSnakeCase("carDeviceInfo"));
        assertEquals("user_name", NamingStrategy.INSTANCE.toSnakeCase("userName"));
    }

    @Test
    public void 驼峰转下划线_边界() throws Exception {
        // 空串与单字符
        assertEquals("", NamingStrategy.INSTANCE.toSnakeCase(""));
        assertEquals("a", NamingStrategy.INSTANCE.toSnakeCase("a"));
        // 全大写缩写（MP 默认规则下为连续拆分，属预期行为）
        assertEquals("station_i_d", NamingStrategy.INSTANCE.toSnakeCase("stationID"));
    }
}
