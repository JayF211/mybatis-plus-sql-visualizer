---
name: t-intrupg
description: T-IntrUpg 旧生成拦截器自位污染残留——解析防线与自动升级，已实施交付（9d09e56，20260914，4012/0/0/6 绿）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_af9ca5ed-ca52-40cc-a06e-6fab1369de7a
---

T-IntrUpg（2026-09-14 主仓 master 直接实施，commit 9d09e56，全量 4012/0/0/6 绿）：T-JumpPath 交付后用户真机再反馈 SQL 日志位置仍全显 `[SqlSourceInterceptor.java:62]`。

**双缺口定性**：RC-A 存量升级缺口——join-demo 手工拷贝旧件（12:41 早于 T-JumpPath-01 模板修复）isSelf 常量 `cn.song.mybatisplusdemo...` 与实际类 `cn.song.mpjdemo...` 分叉，AlreadyExists 短路使旧件永不更新；RC-B 解析防线缺口——插件对已知污染形态照收。

**修复**：①`SqlSourceMarker.isInterceptorSelfPath`（normalize 后文件名==模板文件名常量，包式/项目根式/裸名/反斜杠全形态；MP COUNT 标记不含 .java 天然不互扰）+ `processConsoleLine` 第1步命中丢弃不暂存（独立文件级 Logger 留痕），旧件环境条目落无位置 MissingSource 引导链路；②`needsInterceptorUpgrade` 三条件 internal 纯函数：模板生成标记 ∧ `cn.equals("` 旧常量形态 ∧ ¬`getClass().getName()`——用户自写文件永不覆盖；③注入链升级执行：`interceptorExists`→`interceptorFile`（返 VirtualFile 供 `VfsUtil.loadText` 判形态）、`PrepareOutcome.Ready` 增 `upgradable`、`upgradeInterceptorFile` EDT `VfsUtil.saveText` 内容重写（拷贝件所在包即检测基础包，重写即修正常量；只动拦截器不动 Config）、新键 `log.inject.upgraded` 双语两参、AlreadyExists 收紧为 ready 与 upgradable 皆空。

**用户侧恢复**：join-demo 设置页重新注入 → 弹「已更新旧版拦截器」→ 应用重启后新标记生效（重启前防线兜底）。真机验收四步（计划 §六）留人工。

**后续 T-ModPick（964f89f，同日）**：用户反馈「多模块时应让用户选择而非全部生成」——多模块（新建+升级>1）注入前弹 `InjectionModulePickerDialog` 勾选（默认全选+全选/全不选+空选禁确定，升级项标「旧版待更新」），单模块不打扰直注，取消=`InjectionResult.Cancelled`（静默、设置页回滚勾选）；双语键 `log.inject.pick.*`。

计划 `docs/开发计划/20260914-1439-T-IntrUpg-*.md` §八 实施记录。上游 [[t-jumpcapture]]（T-JumpPath §七留观「旧模板不自动更新视反馈另行立项」即触发点）。
