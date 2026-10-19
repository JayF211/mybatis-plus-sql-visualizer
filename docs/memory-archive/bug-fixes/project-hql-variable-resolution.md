---
name: project-hql-variable-resolution
description: HQL 变量引用拼接初始化式解析的两次缺口：HqlQueryLocator
  已修复（d3c4106）；CustomDaoHqlLocator.extractArgumentText 内容启发被 T-HqlVarResolve-03
  复核遗漏 → 2026-09-03 用户反馈"拼接变量无预览图标"
metadata:
  node_type: memory
  type: project
  originSessionId: sess_12f11f93-b1ad-4cee-8f7e-49bf1178ce17
---

## 问题

HQL 传给 `createQuery()`/自定义 DAO 方法时若先赋值给变量且初始化式为拼接（`String hql = "select" + " ..."`），预览显示变量名或 **gutter 图标不出现**：

```java
String hql = "select" + " count(booking.id) ...";  // initializer 是 PsiBinaryExpression/PsiPolyadicExpression
entityManager.createQuery(hql);                    // HqlQueryLocator 路径已修
commonDao.findByQueryUniqueResult(hql, names, values);  // 自定义 DAO 路径 2026-09-03 仍未修
```

## 现状（2026-09-03 核实）

- `HqlQueryLocator.extractHqlString()`（HqlQueryLocator.kt:292-313）PsiReferenceExpression 分支已递归处理字面量/拼接/Polyadic，提交 d3c4106（2026-09-02 T-HqlVarResolve-01）✓。
- **`CustomDaoHqlLocator.extractArgumentText()`（CustomDaoHqlLocator.kt:320-342）仍有缺口**：内容启发用，PsiReferenceExpression 分支只认 `initializer is PsiLiteralExpression`，拼接初始化式回退变量名 "hql" → `matchesHqlContent` 不匹配 → `isCustomDaoHqlCall=false` → `locate()=null` → **gutter 图标不出 + 手动触发弹"未找到 Wrapper/IService/BaseMapper"**。
- 同文件 `extractHqlString`（278-310 行）已递归——T-HqlVarResolve-03 只复核了它，**漏了并行的 extractArgumentText**。

## 修复方案（开发计划 20260903-1700，**2026-09-03 已实施**）

1. `extractArgumentText` 委托 `extractHqlString(expression).first`（单一来源，消除双逻辑分叉）✓
2. `ParamSource.NAME_VALUE_ARRAYS`：`findByQueryUniqueResult(hql, new String[]{...}, new Object[]{...})` 老式名字数组+值数组模式，新增 detectParamSource/isNameValueArrays/resolveParameters 三分支，参数面板按名匹配、按索引取值 ✓
3. 新测试 3 个（拼接变量识别 / 双数组参数 / 反向守卫）CustomDaoHqlLocatorTest 13/13 + JPA 族回归 145/145 全绿 ✓

**审核教训**：双函数并行的逻辑修改，复核时必须对每个分支逐一对照，不能只查\"主函数\"。

相关：[[project-hql-association-xml-mapping]]、[[project-jdbctemplate-sql-string-preview]]