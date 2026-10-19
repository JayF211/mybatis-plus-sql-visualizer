---
name: t-jumpcapture
description: T-JumpCapture 已实施并合入 master（merge ffc518c，合并后 3975/0/0/6 绿）：日志跳转源码仅依赖拦截器捕获位置，无捕获时三按钮引导弹框，Wrapper 表名回退整链移除；分支与 worktree 用后即删
metadata:
  node_type: memory
  type: project
  originSessionId: sess_af9ca5ed-ca52-40cc-a06e-6fab1369de7a
---

用户定性「按表名搜 Wrapper 定位」跳转不可用（同表名≠同调用点，命中即误导）。计划 `docs/开发计划/20260914-1043-T-JumpCapture-日志跳转仅依赖捕获位置缺失时引导开启拦截器-开发计划.md`（§六 实施记录已回填）：

- SqlLogPanel.LogJumpTarget 收敛 Precise/NoSource（MP COUNT）/NotFound/MissingSource；删 resolveLiveJump/searchByTable/showCandidateChooser/findSourceFile 与 WrapperSearchService.kt 整链+两测试。MP 分页 COUNT 的 NoSource 维持纯提示（文案换 COUNT 语义）。
- 新建 `log/SourceCaptureGuide.kt`：resolveChoice 纯函数 + Messages.showDialog 三按钮（打开设置→ShowSettingsUtil 直达 SqlLogConfigurable / 立即注入→runInjection 防重入 / 取消），实时+历史面板共用；PluginSettingsPanel.triggerInterceptorInjection 委托 Guide（面板仅留勾选回滚），注入四类结果弹窗收编 i18n 化。
- i18n 删 5 键（noTable/noWrapper/multipleTitle/multipleHeader/history.noSource）增 11 键（引导 5 + 注入 6，双语 parity 守卫绿）。
- 交付：worktree `mpsv-jumpcapture`（分支 t-jumpcapture，提交 2c7426c，分支全量 3971/0/0/6 绿）当日 **no-ff 合入 master（merge ffc518c），合并后 master 全量 3975/0/0/6 绿**；分支本地+远端、worktree 均已删除（worktree remove 因 build 产物超 MAX_PATH 失败，改 `cmd rd /s /q "\\?\..."` 长路径前缀强删后 `git worktree prune`）。真机走查留人工。
- 遗留观察：SqlTableExtractor 现零生产消费方（仅自身测试存活），未删留观。
