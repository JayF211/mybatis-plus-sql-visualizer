---
name: feedback-runide-hotswap-separation
description: 用户定案：hotswap 本地服务只挂 runIdeHotSwap（Local Manifest 链路），普通 runIde 是纯开发沙盒绝不拉服务
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_64deca82-5ab0-4c33-8746-620c51b51af1
---

2026-09-12 用户纠偏：T-DynUpdate 曾把 hotswap 本地服务起停同时挂上普通 `runIde`（build.gradle.kts doFirst + finalizedBy），理由是「旧 .run 配置误触 :runIde 也通」——用户明确指出两个运行配置本就该分工：「Run IDE (Local Manifest)」= runIdeHotSwap（热替换验证链路，需要 serve 新版 zip 的本地服务）；「Run IDE with Plugin」= runIde（纯开发沙盒，不依赖清单服务）。已修复为 runIde 摘除服务起停 + 删除 .run 旧配置（master a274e30）。

**Why:** 为兼容一个本该删除的误触入口而给所有入口加副作用，把一次性补救固化成了常驻行为；该行为在 build/distributions 缺 zip（clean 后）时直接让 runIde 启动硬失败。

**How to apply:** 辅助服务/校验链路按任务职责挂载，不为「点错配置」兜底——误触的根源是旧配置本身，删配置而不是扩任务行为。给 runIde 这类高频入口加任何 doFirst 副作用前先想 clean/首跑等异常初态。相关：[[project-release-workflow]]
