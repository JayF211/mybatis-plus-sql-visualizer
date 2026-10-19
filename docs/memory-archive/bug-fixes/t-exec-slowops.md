---
name: t-exec-slowops
description: T-ExecSlowOps 预览/执行临时文件链路线程化双 SEVERE 修复——已实施并合入 master（merge 80e7d9c，合并后 4028/0/0/6 绿，20260914）：DialectPreMapping 两段式映射单入口 + prewarmExecTempFile 挂 previewAsync + 缓存 volatile
metadata:
  node_type: memory
  type: project
  originSessionId: sess_85191f11-f091-4284-82cb-aa97c8cdcd82
---

T-ExecSlowOps（计划 20260914-1218）**已实施并合入 master**（2026-09-14：实施提交 c27ff31，merge 80e7d9c 合入，docs 两表按时间序并集解冲突；合并后 detached worktree 独立沙盒全量 **4028/0/0/6 绿**——中途 XmlPreviewPanelTest 剪贴板 1 例=并行会话抢系统剪贴板环境型 flake 单类重跑绿；分支与 worktree 用后即删）。真机走查计划 §六 5 项留人工。

实施形态与计划的两处偏差（已回填计划 §七）：

- **F2 挂点改为 `SqlPreviewService.previewAsync`**（原计划 §3.2 面板构造器）：previewAsync 是 gutter + 全部工具栏/快捷键入口的单一池线程收口点，compute 之后、`invokeLater` 派发弹窗之前，**独立池线程 fire-and-forget**（预热含 refresh 有界耗时，不得推迟弹窗）。守卫=确定性 SQL（previewModel/batchModel/xmlModel/fillClauses 全空）+ `ExecuteSqlSupport.isAvailable()`。
- **源扫描守卫收紧**：不是「预热方法体内无 setMapping 字面量」，而是「`"setMapping"` 字面量 src/main 全树只允许 DialectPreMapping.kt」——任何新文件反射直调都红。

落地结构：`execute/DialectPreMapping.kt`（internal object，方言映射唯一入口）= `shouldSkip` 纯函数 + `configuredAsMySql`（getConfiguredMapping 反射，243 app-client.jar / 262 analysis.impl.jar 双版 javap 实锤 public 无 EDT 限制）+ `setMySqlOnEdt`（非 EDT 拒绝并 warn 防御网）+ `ensureMapped` 编排（EDT 同步语义 / BGT 读探测稳态跳过、首见 invokeLater 派 EDT + latch 2s，绝不 invokeAndWait）。预览/exec 两处原反射函数收编为薄委托（各自保留 in-process vf 短路 + 成功回写）。`DataSourceSelector.prewarmExecTempFile`：EDT 直调拒绝、AtomicBoolean `execPrewarmInFlight` 防重、**缓存命中≠跳过——仅 doc 驱动刷新文件内容**（连续预览 SQL 会变而命中路径不重写文件，保 attachConsole 初始内容一致）、`markCachedTempPsiFile` internal 正函数 + 测试缝薄委托。F3 四缓存字段 `@Volatile`；F4 按预案砍掉；`createTempPsiFile` KDoc「容忍 SEVERE 噪音/后台化经评估否决」旧表述已清除。

SQL 时效性定论：预热写 `result.sql` 原文与面板渲染文本差异由**既有**三级同步链吸收（reuseSharedConsole setText / prepareAfterEditorResolved console-doc→vf 同步 + smart updateScriptModel / executeOnConsole 执行前同步），未新增同步面。

技术定论（逃生舱双重证伪、断言仅 EDT 生效、setMapping 须 EDT）见 [[pitfall-edt-slowops-coldpath]]；exec 缓存与预览降级链前史见 [[t-preview-exec-ds]]。

**交付后真机再报同形态 SEVERE（setMapping BGT ×7）的归因定论**：堆栈 `SqlPreviewPanel.mapPreviewDialectBeforePsi → DirectMethodHandleAccessor.invoke → setMapping` 直落、**无 DialectPreMapping 帧**——修复分支上该函数已是三行薄委托、全树反射唯一入口在 DialectPreMapping.setMySqlOnEdt（非 EDT 直接拒绝），新代码不可能产生此堆栈形态。结论=用户真机跑的是 master 旧字节码（修复未合流、装机插件未更新）。**堆栈帧形态鉴别构建新旧**是此类「修复后再报错」的第一检查项：先对照分支上修复后的调用链形状，再谈新缺陷。

合并实施记录（2026-09-14 下午）：t-modpick 已收尾（f40cde7/8aa5261 入 master 4024 绿），主仓直接 `git merge t-execslowops --no-ff` 发起，仅 docs/MEMORY.md + docs/变更记录.md 两表冲突（HEAD 侧新行在上、分支侧 T-ExecSlowOps 行按时间序落位即可，代码 SqlPreviewPanel.kt 自动合并）；合并验证走 detached worktree（wt-tso-verify @ 80e7d9c + 临时 sandboxContainer MPSV-T-ExecSlowOps-Merge）绕开主仓并行会话工作区，跑完还原+用后即删。

**Why:** 修复已入 master 主线；后续同类 SEVERE 复现直接按本记录定位。

**How to apply:** 真机若再现 BGT setMapping SEVERE，先核堆栈是否含 DialectPreMapping 帧判定装机代码新旧，再决定是否立新缺陷；新方言映射调用只走 `DialectPreMapping.ensureMapped`（守卫会拦旁路）。
