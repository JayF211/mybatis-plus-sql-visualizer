# Marketplace 审核邮件回复稿（复制后直接 reply 那封邮件）

Subject: Re: SQL Visualizer 2026.9.23 — internal API usage & compatibility issues

Hello,

Thank you for the review. We'd like to respond to the two categories of issues reported by the Plugin Verifier for SQL Visualizer 2026.9.23.

## 1. Internal API usages (7)

**6 of 7 — ACP transport provider (AcpIdeaMcpTransportProviderImpl):**
These usages exist because the class is an extension implementation registered against the platform extension point `com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider` (in the optional fragment `acp-transport-optional.xml`, loaded only when the AI Assistant plugin is present). The EP's interface `AcpIdeaMcpTransportProvider` and its types are annotated `@ApiStatus.Internal` upstream, but implementing this EP is the only way to provide the transport integration, and JetBrains' own `AcpCopilotIdeaMcpTransportProvider` is implemented in exactly the same way against the same internal types. The whole fragment is optional-isolated: without AI Assistant installed, the fragment is never loaded and none of these classes are linked.

**1 of 7 — ProjectEx.getEarlyDisposable():**
This usage follows the platform KDoc's own guidance for this API ("In unit test mode light project is not disposed, but this disposable is disposed for each test. So, you don't need to have another disposable and can use this one instead."). There is no public equivalent that disposes at the same point of the project teardown sequence, and the main project disposable does not fire for light projects between tests. The call is defensive (Disposer.tryRegister + runCatching, no-op fallback), so even if the API changes, the feature degrades silently.

If the team prefers, we are happy to migrate `getEarlyDisposable()` to a public alternative you can point us to. The ACP EP implementation, however, cannot be rewritten without dropping the feature, as implementing the EP interface is the only registration mechanism.

## 2. Compatibility problems on older IDE lines (2024.3 / 2025.x / 2026.1)

All reported unresolved classes come from **optional dependencies** (MCP Server plugin, AI Assistant) whose API surface differs across release lines. These references are isolated at runtime:

- The declaring fragments (`mcp-optional.xml`, `acp-transport-optional.xml`) are loaded only when the corresponding optional dependency is present; on older lines with older-shaped plugins the fragment is either not loaded or its extensions are instantiated under the platform's EP error isolation. The worst-case effect is that MCP/ACP features are unavailable on that version — which is the intended support matrix — never a crash.
- The 262-only filter-provider classes behind the compatibility problems on 253 are registered programmatically only after a `Class.forName` probe succeeds (262+), so on 253 they are never loaded.

We would also like to point out that the **IDE-based verification for 2026.3 EAP passed ("No issues occurred during the IDE run with the plugin installed")**, which we believe covers the one static unresolved-class warning reported for that build by verifier 1.410.

The plugin's core functionality (SQL preview, log capture, rollback, dialects, CRUD generation) targets the declared range since-build 243 and is covered by a full regression suite (5,883 tests, 0 failures) plus real-IDE sandbox verification on 2024.3.7.1 and 2026.2.1.

If any of these can be resolved with a specific public API you can point us to, we will gladly make the change and upload a new build. Otherwise, we kindly ask that the submission proceed to review.

Best regards,
SongRenShuo
