---
name: project-iscrud-this-and-chain-qualifier-bug
description: IService/BaseMapper 内置方法隐式 this 调用和链式调用预览失败——已修复（2026-08-20）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e7a162fd-a084-4eec-9a39-b17be35e37e0
---

**IService/BaseMapper 内置方法预览缺陷（2026-08-20 用户报告 + 已修复）**：两个新形态无法识别。

**问题一：`this.getById(1)` 隐式 this 调用有图标但预览失败**
- gutter 图标正常（WrapperAnchorDetector 场景 D 识别 getById 为批量锚点）
- 预览失败：`resolveServiceEntityClass()` 第 275 行 `call.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return null` — 隐式 this 时 qualifierExpression 为 null → 直接返回 null → 实体解析失败
- 影响：ServiceImpl/Mapper 内部所有 `this.getById()` / `this.save()` / `this.removeById()` 等隐式 this 调用

**问题二：`getBaseMapper().selectById(1)` 链式调用无图标不识别**
- 双重缺陷：① `selectById` 未注册（BATCH_OPERATIONS 只有 `selectByIds` 复数）② `resolveServiceEntityClass()` 要求 qualifier 是变量引用，但 `getBaseMapper()` 是 PsiMethodCallExpression → 转换失败
- 影响：所有 `getBaseMapper().xxx()` 链式调用 + 未注册的 BaseMapper 方法（selectList/selectMaps/selectObjs/selectCount）

**修复完成**（计划 `docs/开发计划/20260820-15`，4 测试用例，1407 全绿）：
1. 补全 BATCH_OPERATIONS 注册表（selectById/selectList/selectMaps/selectObjs/selectCount）+ SINGLE_ID_SELECT_METHODS 补 selectById + 新增 FULL_COLUMN_SELECT_METHODS
2. 新增 `resolveEntityFromImplicitThis()` — qualifier 为 null 或 PsiThisExpression 时从 containing class 父类泛型解析实体
3. 新增 `resolveEntityFromMethodQualifier()` — qualifier 是方法调用时从返回类型泛型解析实体（回退到父类泛型）
4. 修复 `buildSelectBatch` 处理新增方法类型：FULL_COLUMN_SELECT_METHODS 无 WHERE、selectCount 输出 COUNT(*)

**关键踩坑**：
- Java PSI 中 `this.getById(id)` 的 qualifierExpression 是 `PsiThisExpression`（非 null），隐式 this 时才是 null — 两种都要处理
- `user` 是 SQL 关键字，方言自动加反引号 → 测试断言须匹配 `` FROM `user` `` 而非 `FROM user`
- 返回类型是具体子类（UserMapper extends BaseMapper<User>）时，自身无泛型参数，需回退到父类/接口泛型

**Why:** 上一轮修复（project-iscrud-entity-resolution-bug）只解决了实体类无 Entity 后缀的误拒，未覆盖隐式 this 和方法调用 qualifier 两种形态。

**How to apply:** 修复时保持原有实体解析路径优先（步骤 1/2/3 不变），新增路径作为 3b/3c 回退。新增函数不做 isLikelyEntity 限定（方法名在白名单中 + 上下文高置信度），仅排除 java./javax. 包。
