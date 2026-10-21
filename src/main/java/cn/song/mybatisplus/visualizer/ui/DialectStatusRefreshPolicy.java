// 自 master ui/DialectStatusRefresh.kt 的 DialectStatusRefreshPolicy object 移植（T-DialectStatusLiveRefresh 方案 A）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-DialectStatusLiveRefresh 方案 A/B：方言状态行随执行/绑定即时刷新——决策替换纯函数半边。
 *
 * <p>背景：方言决策在预览生成时算一次并固化进 {@code PreviewResult.dialectDecision}，执行 SQL 虽已通过
 * 提交闸写入模块数据源学习（{@code DataSourceSelectorConsole} 的 {@code remember}），但面板没有重算入口——
 * 头部停留在「候选方言不一致」，须关开预览才更新（真机实锤）。</p>
 *
 * <p>纯函数：决定新决策能否替换旧决策（仅升级、手动/冲突不被覆盖）。</p>
 */
public final class DialectStatusRefreshPolicy {

    private DialectStatusRefreshPolicy() {
    }

    private static int rank(@NotNull DialectDecisionEngine.Decision decision) {
        switch (decision.status()) {
            case MANUAL:
            case CONFLICT:
                return 3;
            case RESOLVED:
                return 2;
            case AMBIGUOUS:
                return 1;
            case UNAVAILABLE:
            default:
                return 0;
        }
    }

    /**
     * 新决策可替换旧决策时返回新值；null＝保持旧值。
     *
     * <p>规则：手动与冲突是终态（用户选择/安全阻断），刷新永不覆盖；其余按等级只升不降，
     * 同级且内容不同视为更新（如从模块学习升级到当前绑定源，证据更新鲜）。</p>
     */
    @Nullable
    public static DialectDecisionEngine.Decision pickStronger(
            @Nullable DialectDecisionEngine.Decision old,
            @NotNull DialectDecisionEngine.Decision fresh) {
        // 刷新链不产出手动/冲突态——手动=用户选择、冲突=安全阻断，只能由完整决策链生成，
        // 重算路径即便算出也绝不冒充（顺序在 old==null 短路之前，防 null 旧值时终态混入）。
        if (fresh.status() == DialectDecisionEngine.Status.MANUAL
                || fresh.status() == DialectDecisionEngine.Status.CONFLICT) {
            return null;
        }
        if (old == null) {
            return fresh;
        }
        if (old.status() == DialectDecisionEngine.Status.MANUAL
                || old.status() == DialectDecisionEngine.Status.CONFLICT) {
            return null;
        }
        int oldRank = rank(old);
        int freshRank = rank(fresh);
        if (freshRank > oldRank) {
            return fresh;
        }
        if (freshRank == oldRank && !fresh.equals(old)) {
            return fresh;
        }
        return null;
    }
}
