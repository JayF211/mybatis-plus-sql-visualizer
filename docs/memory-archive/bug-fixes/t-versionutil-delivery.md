---
name: t-versionutil-delivery
description: T-VersionUtil 已交付——更新检查版本比较改用 IDEA 内置 VersionComparatorUtil，根治同日 beta/正式版提示倒挂
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bc501d91-a658-4cfd-a6f7-2a4ac3fc07d6
---

T-VersionUtil（2026-09-10，master ea66ea7）：`update/VersionComparator.compare` 弃自研点分算法，改委托平台 `com.intellij.util.text.VersionComparatorUtil`（`lib/util_rt.jar`），与 IDE/Marketplace「发现新版」判定同口径。

**旧算法缺陷（实证）**：`2026.9.10-beta.1` 按 `.` 切分第三段 = `10-beta` 非数字段走字符串兜底 → 判**大于** `2026.9.10`；发版文档「semver 保证 beta.N < 正式版」只对平台比较器成立。平台比较器识别 `-beta` 预发布标记正确判小，且 `beta.10 > beta.9` 数值排仍成立（243/253/262 三平台 util_rt.jar 同程序实测行为一致）。

**核实链**：243/253 用 gradle caches transforms 下的发行版 + 262 本机安装，python zipfile 定位类在 util_rt.jar → javap 三平台 `compare(String,String)` 签名一致 → jbr java 直跑对照表（含 null/空串/缺段/畸形——全部与旧自研行为一致，包装层仅需保留 null 语义 + 异常 runCatching 降级 0）。IPGP 不裁该类（verifySmoke 243 通过佐证）。

**验证**：全量 3254/0/0/6 绿 + verifySmoke BUILD SUCCESSFUL；`VersionComparatorTest` 补 betaPrereleaseLowerThanRelease/betaSuffixIncrementsNumerically。

**文档同步**：`版本号规范-说明文档.md` 新增内测版本号 + 比较机制场景表；`02-核心流程详解.md` §7.2 图节点更新；CHANGELOG Unreleased。测试假红插曲 = K2J 陈旧类第四犯，见 [[pitfall-k2j-stale-classes-hijack]]。

关联：[[project-update-manifest-workflow]]（双清单工作流）、[[release-verifyplugin-gate]]（发版前全量三版本门禁仍须跑）
