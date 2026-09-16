---
name: t-jumppath
description: T-JumpPath 多模块跳转三缺陷+注入逐模块聚合，已合入 master（50a9cc7，4002/0/0/6）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_af9ca5ed-ca52-40cc-a06e-6fab1369de7a
---

T-JumpPath 已实施交付（merge 50a9cc7，2026-09-14，分支/worktree 用后即删，push 已留痕后远端删）：多模块工程真机三缺陷四根因——RC1 注入只落第一模块→用户手工拷贝，拷贝件生成期 basePackage 常量漏改 `isSelf` 分叉失效→拦截器自位污染（位置 62 行）；RC2 [[t-preview-exec-ds]] 同期 SourcePathResolver 缺 sourceRoots 策略（标记路径=包式相对**源码根**，不含 src/main/java 段）；RC3 EntrySourceMatcher 等值比较对「锚点=项目根相对 vs 条目=包式」恒不命中→最近执行记录空；RC4 ProjectTypeDetector 只取第一模块。

修复形态（后续改动须知）：
- 模板 `isSelf` = `cn.equals(getClass().getName())` 运行时比对（防回退断言在 SourceInterceptorTemplatesTest）；旧注入工程不自动升级（AlreadyExists 短路），「升级已生成文件」未立项。
- SourcePathResolver 三级解析：项目根直拼 → contentRoots → sourceRoots（收编旧 findSourceFile 语义）。
- `EntrySourceMatcher.sameFile` 段对齐后缀互含（`/` 段界，防 xmod 假命中）。
- `ProjectTypeDetector.detectPerModule` 准入 = 模块依赖可见 MP `InnerInterceptor`（生成物编译依赖本身）；`decideAggregate` 纯函数聚合（成功>AlreadyExists>Unsupported）；多模块成功消息新键 `log.inject.successMulti`（四参双语）。

真机验收四步（demo 双模块注入/业务类位置/gutter 反查/历史旧条目不回归）留人工。计划文档 docs/开发计划/20260914-1306-T-JumpPath-*.md §八 有完整实施记录。
