package cn.song.mybatisplus.visualizer.sql

/** 将 DatabaseTools 平台事实按 provider/configured/default 的真实优先级接入统一方言决策。 */
internal object IdeaSqlDialectDecision {
    fun decide(
        manualDialect: String? = null,
        capturedDialect: String? = null,
        snapshot: IdeaSqlContextBridge.Snapshot?,
        learnedModule: DialectDecisionEngine.Candidate? = null,
        fallbackProjectCandidates: List<DialectDecisionEngine.Candidate> = emptyList(),
        lastAgentDialect: String? = null,
        singleModuleProven: Boolean = false,
        boundDataSourceDialect: String? = null,
    ): DialectDecisionEngine.Decision {
        // 无法证明配置 scope 的候选边界时不得借有效方言/已学绑定/项目候选绕过。
        // 手动与事件精确事实仍走原优先级；真实 provider 在桥中优先，不进入此分支。
        if (snapshot?.configuredScopePresent == true &&
            snapshot.scopeOrigin == IdeaSqlContextBridge.ScopeOrigin.UNAVAILABLE) {
            val decision = DialectDecisionEngine.decide(DialectDecisionEngine.Input(
                manualDialect = manualDialect, capturedDialect = capturedDialect,
            ))
            return if (decision.status == DialectDecisionEngine.Status.UNAVAILABLE)
                decision.copy(source = DialectDecisionEngine.Source.IDEA_CONFIGURED_SCOPE) else decision
        }
        val effectiveIdeaDialect = snapshot?.effectiveDialectId?.let(IdeaSqlContextBridge::pluginDialectName)
        val platformCandidates = snapshot?.finalCandidates.orEmpty().map { candidate ->
            DialectDecisionEngine.Candidate(candidate.stableId,
                candidate.dialectName.takeIf { candidate.valid })
        } + (1..(snapshot?.unidentifiedCandidateCount ?: 0)).map {
            // 无 UUID 候选只以本次决策的临时键占位，既防虚假唯一，也绝不进入模块持久绑定。
            DialectDecisionEngine.Candidate("unidentified:$it", null)
        }
        val explicit = snapshot?.scopeOrigin == IdeaSqlContextBridge.ScopeOrigin.PROVIDED
        val scopeSource = if (explicit) DialectDecisionEngine.Source.IDEA_PROVIDER
            else DialectDecisionEngine.Source.IDEA_SCOPE
        val projects = if (snapshot?.scopeOrigin == IdeaSqlContextBridge.ScopeOrigin.DEFAULT_ALL_DATA_SOURCES &&
            platformCandidates.isNotEmpty()) platformCandidates else fallbackProjectCandidates
        return DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            manualDialect = manualDialect,
            capturedDialect = capturedDialect,
            explicitScope = explicit,
            scopeCandidates = if (explicit) platformCandidates else emptyList(),
            scopeSource = scopeSource,
            ideaDialect = effectiveIdeaDialect,
            ideaDialectSource = DialectDecisionEngine.Source.IDEA_EFFECTIVE_DIALECT,
            learnedModule = learnedModule,
            projectCandidates = projects,
            lastAgentDialect = lastAgentDialect,
            singleModuleProven = singleModuleProven,
            boundDataSourceDialect = boundDataSourceDialect,
        ))
    }
}
