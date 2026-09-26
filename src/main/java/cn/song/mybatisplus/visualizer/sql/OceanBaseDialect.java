// T-SyncK2JMaster187 自 master sql/Dialect.kt（OceanBaseDialect object）移植
// （master 的 Kotlin 源把全部方言 object 写在同一 Dialect.kt；Java 侧 public 顶层类须独立文件，沿用 k2j 既有布局）
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * OceanBase（MySQL 模式）方言（T-DialectXcFamily / O-63）：同 MySQL 的 {@code LIMIT [offset,] count}。
 *
 * <p>标识符引用反引号 {@code `x`}。upsert 同 MySQL 的 {@code ON DUPLICATE KEY UPDATE ... VALUES()} 形态。
 * D1=A 定案：<b>只接 MySQL 模式</b>（主流装机面），Oracle 模式不做。</p>
 *
 * <p>验证来源（<b>文档级</b>，无真机实例——环境受限未验证，真机核对后增量）：</p>
 * <ul>
 *  <li>JDBC URL：OceanBase Connector/J 官方《Database URL》{@code jdbc:oceanbase://host:port/db}（V2.4.16）；</li>
 *  <li>EXPLAIN：官方 V4.x《SQL 执行计划简介》{@code EXPLAIN [explain_type] dml_statement}，
 *      {@code FORMAT = {TRADITIONAL | JSON}}，缺省 TRADITIONAL <b>表格行</b> → 与 MySQL 同形态
 *      （{@link ExplainSupport} 表登记 TABLE 档）；</li>
 *  <li>兼容面：官方《与 MySQL 兼容性对比》声明 MySQL 5.7/8.0 绝大部分语法兼容（反引号 / LIMIT /
 *      {@code ON DUPLICATE KEY UPDATE} 官方《唯一键冲突》在册）。</li>
 * </ul>
 *
 * <p>保留字：沿用 {@link SqlKeywords#MYSQL_RESERVED}（MySQL 8.0 全量）为基线——OB MySQL 模式的兼容性
 * 声明覆盖语法面，OB 特有保留字差异未逐词核对（真机核对后增量，参照 P-立项-05 的 DM/KingBase 黄金清单
 * 先例）。折叠方向：推定 {@link IdentifierFolding#NONE}（MySQL 模式大小写不敏感兼容面）——真机打脸按
 * H2 先例订正（接口默认值即 NONE，不覆写）。</p>
 */
public final class OceanBaseDialect implements Dialect {

    public static final OceanBaseDialect INSTANCE = new OceanBaseDialect();

    private OceanBaseDialect() {}

    @Override
    public String getName() {
        return "OceanBase";
    }

    @Override
    public String renderLimit(LimitClause limit) {
        return Dialect.renderMySqlStyleLimit(limit);
    }

    @Override
    public boolean getSupportsDeleteLimit() {
        return true;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier + "`";
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.MYSQL_RESERVED;
    }

    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns,
                               IdentifierCaseSource caseSource) {
        return Dialect.renderMySqlStyleUpsert(this, updateColumns, caseSource);
    }
}
