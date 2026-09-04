package cn.song.mybatisplus.visualizer.log;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JList;
import java.util.List;

/**
 * T-LogListFreeze 单元测试：列表刷新性能优化（EDT 冻结修复）。
 *
 * - T-02：列表 HTML 惰性缓存 + 缓存命中（渲染器实例内弱引用键，refreshList 整体失效）
 * - T-01/03：批量布局抑制 + 分批 EDT 写入常量存在性
 */
public class LogListFreezeTest extends BasePlatformTestCase {

    private final LogEntryListCellRenderer renderer = new LogEntryListCellRenderer();
    private final JList<Object> list = new JList<>();

    private static LogSqlEntry entry(String sql) {
        return new LogSqlEntry(sql, sql, List.of(), 1_700_000_000_000L);
    }

    private String render(LogSqlEntry entry, boolean selected) {
        JLabel c = (JLabel) renderer.getListCellRendererComponent(list, entry, 0, selected, false);
        return c.getText();
    }

    private String cachedHtml(LogSqlEntry entry) {
        return renderer.cachedListHtmlForTest(entry);
    }

    // ---------- T-02：缓存行为 ----------

    @Test
    public void test新条目缓存为null_首次渲染后缓存非null() throws Exception {
        LogSqlEntry e = entry("SELECT * FROM t");
        assertNull("新建条目缓存应为 null", cachedHtml(e));

        String text = render(e, false);
        String cached = cachedHtml(e);
        assertNotNull("首次渲染后缓存应被填充", cached);
        assertEquals("缓存内容应等于渲染结果", text, cached);
    }

    @Test
    public void test同一条目二次渲染_命中缓存_不再重新计算Html() throws Exception {
        LogSqlEntry e = entry("SELECT 1");
        String first = render(e, false);
        String cached = cachedHtml(e);

        // 二次渲染：应返回完全相同的字符串对象（缓存命中）
        String second = render(e, false);
        assertSame("二次渲染应命中缓存（同一对象）", cached, cachedHtml(e));
        assertEquals(first, second);
    }

    @Test
    public void test刷新后缓存清空_render重新填充() throws Exception {
        LogSqlEntry e = entry("SELECT 1");
        render(e, false);
        assertNotNull(cachedHtml(e));

        // 模拟 refreshList 清空缓存
        renderer.invalidateListHtmlCache();
        assertNull("清空后缓存为 null", cachedHtml(e));

        String text = render(e, false);
        String cached = cachedHtml(e);
        assertNotNull("重新渲染后缓存恢复", cached);
        assertEquals(text, cached);
        assertNotSame("清空后渲染结果应为新的 HTML 实例", (Object) text, (Object) cached);
    }

    @Test
    public void test不同条目缓存相互独立() throws Exception {
        LogSqlEntry e1 = entry("SELECT 1");
        LogSqlEntry e2 = entry("SELECT 2");
        render(e1, false);
        render(e2, false);
        assertNotEquals("不同条目缓存内容应不同", cachedHtml(e1), cachedHtml(e2));
        assertNotEquals("e2 缓存应不含 SELECT 1", true,
                cachedHtml(e2) != null && cachedHtml(e2).contains("SELECT 1"));
    }

    // ---------- T-01/03：常量存在性 ----------

    @Test
    public void test首屏批次数大于0且小于总批次数() throws Exception {
        // REFRESH_FIRST_BATCH_SIZE 和 REFRESH_CHUNK_SIZE 应为合理正数
        assertTrue("首屏批次应 > 0", SqlLogPanelKt.REFRESH_FIRST_BATCH_SIZE > 0);
        assertTrue("异步批次应 > 0", SqlLogPanelKt.REFRESH_CHUNK_SIZE > 0);
        assertTrue("首屏批次应 <= 异步批次 × 3（保证首屏足够快）",
                SqlLogPanelKt.REFRESH_FIRST_BATCH_SIZE <= SqlLogPanelKt.REFRESH_CHUNK_SIZE * 3);
    }

    @Test
    public void test200条数据分两批以上写入() throws Exception {
        int total = 200;
        int firstBatchSize = SqlLogPanelKt.REFRESH_FIRST_BATCH_SIZE;
        int chunkSize = SqlLogPanelKt.REFRESH_CHUNK_SIZE;
        int remaining = total - firstBatchSize;
        int chunks = remaining <= 0 ? 0 : (remaining + chunkSize - 1) / chunkSize;
        int totalBatches = 1 + chunks;

        assertTrue("200 条应分多批写入（>2 批），避免首屏冻结", totalBatches > 2);
        assertTrue("首屏批次应 < 总数（有异步追加）", firstBatchSize < total);
    }
}
