package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.util.Jsons;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** [Jsons] 事件序列化测试（IDE 侧 AgentEvent.parse 的对端契约）。 */
public class JsonsTest {

    @Test
    public void 完整事件序列化() {
        SqlEvent e = new SqlEvent();
        e.sql = "INSERT INTO t VALUES (?)";
        e.timestamp = 1724567890000L;
        e.durationNanos = 1234567L;
        e.updateCount = 3L;
        e.errorText = null;
        e.sourceFile = "src/main/java/App.java";
        e.sourceLine = 42;
        e.methodKey = "App#doIt";
        e.requestTag = "req-1";
        e.threadName = "main";
        e.params = java.util.Arrays.asList(
                new String[]{"1", "x'y", "String"},
                new String[]{"2", null, "NULL"});
        String json = Jsons.writeEvent(e);
        assertTrue(json.contains("\"sql\":\"INSERT INTO t VALUES (?)\""));
        assertTrue(json.contains("\"ts\":1724567890000"));
        assertTrue(json.contains("\"dn\":1234567"));
        assertTrue(json.contains("\"u\":3"));
        assertTrue(json.contains("\"sf\":\"src/main/java/App.java\""));
        assertTrue(json.contains("\"sl\":42"));
        assertTrue(json.contains("\"x\\'y\"") == false); // 单引号不转义（合法 JSON）
        assertTrue(json.contains("\"x'y\""));
        assertTrue(json.contains("null"));
    }

    @Test
    public void 控制字符与反斜杠转义() {
        SqlEvent e = new SqlEvent();
        e.sql = "a\\b\"c\nd\te";
        e.timestamp = 1;
        e.durationNanos = 1;
        e.sourceLine = 0;
        String json = Jsons.writeEvent(e);
        assertTrue(json.contains("a\\\\b\\\"c\\nd\\te"));
    }

    @Test
    public void url键序列化与null省略() {
        // T-AgentDialectLoop：连接 URL 透传（面板识别目标库）；null 不落键（旧 IDE 兼容）
        SqlEvent e = new SqlEvent();
        e.sql = "SELECT 1";
        e.timestamp = 1;
        e.durationNanos = 1;
        String json = Jsons.writeEvent(e);
        assertFalse(json.contains("url"));
        e.url = "jdbc:dm://localhost:5236";
        json = Jsons.writeEvent(e);
        assertTrue(json.contains("\"url\":\"jdbc:dm://localhost:5236\""));
    }
}
