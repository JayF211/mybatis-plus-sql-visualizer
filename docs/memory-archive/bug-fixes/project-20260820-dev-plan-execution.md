---
name: project-20260820-dev-plan-execution
description: 20260820 全量优化与新功能开发计划——11 项立项、波次并行开发执行记录
metadata:
  node_type: memory
  type: project
  originSessionId: sess_73eef365-25dc-47f0-8066-2f969baccd17
---

2026-08-20 启动 11 项开发计划（总纲 `docs/开发计划/20260820-全量优化与新功能开发计划-总纲.md`），按价值/成本比分波次并行实施。

## 开发计划清单

| # | 项名 | 优先级 | 预估工时 | 状态 |
|---|------|:------:|----------|------|
| 1 | TenantLine 多租户拦截器 | 🔴 P0 | ~4.5h | 波次1 开发中 |
| 2 | XML customSqlSegment 文档同步 | ✅ | ~0.5h | 已完成（M26-B 已交付） |
| 3 | fill 关联填充（MPJ 1.5.1+） | 🟡 P1 | ~8h | 波次4 |
| 4 | SQL 性能分析建议 | 🟡 P1 | ~8.5h | 波次1 开发中 |
| 5 | 参数批量导入/历史召回 | 🟡 P1 | ~11h | 波次3 |
| 6 | 日志智能分组/高频折叠 | 🟡 P1 | ~11.4h | 波次3 |
| 7 | 批量 SQL→Java 转换 | 🟡 P1 | ~7.5h | 波次3 |
| 8 | 暗色主题适配 | 🟢 P2 | ~2.25h | 波次1 开发中 |
| 9 | 实体解析缓存增强 | 🟢 P2 | ~5.25h | 待排期 |
| 10 | gutter 图标防抖 | 🟢 P2 | ~1.25h | 波次1 开发中 |
| 11 | verifyPlugin 构建提速 | 🟢 P2 | ~2.25h | 波次2 |
| **合计** | | | **~62.4h** | |

## 波次规划（文件冲突分析）

- **波次1**（2026-08-20 启动）：#1 TenantLine + #4 SqlPerfSuggest + #8 DarkTheme + #10 GutterDebounce
  - #1 和 #3 都可能修改 SqlPreviewPanel.kt / SqlPreviewDialog.kt → 先完成的先提交，后完成的需 merge
  - #2 和 #10 文件完全独立
- **波次2**：#2 文档同步 + #11 verifyPlugin 提速（纯配置/文档）
- **波次3**：#5 参数导入 + #6 日志分组 + #7 批量SQL→Java
- **波次4**：#3 fill 关联填充

## 并行开发沙盒登记

子智能体跑测试前必须 `register` 独立沙盒：
- T-TenantLine（sandbox: tenant-line）
- T-SqlPerf（sandbox: sql-perf）
- T-DarkTheme（sandbox: dark-theme）
- T-GutterDeb（sandbox: gutter-deb）

脚本：`python C:\Users\USER\.zcode\cli\parallel_tasks.py check/register/done`

## 提交约定

每关键节点（子计划完成 + 全量测试通过）提交 git：
- commit message 格式：`feat: T-xxx 功能描述`
- 先全量绿 → `git add` 只加自己的文件 → 提交
- 用户 git 配置：SongRenShuo

**Why:** 11 项并行开发是本项目最大规模的多智能体协作，波次规划和文件冲突分析是防冲突的关键。

**How to apply:** 后续会话启动开发前读取本记忆，确认波次进度和文件占用状态；新项启动时按文件冲突分析归入对应波次。关联：[[agent-parallel-test-isolation]]、[[agent-task-split]]、[[agent-commit-rhythm]]。