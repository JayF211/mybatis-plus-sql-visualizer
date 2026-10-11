package cn.song.mybatisplus.visualizer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
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
        // R22 T-01（D1=A）契约变更：无表名不再返回 null——以 SQL 文本规范化指纹为键持久化
        // （缺口①修复；旧契约「无表名不缓存」随纯文本预览参数全丢的缺陷一并废除）。
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        assertNotNull("无表名应以规范化指纹为键（R22 契约）", cache.computeKey(null, "SELECT 1"));
        assertNotNull("空表名同上", cache.computeKey("", "SELECT 1"));
        assertTrue(cache.computeKey(null, "SELECT 1").startsWith("sql:"));
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

    // ==== R22 T-01（D1=A）：无表名预览以 SQL 文本规范化指纹为键（master 新增用例 Java 承载） ====

    /** 缺口①修复：无表名 computeKey 恒非 null（改前返回 null 不缓存——纯文本预览参数全丢）。 */
    @Test
    public void R22_T01_无表名_键非空且同SQL同键() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        String k1 = cache.computeKey(null, "SELECT * FROM custom_dao_table WHERE a = ?");
        assertNotNull("无表名预览应有键（dedupeKey 同源规范化指纹）", k1);
        assertTrue("键应以 sql: 前缀标识无表名形态", k1.startsWith("sql:"));
        assertEquals("同 SQL 同键", k1, cache.computeKey(null, "SELECT * FROM custom_dao_table WHERE a = ?"));
        // 规范化口径：空白差异折叠（dedupeKey 同源）
        assertEquals("空白差异折叠后同键", k1, cache.computeKey(null, "SELECT  *  FROM custom_dao_table WHERE a = ?"));
        assertNotEquals("不同 SQL 不同键", k1, cache.computeKey(null, "SELECT 1"));
    }

    /** 存量兼容：有表名键形态逐字节不变（tableName:fingerprint）。 */
    @Test
    public void R22_T01_有表名_现键兼容() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        String key = cache.computeKey("t_user", "SELECT * FROM t_user WHERE id = ?");
        assertNotNull(key);
        assertTrue("有表名键保持 tableName: 前缀", key.startsWith("t_user:"));
    }

    // ==== R22 T-04（D2 定案）：命名方案多方案结构 ====

    /** 缺口④/D2：旧 `paramHistoryJson` 存量自动成为「默认方案」——零迁移损失，老用户无感。 */
    @Test
    public void R22_T04_D2_旧字段数据自动成为默认方案() throws Exception {
        ParamHistoryCache cache1 = new ParamHistoryCache();
        cache1.clear();
        String key = cache1.computeKey("user", "SELECT * FROM user WHERE id = ?");
        cache1.save(key, Map.of("id", "7", "name", "张三"));
        // 模拟 IDE 重启：新实例从旧字段恢复——数据必须落进默认方案（不是凭空丢、也不是新造方案）
        ParamHistoryCache cache2 = new ParamHistoryCache();
        assertEquals("D2：旧字段数据应从默认方案召回", Map.of("id", "7", "name", "张三"), cache2.recall(key));
        assertEquals("D2：旧数据不应生成命名方案", List.of(), cache2.schemeNames());
        assertEquals("D2：激活方案应保持默认方案", ParamHistoryCache.DEFAULT_SCHEME, cache2.activeSchemeName());
        cache2.clear();
    }

    /** 缺口④：另存方案 → 切走 → 切回，参数快照（含勾选态特殊键）逐字节还原。 */
    @Test
    public void R22_T04_方案另存切走切回_逐字节还原() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        String key = cache.computeKey(null, "SELECT * FROM t_order WHERE id = ?");
        // 默认方案：正常流参数（含 T-02 勾选态特殊键形态）
        Map<String, String> normal = Map.of("id", "1", "__chk__:onlyActive", "1", "__chk__:onlyVip", "0", "__fev__:ids", "2,3");
        cache.save(key, normal);
        // 另存「边界流」：边界流参数（勾选/foreach 形态不同）
        Map<String, String> boundary = Map.of("id", "999", "__chk__:onlyActive", "0", "__fev__:ids", "8");
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.saveAsScheme("边界流", key, boundary));
        assertEquals("另存应切换激活方案", "边界流", cache.activeSchemeName());
        assertEquals("激活方案召回边界流快照", boundary, cache.recall(key));
        // 切走再切回：默认方案快照逐字节还原（T-DevAcceptFix2 ①：切换按键——键=本条语句）
        cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME, key);
        assertEquals("切回默认方案快照逐字节还原", normal, cache.recall(key));
        cache.switchScheme("边界流", key);
        assertEquals("再切回命名方案快照逐字节还原", boundary, cache.recall(key));
        cache.clear();
    }

    /** 方案隔离：命名方案快照不串默认方案，默认方案不被另存覆盖。 */
    @Test
    public void R22_T04_方案隔离_命名与默认互不串写() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        String key = cache.computeKey("t_user", "SELECT * FROM t_user WHERE id = ?");
        cache.save(key, Map.of("id", "1"));
        cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME, key); // saveAsScheme 会切激活，显式回默认再存
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.saveAsScheme("边界流", key, Map.of("id", "2")));
        assertEquals(Map.of("id", "2"), cache.recallFromScheme("边界流", key));
        cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME, key);
        assertEquals("默认方案快照不被另存覆盖", Map.of("id", "1"), cache.recall(key));
        assertEquals("命名方案条目数", 1, cache.schemeEntryCount("边界流"));
        cache.clear();
    }

    /** 重命名/删除语义：默认方案受保护、重名拒绝、未知方案 NOT_FOUND、激活指针随迁。 */
    @Test
    public void R22_T04_重命名与删除_语义() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        String key = cache.computeKey("t_a", "SELECT * FROM t_a WHERE id = ?");
        cache.saveAsScheme("A", key, Map.of("id", "1"));
        cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME);
        cache.saveAsScheme("B", key, Map.of("id", "2"));
        // 默认方案保护
        assertEquals(
            "默认方案不可重命名",
            ParamHistoryCache.SchemeOpResult.DEFAULT_PROTECTED,
            cache.renameScheme(ParamHistoryCache.DEFAULT_SCHEME, "X")
        );
        assertEquals(
            "默认方案不可删除",
            ParamHistoryCache.SchemeOpResult.DEFAULT_PROTECTED,
            cache.deleteScheme(ParamHistoryCache.DEFAULT_SCHEME)
        );
        // 重名 / 未知
        assertEquals(
            "重命名到已存在名应拒绝",
            ParamHistoryCache.SchemeOpResult.DUPLICATE,
            cache.renameScheme("A", "B")
        );
        assertEquals(
            "未知方案重命名 NOT_FOUND",
            ParamHistoryCache.SchemeOpResult.NOT_FOUND,
            cache.renameScheme("NOPE", "X")
        );
        // 重命名保序 + 激活指针随迁
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.renameScheme("A", "A2"));
        assertEquals("创建序应保持", List.of("A2", "B"), cache.schemeNames());
        assertEquals(Map.of("id", "1"), cache.recallFromScheme("A2", key));
        assertNull("旧名应消失", cache.recallFromScheme("A", key));
        cache.switchScheme("A2");
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.renameScheme("A2", "A3"));
        assertEquals("激活指针应随迁", "A3", cache.activeSchemeName());
        // 删除激活方案 → 归位默认方案
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.deleteScheme("A3"));
        assertEquals("删除激活方案后应归位默认方案", ParamHistoryCache.DEFAULT_SCHEME, cache.activeSchemeName());
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.deleteScheme("B"));
        assertEquals(List.of(), cache.schemeNames());
        cache.clear();
    }

    /** 命名方案持久化往返（模拟 IDE 重启）+ 未知激活名兜底归位默认方案。 */
    @Test
    public void R22_T04_方案持久化往返与激活兜底() throws Exception {
        ParamHistoryCache cache1 = new ParamHistoryCache();
        cache1.clear();
        String key = cache1.computeKey("t_b", "SELECT * FROM t_b WHERE id = ?");
        Map<String, String> snapshot = Map.of("id", "42", "__chk__:flag", "0");
        cache1.saveAsScheme("边界流", key, snapshot);
        // 新实例恢复：方案清单/内容/激活态全量还原
        ParamHistoryCache cache2 = new ParamHistoryCache();
        assertEquals("方案清单应恢复", List.of("边界流"), cache2.schemeNames());
        assertEquals("方案快照应恢复", snapshot, cache2.recallFromScheme("边界流", key));
        assertEquals("激活方案应恢复", "边界流", cache2.activeSchemeName());
        assertEquals("激活方案路由召回应命中", snapshot, cache2.recall(key));
        cache2.clear();
    }

    /** 防脏态：switchScheme 对未知方案名忽略（激活态不漂移）。 */
    @Test
    public void R22_T04_切换未知方案名忽略() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        cache.switchScheme("不存在");
        assertEquals("未知方案名不应改变激活态", ParamHistoryCache.DEFAULT_SCHEME, cache.activeSchemeName());
        cache.clear();
    }

    // ------------------------------------------------------------------
    // F49/F50（T-PerfQuick5 性能快件批）
    // ------------------------------------------------------------------

    /**
     * F49：switch 只写激活指针——不触发全量序列化（persistProbe 计数守卫）。
     * 反向验证锚：摘掉修复（switchScheme 回走 persistToStorage）→ 计数 +1 → 本测试红。
     */
    @Test
    public void F49_switch只写指针_不触发全量序列化() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        String key = cache.computeKey("t_f49", "SELECT * FROM t_f49 WHERE id = ?");
        java.util.concurrent.atomic.AtomicInteger probeCount = new java.util.concurrent.atomic.AtomicInteger();
        ParamHistoryCache.persistProbe = probeCount::incrementAndGet;
        try {
            cache.saveAsScheme("S1", key, Map.of("id", "1")); // 另存仍走全量序列化（1 次）
            int afterSave = probeCount.get();
            assertEquals("前置：另存应恰好触发一次全量序列化", 1, afterSave);
            cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME);
            cache.switchScheme("S1");
            cache.switchScheme("S1"); // 同名幂等
            assertEquals(
                "switch 不得走全量序列化（只写激活指针）：计数 " + afterSave + " → " + probeCount.get(),
                afterSave,
                probeCount.get()
            );
            // 指针本身仍轻量落盘：新实例恢复激活态
            ParamHistoryCache cache2 = new ParamHistoryCache();
            assertEquals("激活指针经轻路径落盘仍可恢复", "S1", cache2.activeSchemeName());
            cache2.clear();
        } finally {
            ParamHistoryCache.persistProbe = null;
        }
        cache.clear();
    }

    /** F49：方案个数上限——达 MAX_SCHEME_COUNT 后新建另存拒收，存量操作不受影响。 */
    @Test
    public void F49_方案个数上限_新建拒收存量不受影响() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        String key = cache.computeKey("t_f49b", "SELECT * FROM t_f49b WHERE id = ?");
        for (int i = 0; i < ParamHistoryCache.MAX_SCHEME_COUNT; i++) {
            assertEquals(
                "第 " + (i + 1) + " 个方案应成功",
                ParamHistoryCache.SchemeOpResult.OK,
                cache.saveAsScheme("S" + i, key, Map.of("id", String.valueOf(i)))
            );
        }
        assertEquals(
            "达上限后新建另存应拒收",
            ParamHistoryCache.SchemeOpResult.LIMIT_REACHED,
            cache.saveAsScheme("溢出方案", key, Map.of("id", "X"))
        );
        assertFalse("被拒方案不应入库", cache.hasScheme("溢出方案"));
        assertEquals("方案个数不得超上限", ParamHistoryCache.MAX_SCHEME_COUNT, cache.schemeNames().size());
        // 存量操作不受影响：既有方案覆盖写 / 重命名 / 删除
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.saveAsScheme("S0", key, Map.of("id", "overwritten")));
        assertEquals(Map.of("id", "overwritten"), cache.recallFromScheme("S0", key));
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.renameScheme("S1", "S1R"));
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.deleteScheme("S1R"));
        // 删除腾出名额后可新建
        assertEquals(ParamHistoryCache.SchemeOpResult.OK, cache.saveAsScheme("新名额", key, Map.of("id", "N")));
        cache.clear();
    }

    /**
     * F50：方案存储 CHM 结构守卫——`schemes` 须为 ConcurrentHashMap。
     * 反向验证锚：摘掉 CHM 化（回退 LinkedHashMap）→ 本测试红（确定性红，并发压测只作辅助面）。
     */
    @Test
    public void F50_方案存储_CHM结构守卫() throws Exception {
        java.lang.reflect.Field field = ParamHistoryCache.class.getDeclaredField("schemes");
        field.setAccessible(true);
        assertTrue(
            "schemes 须为 ConcurrentHashMap（F50：后台 pooled 写回 vs EDT 读原裸 LinkedHashMap 无同步保护）",
            java.util.concurrent.ConcurrentHashMap.class.isAssignableFrom(field.getType())
        );
    }

    /** F50：pooled 写回与 EDT 读并发——多线程 saveForScheme 建桶，主线程连续读清单，全量在册且读侧不抛。 */
    @Test
    public void F50_pooled写回与EDT读并发_全量在册() throws Exception {
        ParamHistoryCache cache = new ParamHistoryCache();
        cache.clear();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int tid = t;
            threads.add(new Thread(() -> {
                for (int i = 0; i < 25; i++) {
                    cache.saveForScheme("并发" + tid + "-" + i, "k", Map.of("id", String.valueOf(i)));
                }
            }));
        }
        threads.forEach(Thread::start);
        int reads = 0;
        while (threads.stream().anyMatch(Thread::isAlive)) {
            List<String> names = cache.schemeNames(); // EDT 侧读形态：结构遍历不得抛 CME
            if (names.size() > 4 * 25) throw new AssertionError("方案清单越界：" + names.size());
            reads++;
        }
        for (Thread th : threads) th.join(5000);
        assertTrue("读循环应实际执行（防空转空绿）", reads > 0);
        assertEquals("并发建桶方案应全量在册", 100, cache.schemeNames().size());
        cache.clear();
    }

    /** F50：创建序经持久化往返不漂移（serializeSchemesToJson 按创建序遍历，CHM 哈希序不外泄）。 */
    @Test
    public void F50_创建序_持久化往返不漂移() throws Exception {
        ParamHistoryCache cache1 = new ParamHistoryCache();
        cache1.clear();
        String key = cache1.computeKey("t_f50", "SELECT * FROM t_f50 WHERE id = ?");
        // 以乱哈希序感明显的名字集创建
        List<String> names = List.of("кирпич", "alpha", "方案Z", "0start", "Omega");
        for (int i = 0; i < names.size(); i++) {
            cache1.saveAsScheme(names.get(i), key, Map.of("id", String.valueOf(i)));
        }
        assertEquals("创建序在册", names, cache1.schemeNames());
        ParamHistoryCache cache2 = new ParamHistoryCache(); // 模拟重启恢复
        assertEquals("持久化往返后创建序不漂移", names, cache2.schemeNames());
        cache2.clear();
        cache1.clear();
    }
}
