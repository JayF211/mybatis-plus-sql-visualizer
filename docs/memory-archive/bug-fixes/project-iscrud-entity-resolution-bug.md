---
name: project-iscrud-entity-resolution-bug
description: IService/BaseMapper 内置 CRUD 方法（getById 等）预览失败——实体类无 Entity 后缀时
  resolveServiceEntityClass 误拒
metadata:
  node_type: memory
  type: project
  originSessionId: sess_95a9b56e-a33d-4792-8335-4db23ae7c113
---

**2026-08-19 用户反馈**：`userService.getById(id)` 点击 gutter 图标报"未在光标处找到MyBatis-Plus Wrapper 调用"。

**根因**：`BatchOperationConverter.resolveEntityTable()` → `resolveServiceEntityClass()` 步骤 3b 要求 `resolved.name?.endsWith(ENTITY_SUFFIX) == true`。对 `UserService extends IService<User>`，解析出的 `User` 不以 "Entity" 结尾 → 跳过 → 实体解析失败 → convert() 返回 null → 预览失败。

**影响范围**：所有 `BATCH_OPERATIONS` 中的方法（getById/removeById/save/updateById/saveOrUpdate/listByIds 等），只要实体类不含 "Entity" 后缀且无 `@TableName` 注解。

**修复方案**（开发计划 `docs/开发计划/20260819-2350-IService内置CRUD方法预览-实体解析增强-开发计划.md`）：
1. 新增方法返回类型解析源（`getById` 返回 `User` → 直接识别为实体）——高置信度，方法名在白名单中
2. 放宽 `resolveServiceEntityClass()` 步骤 3b 的后缀限定（`endsWith(ENTITY_SUFFIX)` → `isLikelyEntity(resolved)`）
3. 更新错误提示文案（从硬编码 "LambdaQueryWrapper" 泛化为 "IService/BaseMapper 内置方法"）

**How to apply**: 实体解析路径新增源时，优先用方法返回类型（白名单方法名上下文下高置信度），不破坏原有含 Entity 后缀的路径。防回归：`List<String> list.selectAll()` 的 String 不得被误判为实体（已通过排除 java./javax. 包前缀 + 返回类型非实体防护）。

**关联记忆**：[[project-exists-bugfix]]（同类 WrapperLocator 定位缺陷）