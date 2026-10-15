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
    ): DialectDecisionEngine.Decision {
        val effectiveIdeaDialect = snapshot?.effectiveDialectId?.let(IdeaSqlContextBridge::pluginDialectName)
        val platformCandidates = snapshot?.finalCandidates.orEmpty().map {
            DialectDecisionEngine.Candidate(it.stableId, it.dialectName)
        }
        val explicit = snapshot?.scopeOrigin == IdeaSqlContextBridge.ScopeOrigin.PROVIDED ||
            snapshot?.scopeOrigin == IdeaSqlContextBridge.ScopeOrigin.CONFIGURED_RESOLUTION
        val scopeSource = when (snapshot?.scopeOrigin) {
            IdeaSqlContextBridge.ScopeOrigin.PROVIDED -> DialectDecisionEngine.Source.IDEA_PROVIDER
            IdeaSqlContextBridge.ScopeOrigin.CONFIGURED_RESOLUTION ->
                DialectDecisionEngine.Source.IDEA_CONFIGURED_SCOPE
            else -> DialectDecisionEngine.Source.IDEA_SCOPE
        }
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
        ))
    }
}
