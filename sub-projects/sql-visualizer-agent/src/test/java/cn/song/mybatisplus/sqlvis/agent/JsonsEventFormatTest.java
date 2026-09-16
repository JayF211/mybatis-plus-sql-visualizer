package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.util.Jsons;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 事件 JSON 格式守卫（IDE 侧 Gson 解析往返的前置防线）：writeEvent 产出必须是合法 JSON。
 * 回归背景：str() 辅助给首成员也补逗号 → 产出 {,"sql":...} 非法 → IDE 侧 AgentEvent.parse
 * 全量失败静默丢弃，事件零到达（两端各自测试皆绿、合流即断的往返缺口）。
 */
public class JsonsEventFormatTest {

    private static SqlEvent minimal() {
        SqlEvent e = new SqlEvent();
        e.sql = "SELECT 1";
        e.timestamp = 1789528409982L;
        e.durationNanos = 1234567L;
        return e;
    }

    @Test
    public void 最小事件_首成员无前导逗号() {
        String json = Jsons.writeEvent(minimal());
        assertTrue("应以 {\"sql\" 开头（sql 恒为首个非空字段）: " + json, json.startsWith("{\"sql\":"));
        assertFalse("对象首成员前不得有逗号: " + json, json.contains("{,"));
    }

    @Test
    public void 全字段_无相邻双逗号与尾逗号() {
        SqlEvent e = minimal();
        e.updateCount = 1L;
        e.errorText = "boom";
        e.sourceFile = "UserServiceImpl.java";
        e.sourceLine = 36;
        e.methodKey = "UserMapper#selectList";
        e.requestTag = "req-1";
        e.threadName = "http-nio-8088-exec-1";
        e.url = "jdbc:mysql://localhost:3306/demo";
        e.params = Collections.singletonList(new String[]{"1", "10", "java.lang.Integer"});
        String json = Jsons.writeEvent(e);
        assertFalse("不得出现相邻双逗号: " + json, json.contains(",,"));
        assertFalse("不得出现 ,} 尾逗号: " + json, json.contains(",}"));
        assertFalse("不得出现 {, 首逗号: " + json, json.contains("{,"));
        assertTrue("应含 params 数组: " + json, json.contains("\"p\":[["));
        assertTrue("应以 } 收尾: " + json, json.trim().endsWith("}"));
    }

    @Test
    public void 可空字段全缺_仍是合法骨架() {
        SqlEvent e = new SqlEvent();
        e.sql = "SELECT 1";
        e.timestamp = 1L;
        e.durationNanos = 1L;
        e.errorText = null;
        e.sourceFile = null;
        e.methodKey = null;
        e.requestTag = null;
        e.threadName = null;
        e.url = null;
        e.params = Collections.emptyList();
        String json = Jsons.writeEvent(e);
        assertFalse(json.contains("{,"));
        assertFalse(json.contains(",,"));
        assertFalse(json.contains(",}"));
        assertTrue(json.startsWith("{\"sql\":"));
    }

    @Test
    public void 转义仍生效_引号反斜杠控制字符() {
        SqlEvent e = new SqlEvent();
        e.sql = "SELECT 'a\\b'\n\t-- \"x\"";
        e.timestamp = 1L;
        e.durationNanos = 1L;
        String json = Jsons.writeEvent(e);
        assertFalse("换行须转义: " + json, json.contains("\n"));
        assertTrue("引号须转义: " + json, json.contains("\\\""));
        assertTrue("反斜杠须转义: " + json, json.contains("\\\\"));
    }
}
