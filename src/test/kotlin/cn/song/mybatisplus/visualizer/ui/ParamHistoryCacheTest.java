package cn.song.mybatisplus.visualizer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import org.junit.Test;

import java.util.Map;

/**
 * P-05：[ParamHistoryCache] 纯逻辑单测（LRU + fingerprint + 持久化往返）。
 *
 * 纯逻辑、无 IDE 依赖（PluginSettings.getInstance() 在测试环境返回默认 State），
 * 轻量 JUnit 4 测试即可。
 */
public class ParamHistoryCacheTest {

    // ------------------------------------------------------------------
    // save + recall 往返
    // ------------------------------------------------------------------

    @Test
    public void testSaveAndRecallRoundTrip() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        String key = cache.computeKey("user", "SELECT * FROM user WHERE id = ?");
        assertNotNull(key);
        cache.save(key, Map.of("id", "123", "name", "张三"));
        Map<String, String> recalled = cache.recall(key);
        assertNotNull("应命中缓存", recalled);
        assertEquals(Map.of("id", "123", "name", "张三"), recalled);
    }

    @Test
    public void testRecallMissReturnsNull() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        String key = cache.computeKey("user", "SELECT * FROM user");
        assertNull("未保存的 key 应返回 null", cache.recall(key));
    }

    // ------------------------------------------------------------------
    // LRU 淘汰
    // ------------------------------------------------------------------

    @Test
    public void testLruEvictionOverMaxSize() throws Exception {
        // 小上限便于测试淘汰（上限 3）
        ParamHistoryCache cache = new ParamHistoryCache(3);
        cache.clear();
        String sql = "SELECT * FROM tbl WHERE id = ?";
        // 存入 4 条（超过上限 3）
        for (int i = 0; i < 4; i++) {
            String key = "tbl:" + i;
            cache.save(key, Map.of("id", String.valueOf(i)));
        }
        assertEquals("超过上限后应淘汰到 maxSize", 3, cache.size());
        // 最久未访问的第 0 条应被淘汰
        assertNull("最早条目应被淘汰", cache.recall("tbl:0"));
        // 第 1/2/3 条仍在
        assertNotNull(cache.recall("tbl:1"));
        assertNotNull(cache.recall("tbl:2"));
        assertNotNull(cache.recall("tbl:3"));
    }

    @Test
    public void testLruAccessOrderUpdate() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache(3);
        cache.clear();
        for (int i = 0; i < 3; i++) cache.save("tbl:" + i, Map.of("id", String.valueOf(i)));
        // 访问第 0 条（最近访问 → 排到尾部，不再是最久未访问）
        cache.recall("tbl:0");
        // 插入第 4 条 → 淘汰最久未访问的第 1 条（第 0 条刚被访问过）
        cache.save("tbl:3", Map.of("id", "3"));
        assertNotNull("最近访问的第 0 条应保留", cache.recall("tbl:0"));
        assertNull("最久未访问的第 1 条应被淘汰", cache.recall("tbl:1"));
    }

    // ------------------------------------------------------------------
    // computeKey 一致性
    // ------------------------------------------------------------------

    @Test
    public void testComputeKeyConsistency() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        String key1 = cache.computeKey("user", "SELECT * FROM user WHERE id = ?");
        String key2 = cache.computeKey("user", "SELECT * FROM user WHERE id = ?");
        assertEquals("相同 tableName+rawSql 应生成相同 key", key1, key2);
    }

    @Test
    public void testComputeKeyDifferentSql() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        String key1 = cache.computeKey("user", "SELECT * FROM user WHERE id = ?");
        String key2 = cache.computeKey("user", "SELECT * FROM user WHERE name = ?");
        assertFalse("不同 SQL 应生成不同 key", key1.equals(key2));
    }

    @Test
    public void testComputeKeyNullTableNameReturnsNull() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        assertNull("无表名应返回 null（不缓存）", cache.computeKey(null, "SELECT 1"));
        assertNull("空表名应返回 null（不缓存）", cache.computeKey("", "SELECT 1"));
    }

    // ------------------------------------------------------------------
    // fingerprint 稳定性
    // ------------------------------------------------------------------

    @Test
    public void testFingerprintStability() throws Exception {
        String sql = "SELECT * FROM user WHERE id = ?";
        String fp1 = ParamHistoryCache.fingerprint(sql);
        String fp2 = ParamHistoryCache.fingerprint(sql);
        assertEquals("相同 SQL → 相同指纹", fp1, fp2);
        assertEquals("指纹应为 16 hex 字符", 16, fp1.length());
    }

    @Test
    public void testFingerprintDifferentSql() throws Exception {
        String fp1 = ParamHistoryCache.fingerprint("SELECT * FROM user");
        String fp2 = ParamHistoryCache.fingerprint("SELECT * FROM order");
        assertFalse("不同 SQL → 不同指纹", fp1.equals(fp2));
    }

    // ------------------------------------------------------------------
    // clear 清空
    // ------------------------------------------------------------------

    @Test
    public void testClearEmptiesCache() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        String key = cache.computeKey("user", "SELECT * FROM user");
        cache.save(key, Map.of("id", "1"));
        assertEquals(1, cache.size());
        cache.clear();
        assertEquals("clear 后应为空", 0, cache.size());
        assertNull("clear 后 recall 返回 null", cache.recall(key));
    }

    // ------------------------------------------------------------------
    // 持久化往返
    // ------------------------------------------------------------------

    @Test
    public void testPersistenceRoundTrip() throws Exception {
        ParamHistoryCache cache1 = new ParamHistoryCache();
        cache1.clear();
        String key = cache1.computeKey("user", "SELECT * FROM user WHERE id = ?");
        cache1.save(key, Map.of("id", "42", "status", "active"));
        // 模拟 IDE 重启：新建 cache 实例，从持久化恢复
        ParamHistoryCache cache2 = new ParamHistoryCache();
        Map<String, String> recalled = cache2.recall(key);
        assertNotNull("新实例应从持久化恢复", recalled);
        assertEquals(Map.of("id", "42", "status", "active"), recalled);
        cache2.clear();
    }
}
