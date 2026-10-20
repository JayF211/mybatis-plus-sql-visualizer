package cn.song.mybatisplus.visualizer.sql;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 DatabaseTools 平台事实按 provider/configured/default 的真实优先级接入统一方言决策。
 *
 * <p>master 的 {@code internal object IdeaSqlDialectDecision} 的 Java 承载（全 static 形态；
 * Kotlin 默认参数 → 便捷重载）。</p>
 */
public final class IdeaSqlDialectDecision {

    private IdeaSqlDialectDecision() {
    }

    public static DialectDecisionEngine.Decision decide(
            @Nullable String manualDialect,
            @Nullable String capturedDialect,
            @Nullable IdeaSqlContextBridge.Snapshot snapshot,
            @Nullable DialectDecisionEngine.Candidate learnedModule,
            List<DialectDecisionEngine.Candidate> fallbackProjectCandidates,
            @Nullable String lastAgentDialect,
            boolean singleModuleProven,
            @Nullable String boundDataSourceDialect) {
        // 无法证明配置 scope 的候选边界时不得借有效方言/已学绑定/项目候选绕过。
        // 手动与事件精确事实仍走原优先级；真实 provider 在桥中优先，不进入此分支。
        if (snapshot != null && snapshot.configuredScopePresent()
                && snapshot.scopeOrigin() == IdeaSqlContextBridge.ScopeOrigin.UNAVAILABLE) {
            DialectDecisionEngine.Decision decision = DialectDecisionEngine.decide(
                    new DialectDecisionEngine.Input(manualDialect, capturedDialect));
            if (decision.status() == DialectDecisionEngine.Status.UNAVAILABLE) {
                return decision.withSource(DialectDecisionEngine.Source.IDEA_CONFIGURED_SCOPE);
            }
            return decision;
        }
        String effectiveIdeaDialect = snapshot != null && snapshot.effectiveDialectId() != null
                ? IdeaSqlContextBridge.pluginDialectName(snapshot.effectiveDialectId())
                : null;
        List<DialectDecisionEngine.Candidate> platformCandidates = new ArrayList<>();
        if (snapshot != null) {
            for (IdeaSqlContextBridge.DataSourceCandidate candidate : snapshot.finalCandidates()) {
                platformCandidates.add(new DialectDecisionEngine.Candidate(candidate.stableId(),
                        candidate.valid() ? candidate.dialectName() : null));
            }
            // 无 UUID 候选只以本次决策的临时键占位，既防虚假唯一，也绝不进入模块持久绑定。
            for (int i = 1; i <= snapshot.unidentifiedCandidateCount(); i++) {
                platformCandidates.add(new DialectDecisionEngine.Candidate("unidentified:" + i, null));
            }
        }
        boolean explicit = snapshot != null && snapshot.scopeOrigin() == IdeaSqlContextBridge.ScopeOrigin.PROVIDED;
        DialectDecisionEngine.Source scopeSource = explicit
                ? DialectDecisionEngine.Source.IDEA_PROVIDER
                : DialectDecisionEngine.Source.IDEA_SCOPE;
        List<DialectDecisionEngine.Candidate> projects =
                snapshot != null && snapshot.scopeOrigin() == IdeaSqlContextBridge.ScopeOrigin.DEFAULT_ALL_DATA_SOURCES
                        && !platformCandidates.isEmpty()
                        ? platformCandidates
                        : fallbackProjectCandidates;
        return DialectDecisionEngine.decide(new DialectDecisionEngine.Input(
                manualDialect,
                capturedDialect,
                explicit,
                explicit ? platformCandidates : List.of(),
                scopeSource,
                effectiveIdeaDialect,
                DialectDecisionEngine.Source.IDEA_EFFECTIVE_DIALECT,
                learnedModule,
                projects,
                lastAgentDialect,
                singleModuleProven,
                boundDataSourceDialect));
    }

    /** 便捷重载：learnedModule/项目候选/agent 事实缺省（Kotlin 默认参数）。 */
    public static DialectDecisionEngine.Decision decide(
            @Nullable String manualDialect,
            @Nullable String capturedDialect,
            @Nullable IdeaSqlContextBridge.Snapshot snapshot) {
        return decide(manualDialect, capturedDialect, snapshot, null, List.of(), null, false, null);
    }
}
