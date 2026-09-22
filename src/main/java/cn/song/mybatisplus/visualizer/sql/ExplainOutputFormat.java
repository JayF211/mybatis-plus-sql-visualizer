// T-SyncK2JMaster184 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/sql/ExplainSupport.kt 移植
package cn.song.mybatisplus.visualizer.sql;

/**
 * EXPLAIN 计划输出格式（T-ExplainChainHardening T-ECH-02 / O-48）。
 *
 * <p>引入原因（第十轮报告 §8 #51）：此前「格式」概念不存在——包装语句与输出形态都硬编码在
 * 各方言的 {@code renderExplainStatement} 覆写里，消费方（
 * {@link cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor}）只能按<b>方言名</b>猜格式，
 * 于是 PG/KingBase 的 {@code FORMAT JSON} 被当成文本树解析 → 建议永不触发（真缺陷，T-ECH-03 修复）。</p>
 *
 * <p>取值按「计划文本的物理形态」分类，不按数据库品牌——同一品牌不同格式（如 PG 的
 * {@code FORMAT TEXT} 与 {@code FORMAT JSON}）分属不同取值，消费方按形态选解析器：</p>
 * <ul>
 *  <li>{@link #TABLE}：行列二维表（MySQL 家族 {@code EXPLAIN} 的原生表格行）；</li>
 *  <li>{@link #TEXT}：单列文本计划树/文本行（DM8 {@code #NSET2}、H2 {@code PLAN FOR}、SQLite
 *      {@code QUERY PLAN}）；</li>
 *  <li>{@link #JSON}：单列 JSON 文本（PG/KingBase {@code FORMAT JSON}）。</li>
 * </ul>
 *
 * <p><b>不设占位值</b>：SQL Server 的 XML 计划形态（{@code SET SHOWPLAN_XML}）本插件未接入，
 * 故不预留 {@code XML} 常量——仓内守卫 {@code EnumReachabilityGuardTest} 判定「声明了却零产出」
 * 为死常量（消费者恒走不到），且接入时补一个常量与一条表项的成本远低于长期维护一个死分支。</p>
 *
 * <p>移植注记：master 侧为 {@code ExplainSupport.kt} 内顶层 {@code enum class ExplainOutputFormat}；
 * Java 一个文件只能有一个 public 顶层类型，故按 k2j 既定惯例独立成件（同 {@link IdentifierCaseSource}）。</p>
 */
public enum ExplainOutputFormat {
    TABLE,
    TEXT,
    JSON,
}
