// T-SyncK2JMaster309 自 execute/ExecuteConfirmMessages.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * T-UnboundedWriteGuard：执行确认消息构建（纯函数，零 Swing 依赖可直接测）。
 *
 * <p>消费方三处确认流共用（LogToSqlPanel.executeStatement / SqlPreviewPanel.executeSql /
 * SqlPreviewPanel.executeAdhocSql）——同一对话框形态只换 message 文案：</p>
 * <ul>
 *  <li>无危险发现：文案逐字节走既有 {@code execute.confirm.dml.message}（频率/形态零变化）；</li>
 *  <li>有危险发现：摘要行 + 按类别去重的发现清单（每类一行，文案中性说事实）。</li>
 * </ul>
 *
 * <p>不新增弹窗形态、不新增步骤（弹窗克制红线 {@code docs/设计规范-弹窗克制红线.md}）。</p>
 */
public final class ExecuteConfirmMessages {

    public static final ExecuteConfirmMessages INSTANCE = new ExecuteConfirmMessages();
    private ExecuteConfirmMessages() {}

    /** 无危险发现 → 既有文案；有 → 升级为发现清单。 */
    @org.jetbrains.annotations.NotNull
    public static String build(@org.jetbrains.annotations.NotNull String sql, @org.jetbrains.annotations.NotNull String keyword) {
        UnboundedWriteDetector.WriteRisk risk = UnboundedWriteDetector.detect(sql);
        if (!risk.isDangerous()) {
            return MpSqlVisualizerBundle.message("execute.confirm.dml.message", keyword);
        }
        // Kotlin: risk.kinds().sorted().joinToString("\n") — 排序去重后逐类一行
        Set<UnboundedWriteDetector.FindingKind> sorted = new TreeSet<>(risk.kinds());
        List<String> lines = new ArrayList<>();
        for (UnboundedWriteDetector.FindingKind kind : sorted) {
            lines.add("• " + MpSqlVisualizerBundle.message(messageKeyOf(kind)));
        }
        return MpSqlVisualizerBundle.message("execute.confirm.unbounded.summary", keyword, String.join("\n", lines));
    }

    /** 发现类别 → 文案键（单一映射点；键值见 bundle execute.confirm.unbounded.* 段）。 */
    @org.jetbrains.annotations.NotNull
    private static String messageKeyOf(@org.jetbrains.annotations.NotNull UnboundedWriteDetector.FindingKind kind) {
        switch (kind) {
            case MISSING_WHERE: return "execute.confirm.unbounded.missingWhere";
            case TAUTOLOGY: return "execute.confirm.unbounded.tautology";
            case OR_TAUTOLOGY: return "execute.confirm.unbounded.orTautology";
            case LIKE_WILDCARD: return "execute.confirm.unbounded.likeWildcard";
            case NO_COLUMN_REF: return "execute.confirm.unbounded.noColumnRef";
            case SUBQUERY: return "execute.confirm.unbounded.subquery";
            case PARSE_FAILED: return "execute.confirm.unbounded.parseFailed";
            default: throw new IllegalArgumentException("unknown kind: " + kind);
        }
    }
}
