---
name: pitfall-gutter-provider-receiver-not-checked
description: gutter LineMarkerProvider 只按方法名判中、不校验接收者类型 → 通用方法名跨 provider 双收/漏收，产生重复或缺失预览图标
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_9174d2f4-819c-44dc-9e3d-20d63ca9bbef
---

# 预览图标重复/缺失：gutter provider 只按方法名判中，不校验接收者类型

## 现象（T-GutterDedup，2026-09-04）
- `commonDao.execute("update ...")` 出现 2 个预览图标（JdbcTemplate + CustomDaoHql 双收 `execute`）。
- `getHibernateTemplate().execute(new HibernateCallback{ session.createQuery(...) })` 出现 3 个（execute 被 Jdbc 与 CustomDaoHql 双收 + 内层 createQuery 被 Hql 收）。
- `getHibernateTemplate().find(...)` / `.findByValueBean(...)` 无任何图标（无 provider 覆盖）。

## 根因
gutter 图标由多个相互独立的 `LineMarkerProvider` 并行产生，各自**只按方法名锚点判中、不感知其它 provider 是否已中标、也不校验接收者到底是哪类对象**：
- `JdbcTemplateMethodDetector.JDBC_TEMPLATE_METHODS` 含 `execute`，其 gutter 快速路径只校验"第 1 实参非空"（不校验是否字符串、不校验接收者）→ `commonDao.execute`/`getHibernateTemplate().execute(callback)` 均被 Jdbc 误标；`execute(callback)` 实参非字符串但被标 → "有图标但点击 preview 判不中 → 预览无效"。
- `CustomDaoHqlLocator.QUERY_METHOD_KEYWORDS` 含 `execute`，判定 `(qualifier 匹配 DAO 后缀 OR 方法名含关键字) AND 内容匹配`，`execute` 仅靠方法名+内容即达标、无需 DAO qualifier → HibernateTemplate 的 execute 也被收。
- 各 provider 注释声称"方法名集合天然区分"，前提（接收者固定）不成立——通用方法名 `execute` 被 Jdbc 与 CustomDaoHql 同时收录即破例。

## 修复方向（增量）
- **接收者类型消歧**：新增轻量 `PreviewReceiverResolver` 把接收者归类（JDBC/HibernateTemplate/CustomDao/Session/UNKNOWN），各 provider 只认自家；qualifier 文本启发为主 + 白名单类型 `resolve()` 兜底，resolve 在后台 daemon 线程（非 EDT）不引 EDT 慢操作。
- **收紧 CustomDaoHql**：`execute` 等通用方法名须搭配 DAO qualifier 才命中。
- **补齐 HibernateTemplate**：`find/findByValueBean/iterate` HQL 首参 → 新 provider + 复用 HQL 预览（findByValueBean 参数按 `:name` 命名提示）。
- **JDBC 快路径**：实参非空升级为"首参是字符串/拼接/字符串变量"，杜绝假图标。

## 适用铁律
写/加 LineMarkerProvider 或给方法名集合增名时，先问"这个方法名会不会同时命中其它 provider（尤其通用名 execute/update/query/find/delete/insert）"；要么按接收者消歧，要么在对方 provider 里显式排除。相关：all gutter 是 Java 文件方法名锚点，不能只靠方法名独特性。

## 实施完成（2026-09-04，提交 480dacb）

最终落地（6 文件）：`psi/PreviewReceiverResolver.kt`（Family 枚举 CUSTOM_DAO/HIBERNATE_TEMPLATE/JDBC_TEMPLATE/JPA_SESSION/UNKNOWN；classify 按 qualifier 文本启发 → 类型 resolve 兜底；isStringLikeFirstArg=字面量/拼接/解析到 String 的引用）+ `CustomDaoHqlLocator`（execute 移出关键字；HIBERNATE_HQL_METHODS={find,findByNamedParam,findByValueBean,iterate} 由本定位器认领 HibernateTemplate 家族，未新建 provider）+ `JdbcTemplateSqlGutterLineMarkerProvider`（JDBC 家族 + 首参字符串双门）+ `GutterIconDedupTest`（11 场景）+ `DemoRecognitionTest`（加载真实 demo 文件，逐调用点断言恰 1 图标、execute(callback)=0）+ 计划文档（含实施记录节）。测试全绿；沙盒容器已还原、并行登记已注销。

实施踩坑（PSI/测试，后续同类改动必看）：
- HIBERNATE_TEMPLATE 家族必须跳过内容匹配（matchesHqlContent）：`find(hql, params)` 的 hql 是方法参数、无 initializer，内容门恒 false → 该家族方法名定案 + 仅要求 isStringLikeFirstArg。
- `isStringLikeExpression` 引用分支 cast 用 `PsiVariable`——`PsiParameter` 是 PsiVariable 而**不是** PsiTypeElement，cast 成 PsiTypeElement 对参数静默返回 null（假阴性，测了才发现）。
- PsiMethodCallExpression 没有 returnType，qualifier 类型取 `qualifier.type`。
- 测试定位调用点 leaf：`it.text == "find"` 会命中方法**声明**名（parent 是 PsiMethod），须加 `it.parent is PsiReferenceExpression` 才是调用名。

计划：`docs/开发计划/20260904-1326-T-GutterDedup-SQL预览图标重复与缺失修复-开发计划.md`。