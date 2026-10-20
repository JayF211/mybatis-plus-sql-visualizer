// T-SyncK2JMaster187 自 master sql/Dialect.kt（OpenGaussDialect object）移植
// （master 的 Kotlin 源把全部方言 object 写在同一 Dialect.kt；Java 侧 public 顶层类须独立文件，沿用 k2j 既有布局）
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * openGauss 方言（T-DialectXcFamily / O-63）：PG 族形态——{@code LIMIT {count} OFFSET {offset}}、
 * 双引号引用、{@link IdentifierFolding#LOWER} 折叠。D2=A 定案：GaussDB（华为云）与 openGauss 社区版
 * 共用一个 object，两个 JDBC URL 前缀（{@code jdbc:opengauss://} 与 {@code jdbc:gaussdb://}）都归本方言
 * （两前缀各有官方文档出处，见 {@link UrlSubprotocolDialects}）。
 *
 * <p><b>验证来源（openGauss 引擎级，DBX MCP 只读通道，GaussDB/openGauss 5.0.0，2026-09-23）</b>：</p>
 * <ul>
 *  <li>折叠 LOWER：{@code SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES} → 455 行（裸大写折小写命中）、
 *      {@code FROM information_schema."TABLES"} → {@code relation "information_schema.TABLES" does not
 *      exist on gaussdb}；</li>
 *  <li>反引号不可用：{@code SELECT 1 AS `x`} → {@code syntax error}（双引号族实锤）；</li>
 *  <li>LIMIT 形态：{@code SELECT 1 LIMIT 1 OFFSET 0} → 1 行；{@code SELECT 1 LIMIT 1, 2} → <b>0 行</b>——
 *      openGauss 把 {@code LIMIT a, b} 按 <b>PG 语义</b>（OFFSET a LIMIT b）解析，MySQL 形态会被静默错解，
 *      故分页必须渲染 PG 形态（真机对照读数）；</li>
 *  <li>EXPLAIN：{@code EXPLAIN (FORMAT JSON) SELECT 1} → 单列 {@code QUERY PLAN} JSON 文本（JSON 档实锤）；
 *      {@code EXPLAIN SELECT 1} → 文本计划树。</li>
 * </ul>
 *
 * <p><b>upsert 特例（真机对照对实锤）</b>：openGauss 的原生 upsert 是 <b>MySQL 形态</b>
 * {@code ON DUPLICATE KEY UPDATE col = VALUES(col)}（EXPLAIN 规划期通过），而 PG 的
 * {@code ON CONFLICT (k) DO UPDATE} 直接语法错误——「PG 族=ON CONFLICT」的族推定在本方言被打脸
 * （KingBase 同为 PG 系但 upsert 相反），照抄 KingBase 先例会产出语法错误 SQL。
 * 官方文档同向（openGauss《INSERT》on_duplicate_key 文法，{@code VALUES(column_name)} 引用在册）。</p>
 *
 * <p>保留字：沿用 {@link SqlKeywords#PG_RESERVED}（PG 官方附录全量）为基线——openGauss 内核源自 PG 9.2
 * 演化，openGauss 特有保留字差异未逐词核对（真机核对后增量）。</p>
 */
public final class OpenGaussDialect implements Dialect {

    public static final OpenGaussDialect INSTANCE = new OpenGaussDialect();

    private OpenGaussDialect() {}

    @Override
    public String getName() {
        return "openGauss";
    }

    /** R36：openGauss 取 PG 形态 `BEGIN`（族推定；本方言 upsert 曾反族，真机核对后增量）（证据三档见 Dialect#getTransactionBegin）。 */
    @Override
    public String getTransactionBegin() {
        return "BEGIN";
    }

    @Override
    public String renderLimit(LimitClause limit) {
        return Dialect.renderPgStyleLimit(limit);
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public Set<Character> getIdentifierQuotes() {
        return Set.of('"');
    }

    /**
     * 折叠方向 LOWER：openGauss 5.0.0 真机实测（2026-09-23，DBX 只读通道），与 PG/KingBase 同族
     * ——目录来源「含大写字母即加引号」。
     */
    @Override
    public IdentifierFolding getIdentifierFolding() {
        return IdentifierFolding.LOWER;
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.PG_RESERVED;
    }

    /**
     * MySQL 形态 upsert（真机对照对实锤，见类 KDoc）——复用 {@link Dialect#renderMySqlStyleUpsert}，
     * 列名引用符号随本方言的双引号（{@code VALUES()} 引用形态 openGauss 官方 INSERT 文法在册）。
     */
    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns,
                               IdentifierCaseSource caseSource) {
        return Dialect.renderMySqlStyleUpsert(this, updateColumns, caseSource);
    }
}
