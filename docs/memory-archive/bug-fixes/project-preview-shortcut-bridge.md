---
name: project-preview-shortcut-bridge
description: T-PreviewShortcut 非 Wrapper 预览快捷键贯通（convertToSql 兜底链加
  tryXxxFallback）+ @NamedQuery 实体级预览接入
metadata:
  node_type: memory
  type: project
  originSessionId: sess_9174d2f4-819c-44dc-9e3d-20d63ca9bbef
---

# T-PreviewShortcut：非 Wrapper 预览快捷键贯通（2026-09-04，提交 210c802；前置 merge 151d56e）

## 背景与洞察
所有"非 Wrapper"预览都有两套入口：**gutter 点击**（各 LineMarkerProvider → 各自 preview 方法）与 **快捷键 `Alt+Shift+Q`**（`ConvertToSqlAction` actionPerformed）。二者原本是两条互不相通的链路——gutter 能点，快捷键却因为 `ConvertToSqlAction` 的兜底链（`service.preview()` 只识别 MP Wrapper → `tryJpaFallback()` 只识别 @Query/派生/CriteriaQuery → `@Select` 注解候选）不含这些定位器而落到"未找到"。

## 根因定位模式
排查"只有图标、无快捷键"= 逐个预览类型核对"定位器 → preview 方法 → 是否被 ConvertToSqlAction 兜底链引用"。定位器存在 + gutter 点击能预览 + 兜底链不引用 = 快捷键缺口。Result：HQL/JPQL 运行时（`session/em.createQuery`/`createNativeQuery`/`createNamedQuery`）、自定义 DAO HQL（`findByQuery`）、JDBC 模板（`queryForList` 等）三类同源缺口；`@NamedQuery` 实体级**连 gutter/预览都没有**（NamedQueryLocator 有定位器+测试但主代码无任何消费者——"做了一半"）。

## 方案：兜底链接成短路链
`ConvertToSqlAction` 在 `preview()` 返 null 后、`tryJpaFallback` 前按序插入短路 fallback，链式写法避免嵌套：
```
else if (!tryHqlFallback(...) && !tryNamedQueryFallback(...) && !tryJdbcTemplateFallback(...) && !tryJpaFallback(...))
```
每个 fallback = `XXXLocator.locate(element)!=null` 判命中 → `previewAsync{ 对应 preview 方法 }` → return true。定位器均接受任意 PSI 元素（locate 路径 3 扫宿主方法体）。jar 反编译权威：不 invoke 不存在的 API，全部用既有 preview 方法（previewHql/previewCustomDaoHql/JdbcTemplateSqlPreviewService.preview）。

## @NamedQuery 实体级补齐（要点）
- `NamedQueryLocator.NamedQueryInfo` 增 `annotation: PsiAnnotation? = null`（回填），供"光标下那一条"定位 + 反查实体类。
- 新增 `NamedQueryLocator.findByQueryName(project, name)`：JPA 命名查询名默认 `<实体简单名>.<方法名>`，取 `.` 前缀 `JavaPsiFacade.getInstance(project).findClasses(prefix, GlobalSearchScope.projectScope(project))` 解析实体，再 `locate(cls).firstOrNull{ name 匹配 }`。**不用 PsiSearchHelper**（前缀约定即可按类名解析）。解析不到优雅降级为把名字当 HQL 处理。
- `SqlPreviewService.previewNamedQuery(anchor)`：反查 PsiClass → JpqlConverter.convert → buildJpaPreviewResult(..., method=null,...)（method 可空，数值判定回退全引号）。
- `previewHql` 对 `createQueryMethodName=="createNamedQuery"` 分支：`findByQueryName` 命中 → 走 JpqlConverter 转换真实 JPQL；未命中 → 回退旧"把名字当 HQL 转换"。

## gutter 锚点模式（@NamedQuery 新 provider）
仿 JpqlGutter：注释类 gutter 锚 `JavaTokenType.AT` token；`@NamedQueries` 容器内子 `@NamedQuery` 自带独立 `@` token 逐个锚定，容器本身（短名 NamedQueries）跳过。测试注意：`@interface X {}` 声明的 `@` 也是 AT token，断言别按原始 @ 计数，应数"产生 marker 的个数"。

## 并行会话连带事故（再次实证）
本会话中途，另一个并行会话做了 `git add`+commit（9dd4825 JOIN 工作），把**我写入 docs/MEMORY.md 的索引行也一并提交**进它的提交里——导致我的文档改动"神秘消失"从 status 里不见（实际被它提交）。教训：有并行会话在 master 活动时主动提交前先 `git log`/`git status` 核对，别假设工作区干净。

## 迁移记录
计划文档已从 T-HqlShortcut 重命名为 `docs/开发计划/20260904-1010-T-PreviewShortcut-...`。同系列"JDBC 模板原 i18n 文案已写支持 Alt+Shift+Q 但快捷键未真正接线"，本修复让文档承诺变现实。后续 GutterDedup 图标去重见 [[pitfall-gutter-provider-receiver-not-checked]]。