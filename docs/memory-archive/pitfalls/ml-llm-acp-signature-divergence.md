---
name: ml-llm-acp-signature-divergence
description: ml-llm 两份制品的 AcpAgentId 包路径不同，任何护栏断言不可能对两处同时成立；且未合并分支已把桩迁到新包
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bd923173-5e98-4d8b-ac6b-b59251d2d90d
---

本机两份 `intellij.ml.llm.agents.acp.jar` 的 `AcpIdeaMcpTransportProvider.getTransport` 参数类型**不同包**（20260920 解 jar 常量池实测）：

| 制品 | 版本 | 描述符 |
|---|---|---|
| `%APPDATA%/…/IntelliJIdea2026.2/plugins/ml-llm/` | 262.10968.97（宿主 IU-262.10968.63） | `Lcom/intellij/ml/llm/agents/acp/api/AcpAgentId;`（与 `src/mcpStub` 桩一致） |
| `~/.gradle/caches/9.7.0/transforms/…/com.intellij.ml.llm-262.9437.276/` | 262.9437.276 | `Lcom/intellij/platform/acp/AcpAgentId;`，且该 jar **不含** `ml/llm/agents/acp/api/AcpAgentId` 类（`jar tf` 计数 0） |

**Why**：T-MlLlmSignatureGuard（O-41）的四项签名断言里，「AcpAgentId 为接口」「getTransport 参数类型」两项**不可能对两处制品同时成立**，其兜底口径（「某处缺 ACP jar 记 MISSING」）覆盖不到「jar 在、类包不同」这种情形 → 验收 1 按现文案不可达。本机共 3 处 ml-llm 安装（`IntelliJIdea2025.3` 只有 `…acp.embeddedMcp.jar`/`…acp.json.jar`，无 agents.acp.jar），计划写的「只有两处」也不成立。

**How to apply**：动 ml-llm 签名护栏前先定桩归属——分支 `t-gif-rec-mcp` 的 `102b44c`（20260920 01:10）**已把桩迁到 `com.intellij.platform.acp`** 并在分支版 `build.gradle.kts:165` 复启 `plugin("com.intellij.ml.llm","262.9437.276")`（master 上该声明不在，只有注释）。**⚠️ 20260921 实测订正：「未合并分支」「先定桩归属再动」「两路不可并行」三条口径均已失效**——`102b44c` 已是 master 的祖先（`git merge-base --is-ancestor 102b44c master` 成立，`git branch --contains` 列出 dev/master/全部 t-* 分支），`t-gif-rec-mcp` 整体也已完全合入 master（`master..t-gif-rec-mcp` 为空），该分支与工作树可直接清理。桩归属已由 master 落定，护栏的断言面按 `com.intellij.platform.acp` 一侧收敛即可，不存在与在途分支的正面冲突。相关：[[project-dynamic-unload-barriers]]（跨版本签名护栏登记定例）、[[project-pending-plans]]。
