package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * R52 方言能力矩阵 + 执行前自检（O-106 产品化，V79 三前提全过）。
 *
 * <p>方言能力差「只在撞上时可见」：PG 项目给 DELETE 拼 {@code last("LIMIT 10")} 时预览静默省略该尾段
 * （{@link Dialect#getSupportsDeleteLimit} 基类 false，仅 MySQL 族覆写），用户照预览去控制台执行即无界删除；
 * Oracle/SQL Server/DM 无原生 upsert 渲染 → 预览降级纯 INSERT，用户也无从得知「本方言不支持」。
 * 本对象把「当前方言支持什么」变成可枚举的事实，供两处消费：</p>
 * <ol>
 * <li><b>呈现层</b>（矩阵本身）：预览面板 meta 悬停 + 诊断报告（R50）的方言能力段；</li>
 * <li><b>执行确认链</b>（{@link #gapsFor}）：SQL 用到了本方言不支持的形态时，确认弹窗追加一行发现。</li>
 * </ol>
 *
 * <h2>与 {@link ExplainGate} 的分工（V79③ 证伪结论）</h2>
 * {@code ExplainGate.planExplainSubmit} 只覆盖 EXPLAIN 一件事（首关键字白名单 + 方言是否接入），
 * <b>不是</b>通用能力闸门——DELETE LIMIT / UPSERT / identity 包装三类缺口它一概不看。故本对象
 * 提供 {@link #gapsFor} 承接非 EXPLAIN 缺口的执行前自检；EXPLAIN 缺口沿用 ExplainGate 既有提示
 * （点「执行计划」时的轻通知），{@link #matrix} 仍单列 EXPLAIN 行以保矩阵完整，但不重复进确认弹窗。
 *
 * <h2>能力判定口径（全部读 {@link Dialect} 接口自身的真值，不另造表——防伪能力）</h2>
 * <ul>
 * <li>EXPLAIN：读 {@link ExplainSupport} 表的 supported；</li>
 * <li>DELETE_LIMIT：{@link Dialect#getSupportsDeleteLimit()}；</li>
 * <li>UPSERT：{@link Dialect#renderUpsert} 或 {@link Dialect#renderUpsertStatement} 非 null；</li>
 * <li>IDENTITY_WRAP：{@link Dialect#renderInsertWrapper} 非 null（DM8/SQL Server 需要包装）。</li>
 * </ul>
 *
 * <p>纯逻辑层：只依赖 Dialect/ExplainSupport（无 IDE 类型），普通 JUnit 可直接覆盖。</p>
 */
public final class DialectCapabilities {

    private DialectCapabilities() {
    }

    /** 方言能力项（矩阵列）。 */
    public enum Capability {
        /** 查询执行计划（EXPLAIN）。 */
        EXPLAIN,
        /** DELETE 语句支持 {@code LIMIT n} 尾段。 */
        DELETE_LIMIT,
        /** 有原生 upsert 渲染形态（后缀或整语句）。 */
        UPSERT,
        /** 显式写自增列需要 identity 包装语句。 */
        IDENTITY_WRAP,
    }

    /**
     * 单行能力。
     *
     * @param capability 能力项
     * @param supported  本方言是否支持
     * @param reasonKey  不支持时的原因文案键（支持的方言为 null）；键值见 bundle {@code dialect.capability.reason.*}
     */
    public record CapabilityRow(Capability capability, boolean supported, @Nullable String reasonKey) {
    }

    /**
     * 执行前自检发现的能力缺口。
     *
     * @param capability  缺口能力
     * @param dialectName 当前方言显示名（进文案）
     * @param messageKey  确认弹窗文案键（{@code execute.confirm.capability.*}）
     */
    public record CapabilityGap(Capability capability, String dialectName, String messageKey) {
    }

    /** 建当前方言的能力矩阵（顺序稳定＝{@link Capability} 声明序）。 */
    @NotNull
    public static List<CapabilityRow> matrix(@NotNull Dialect dialect) {
        return java.util.Arrays.stream(Capability.values())
                .map(cap -> {
                    boolean supported = isSupported(cap, dialect);
                    return new CapabilityRow(cap, supported, supported ? null : reasonKey(cap));
                })
                .toList();
    }

    /**
     * 列出本 SQL 用到的、而当前方言不支持的能力缺口（执行确认链用）。
     *
     * <p>只在<b>语句确实用到了该形态</b>时才报——{@code DELETE ... LIMIT 10} 撞上非 MySQL 族方言、
     * {@code ON DUPLICATE KEY UPDATE}/{@code ON CONFLICT}/{@code MERGE INTO} 撞上无 upsert 的方言、
     * identity 包装语句撞上无包装的方言。判据见 {@link #hasDeleteLimitTail}/{@link #hasUpsertClause}/
     * {@link #hasIdentityWrapper}。不在册：EXPLAIN 缺口（见类 KDoc 的分工说明）。</p>
     */
    @NotNull
    public static List<CapabilityGap> gapsFor(@NotNull String sql, @NotNull Dialect dialect) {
        if (sql.isBlank()) {
            return List.of();
        }
        Map<Capability, CapabilityGap> gaps = new LinkedHashMap<>();
        if (!dialect.getSupportsDeleteLimit() && hasDeleteLimitTail(sql)) {
            gaps.put(Capability.DELETE_LIMIT,
                    gap(Capability.DELETE_LIMIT, dialect, "execute.confirm.capability.deleteLimit"));
        }
        if (!isSupported(Capability.UPSERT, dialect) && hasUpsertClause(sql)) {
            gaps.put(Capability.UPSERT, gap(Capability.UPSERT, dialect, "execute.confirm.capability.upsert"));
        }
        if (!isSupported(Capability.IDENTITY_WRAP, dialect) && hasIdentityWrapper(sql)) {
            gaps.put(Capability.IDENTITY_WRAP,
                    gap(Capability.IDENTITY_WRAP, dialect, "execute.confirm.capability.identityWrap"));
        }
        return List.copyOf(gaps.values());
    }

    /** 本方言是否支持某能力（矩阵与缺口判定唯一入口）。 */
    public static boolean isSupported(@NotNull Capability capability, @NotNull Dialect dialect) {
        return switch (capability) {
            case EXPLAIN -> {
                ExplainSupport.Entry entry = ExplainSupport.entryFor(dialect.getName());
                yield entry != null && entry.supported();
            }
            case DELETE_LIMIT -> dialect.getSupportsDeleteLimit();
            case UPSERT -> dialect.renderUpsert("k", List.of(), IdentifierCaseSource.ENTITY) != null
                    || dialect.renderUpsertStatement("t", List.of(), "k", IdentifierCaseSource.ENTITY) != null;
            case IDENTITY_WRAP -> dialect.renderInsertWrapper("t", "", IdentifierCaseSource.ENTITY) != null;
        };
    }

    /**
     * 矩阵呈现文本（多行，供 meta 悬停 / 诊断报告的方言能力段直接引用）。
     *
     * <p>每行 {@code - <能力名>: <state>}，state = 支持说明或按 reasonKey 取的降级原因
     * （i18n 走 bundle，双语随语言切换）——呈现层不自行拼词，单一事实源在本对象。</p>
     */
    @NotNull
    public static String summaryText(@NotNull Dialect dialect) {
        StringBuilder sb = new StringBuilder();
        for (CapabilityRow row : matrix(dialect)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String label = MpSqlVisualizerBundle.message(labelKey(row.capability()));
            String state = row.reasonKey() != null
                    ? MpSqlVisualizerBundle.message(row.reasonKey())
                    : MpSqlVisualizerBundle.message("dialect.capability.supported");
            sb.append("- ").append(label).append(": ").append(state);
        }
        return sb.toString();
    }

    /** 能力名文案键（矩阵呈现用）。 */
    private static String labelKey(@NotNull Capability capability) {
        return switch (capability) {
            case EXPLAIN -> "dialect.capability.label.explain";
            case DELETE_LIMIT -> "dialect.capability.label.deleteLimit";
            case UPSERT -> "dialect.capability.label.upsert";
            case IDENTITY_WRAP -> "dialect.capability.label.identityWrap";
        };
    }

    /** 不支持原因文案键（矩阵呈现用）。 */
    private static String reasonKey(@NotNull Capability capability) {
        return switch (capability) {
            case EXPLAIN -> "dialect.capability.reason.explain";
            case DELETE_LIMIT -> "dialect.capability.reason.deleteLimit";
            case UPSERT -> "dialect.capability.reason.upsert";
            case IDENTITY_WRAP -> "dialect.capability.reason.identityWrap";
        };
    }

    /**
     * 语句里是否出现 DELETE 的 {@code LIMIT n} 尾段。
     *
     * <p>逐条拆分（{@link SqlStatementSplitter#splitStripped}，注释/字符串内分号安全）后只看首关键字为
     * DELETE 的语句，且要求 {@code LIMIT} 后紧随数字（词边界）——{@code UPDATE ... LIMIT} 不在本能力范围
     * （MySQL 亦不支持），{@code LIMIT} 作为列名/字面量（{@code WHERE note = 'limit'}）由引号内文本
     * 自然排除在词边界判定外。</p>
     */
    public static boolean hasDeleteLimitTail(@NotNull String sql) {
        for (String statement : SqlStatementSplitter.splitStripped(sql)) {
            if (!firstWordIs(statement, "DELETE")) {
                continue;
            }
            if (DELETE_LIMIT_TAIL.matcher(maskLiterals(statement)).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 语句里是否出现 upsert 冲突子句形态：{@code ON DUPLICATE KEY UPDATE}（MySQL 族/openGauss）、
     * {@code ON CONFLICT}（PG 族）、或整语句 {@code MERGE INTO}（H2 形态；DM/Oracle 的 MERGE 也归此）。
     *
     * <p>词面判定前先屏蔽字符串字面量（'…' / "…" / 反引号 / […]）——否则
     * {@code WHERE note = 'on conflict'} 这类常量值会误判（诊断测试实证：'limit 10' 曾误命中
     * DELETE LIMIT 尾段规则）。</p>
     */
    public static boolean hasUpsertClause(@NotNull String sql) {
        String masked = maskLiterals(sql);
        if (ON_DUPLICATE.matcher(masked).find() || ON_CONFLICT.matcher(masked).find()) {
            return true;
        }
        for (String statement : SqlStatementSplitter.splitStripped(sql)) {
            if (firstWordIs(statement, "MERGE")) {
                return true;
            }
        }
        return false;
    }

    /** identity 包装语句是否在场（复用既有精确判据，只认插件自产的 {@code SET IDENTITY_INSERT <表> ON|OFF;}）。 */
    public static boolean hasIdentityWrapper(@NotNull String sql) {
        return IdentityInsertStatements.containsStatement(sql);
    }

    private static CapabilityGap gap(Capability capability, Dialect dialect, String key) {
        return new CapabilityGap(capability, dialect.getName(), key);
    }

    /** 首关键字（大写）判定；首词取连续字母段（与执行链分类器同口径的轻量版，够用即可）。 */
    private static boolean firstWordIs(String statement, String upper) {
        String trimmed = statement.stripLeading();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (!Character.isLetter(c)) {
                break;
            }
            word.append(c);
        }
        return word.toString().equalsIgnoreCase(upper);
    }

    /**
     * 屏蔽字符串字面量与引号标识符（内容替换为空格，保持偏移稳定）——词面规则只在结构文本上跑，
     * 避免 {@code WHERE note = 'limit 10'} / {@code = 'on conflict'} 这类常量值误命中。
     * 与 execute 包 {@code SqlTypeClassifier.maskQuoted} 同口径（各自私有副本，两处模块边界不同）。
     */
    private static String maskLiterals(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            Character close = switch (c) {
                case '\'' -> '\'';
                case '"' -> '"';
                case '`' -> '`';
                case '[' -> ']';
                default -> null;
            };
            if (close == null) {
                sb.append(c);
                i++;
                continue;
            }
            sb.append(' ');
            i++;
            while (i < sql.length()) {
                if (close != '[' && sql.charAt(i) == close && i + 1 < sql.length() && sql.charAt(i + 1) == close) {
                    sb.append("  ");
                    i += 2;
                    continue;
                }
                if (sql.charAt(i) == close) {
                    sb.append(' ');
                    i++;
                    break;
                }
                sb.append(' ');
                i++;
            }
        }
        return sb.toString();
    }

    /** DELETE 的 LIMIT 尾段：LIMIT 后可选空白再跟数字（词边界，避免 LIKE 'limit' 误判）。 */
    private static final Pattern DELETE_LIMIT_TAIL =
            Pattern.compile("\\bLIMIT\\s+\\d", Pattern.CASE_INSENSITIVE);

    /** MySQL 族 upsert 后缀。 */
    private static final Pattern ON_DUPLICATE =
            Pattern.compile("\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b", Pattern.CASE_INSENSITIVE);

    /** PG 族 upsert 后缀。 */
    private static final Pattern ON_CONFLICT =
            Pattern.compile("\\bON\\s+CONFLICT\\b", Pattern.CASE_INSENSITIVE);
}
