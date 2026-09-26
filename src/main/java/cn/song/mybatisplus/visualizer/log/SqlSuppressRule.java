// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlSuppressRule.kt 移植
package cn.song.mybatisplus.visualizer.log;

import java.util.UUID;

/**
 * SQL 屏蔽规则（O-69 T-SqlSuppress）：一条「后续不再捕获」的语句形态。
 *
 * <p>持久化友好（照 {@code cn.song.mybatisplus.visualizer.history.SqlHistoryEntry} 形态）：
 * 全部字段为基本类型 / String，XmlSerializer 可直接序列化。
 *
 * <p>Kotlin data class（var 属性）→ 可变 POJO：getter/setter 为 Kotlin 属性访问的 JVM 形态；
 * 无参构造为 XmlSerializer 反序列化要求（Kotlin 默认参数的等价形态，全字段取默认值）。
 */
public class SqlSuppressRule {

    /** 唯一标识（空白时由 {@link SqlSuppressRuleManager#addRule} 自动分配 UUID）。 */
    private String id = "";

    /** 匹配模式——规范化文本（normalizeSqlWhitespace 后），存储与展示口径；运行时按其指纹进匹配集。 */
    private String pattern = "";

    /** 原始还原 SQL（右键动作捕获时的 restoredSql），管理对话框高亮展示用。 */
    private String sampleSql = "";

    /** 创建时间戳（毫秒；管理对话框列表展示）。 */
    private long createdAt = 0L;

    /**
     * 结构匹配开关（T-SqlSuppressUx 二段，20260924 用户定案：手动开启）——开启后本规则按结构指纹
     * （字符串字面量与裸数字参数化为 {@code ?}）匹配，参数值不同的同形状 SQL 一并屏蔽；
     * 关闭（默认）按精确口径（空白归一 + 尾分号剥离）。缺省字段（存量 XML）= false，存量规则行为不变。
     */
    private boolean structureMatch = false;

    /** XmlSerializer 反序列化用无参构造（= Kotlin 全默认参数构造）。 */
    public SqlSuppressRule() {
    }

    public SqlSuppressRule(String id, String pattern, String sampleSql, long createdAt, boolean structureMatch) {
        this.id = id;
        this.pattern = pattern;
        this.sampleSql = sampleSql;
        this.createdAt = createdAt;
        this.structureMatch = structureMatch;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getSampleSql() {
        return sampleSql;
    }

    public void setSampleSql(String sampleSql) {
        this.sampleSql = sampleSql;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isStructureMatch() {
        return structureMatch;
    }

    public void setStructureMatch(boolean structureMatch) {
        this.structureMatch = structureMatch;
    }

    /** 分配唯一 id（仅当当前为空白时）。 */
    public void ensureId() {
        if (id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    /** 列表展示用 pattern 摘要：首行、截断至 {@link #MAX_PATTERN_SUMMARY_LEN} 字符。 */
    public String patternSummary() {
        String trimmed = pattern.trim();
        String[] lines = trimmed.split("\n");
        // Kotlin split(limit=0) 去尾空串后 firstOrNull；split 结果至少含一个元素（空串输入 = [""]）
        String firstLine = lines.length > 0 ? lines[0].trim() : "";
        if (firstLine.length() <= MAX_PATTERN_SUMMARY_LEN) {
            return firstLine;
        }
        return firstLine.substring(0, MAX_PATTERN_SUMMARY_LEN) + "…";
    }

    /** 列表渲染的 pattern 摘要最大字符数。 */
    public static final int MAX_PATTERN_SUMMARY_LEN = 80;
}
