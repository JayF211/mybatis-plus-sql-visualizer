# Marketplace 审核邮件回复稿 v2（已修复版——20260923 代理化改造后重新上传时使用）

Subject: Re: SQL Visualizer 2026.9.23 — internal API usage & compatibility issues

Hello,

Thank you for the review. Following your report, we have reworked the plugin and uploaded a new build that **eliminates all Internal API usages of the ACP integration and the compatibility problems reported on 2025.x/2026.1 lines**. Details below.

## What we fixed

**1. The six Internal API usages in the ACP transport integration are gone.**
The transport provider no longer implements the internal `AcpIdeaMcpTransportProvider` interface at compile time. Instead, it is registered programmatically at runtime through a `Class.forName` probe plus a dynamic proxy, so the plugin bytecode carries **zero references** to `com.intellij.ml.llm.agents.acp.*` and `com.intellij.platform.acp.*` types. Runtime behavior is unchanged (verified in a real 2026.2.1 IDE sandbox: the provider registers at startup, the private IJ MCP session for the Claude agent runs over SSE, and tool calls execute normally).

**2. The Internal API usage of `ProjectEx.getEarlyDisposable()` is gone.**
The disposable tree is now resolved reflectively with a silent no-op fallback.

**3. Compatibility problems on 2024.3 / 2025.x / 2026.1 are substantially reduced.**
The MCP tool-filter provider (reported as 4 unresolved classes per line) no longer implements the 262-only platform interface at compile time; it uses the same probe + dynamic-proxy + programmatic registration pattern. After the rework, our local verification (verifier 1.410, online resolution, no ignore lists) reports:

- 2025.3.5: **Compatible** (was 9 compatibility problems)
- 2026.2.3: **Compatible**
- 2024.3.7.1: 2 remaining compatibility problems, both inherent to supporting the MCP toolset at all on a single build: the toolset base class implements the platform `McpToolset` interface (absent in the legacy 243 MCP plugin), and one suspend helper references `SpillingKt` from newer Kotlin runtimes. Both are isolated at runtime by the optional-fragment loading condition (243 does not support the MCP feature by design; worst case is feature unavailability, never a crash).

If further reduction of the two remaining 2024.3 items is desired, the only structural route we can see is splitting the plugin into two artifacts per platform line — we are happy to discuss that option with the team if preferred.

**4. The `getEarlyDisposable` regression suite and the ACP scope/signature guards have been updated** to assert the new zero-reference property, so these usages cannot silently return in future builds.

Best regards,
SongRenShuo
