---
name: project-reverse-invocation-has-mpj-interface-bug
description: SQL→Java 反向 invocationHasMpj 漏判接口继承 MPJBaseService（BikeDeviceInfoService 实证）——getInterfaces() 对 jar-loaded 泛型超类型返回空数组，改用 superTypes+resolve() 回退
metadata:
  node_type: memory
  type: project
  originSessionId: sess_265db880-45c6-4317-8db4-4afe70065e95
---

2026-08-17 用户实证：`BikeDeviceInfoService`（`interface BikeDeviceInfoService extends MPJBaseService<BikeDeviceInfoEntity>`）作调用类时，多表 JOIN 生成报 `SqlReverseException: 多表 JOIN 需要 MPJ 依赖，但调用类 BikeDeviceInfoService 无 MPJ 能力`——该接口明确具备 MPJ 能力，属误判。提交 0fd6b1f 修复，1220 全绿。

**根因**：`hasMpjInterface` 主路径用 `clazz.interfaces.any{...}` + `clazz.superClass` 递归（接口已覆盖，原记忆「getSuperClass 对接口返 null」不准确——原代码先走 interfaces）。真因是 `getInterfaces()` 需完整解析泛型超类型 `MPJBaseService<BikeDeviceInfoEntity>`——jar-loaded 跨模块时类型实参解析失败 → `getInterfaces()` 返回空数组 → 继承链在第一层断裂，MPJBaseService 从未被检查。源文件→源文件解析总是成功，故既有测试（源文件 fixture）通过但真机翻车。

**修复（T-M48.5，2026-08-18，提交 64bd1c3）**：上一版 `canonicalText` 回退仍失败——jar-loaded 未解析类型 `canonicalText` 返简单名 `MPJBaseService`，不匹配全限定名集合。重写为 `superTypes` 主路径 + 双路回退：resolve() 失败时先直接匹配 `canonicalText` 全限定名（源文件 FQN 场景），未命中时按简单名经 `PsiShortNamesCache.allScope(project)` 全项目查找（jar-loaded 简单名场景）。`hasMpjInterface` 签名加 `project: Project` 参数（调用方 `invocationHasMpj` 透传）。测试新增 `testGenericSupertypeWithUnresolvableArg` 用 FQN extends 不可解析接口 → 4/4 全绿。

**验证**：Heavy MPJ 4/4（含 testGenericSupertypeWithUnresolvableArg + testDirect + testIndirect + testPlain）；反向 104/104；面板 34/34。

**Why:** 原记忆描述「应改用 getSuperTypes() 统一遍历」不准确——实际是混合方案（主路径不变 + 回退），避免破坏源文件场景的既有测试。

**How to apply:** 涉及 `invocationHasMpj`/`InvocationTarget.mpj` 判定时，jar-loaded 泛型超类型场景 getInterfaces() 不可靠，须 superTypes+resolve() 兜底。MPJ_INTERFACES = {MPJBaseService, JoinService, MPJBaseMapper, JoinMapper}（1.5.6 javap 实证全限定名）。相关：[[project-reverse-join-fix]]