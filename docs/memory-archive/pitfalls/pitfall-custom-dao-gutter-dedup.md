---
name: pitfall-custom-dao-gutter-dedup
description: HQL gutter 图标去重——两个 provider 互相排除 + 导航 handler 直接传 PsiMethodCallExpression
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bb5d9591-b617-4ac9-9c22-22ca1ad5d96a
---

**HQL gutter 重复图标陷阱（2026-08-21 用户 demo 实测发现，多轮迭代修复）**：

### 问题

方法内有 2 个 `createQuery` 调用，却显示了 4 个重复 gutter 图标。原因是两个 provider 都在为同一个 `createQuery` 创建图标：
- `HqlGutterLineMarkerProvider` — 锚定 `createQuery` 方法名 ✅
- `CustomDaoHqlGutterLineMarkerProvider` — 因为 `createQuery` 含 `Query` 关键字，`CustomDaoHqlLocator` 误判为自定义 DAO 调用 ❌

结果：2 个调用 × 2 个 provider = 4 个重复图标。

此外，当同一个方法内有多个 `createQuery` 调用时（如 `jpqlQuery` 有两个 `em.createQuery()`），旧实现所有图标都显示第一个 SQL。

### 修复

#### 1. 跨 provider 互斥排除

`CustomDaoHqlGutterLineMarkerProvider` 必须排除标准 JPA/Hibernate 方法：
```kotlin
// 【关键排除】标准 JPA/Hibernate 方法由 HqlGutterLineMarkerProvider 负责
if (element.text in setOf("createQuery", "createNamedQuery", "createNativeQuery")) return null
```

`HqlGutterLineMarkerProvider` 处理所有三种 createQuery 方法：
```kotlin
if (element.text !in setOf("createQuery", "createNamedQuery", "createNativeQuery")) return null
```

#### 2. 导航 handler 直接传 PsiMethodCallExpression

两个 provider 的导航 handler 都应直接从锚点向上查找 `PsiMethodCallExpression`，传给 `previewHql()`/`previewCustomDaoHql()`，而非依赖 `locate()` 反查：
```kotlin
override fun navigate(e: MouseEvent?, elt: PsiElement) {
    val parent = elt.parent as? PsiReferenceExpression ?: return
    val call = parent.parent as? PsiMethodCallExpression ?: return
    SqlPreviewService.previewAsync(project, {
        service.previewHql(call)  // 直接传调用，不依赖 locate()
    }) { result -> SqlPreviewService.showResult(project, result) }
}
```

**Why:** 一个方法体内可能有多个 `createQuery` 调用（如 `jpqlQuery` 有两个 `em.createQuery()`），每个调用需要独立图标和独立 SQL 预览。`locate()` 反查可能返回方法体内第一个调用而非锚点所在的调用。

**How to apply:** 多个 provider 覆盖同一条目时，必须通过方法名白名单/黑名单明确分工。导航 handler 应直接绑定到锚点对应的 `PsiMethodCallExpression`，确保点击哪个图标就显示哪个 SQL。

## 第三类（T-GutterDupFreeze，2026-10-08 用户真机）：内容启发把「整条链源码」当 HQL 首参

前两类是「两个 provider 抢同一锚点」，这一类是**单 provider 自己认错了调用**，且连带一次 70 秒 EDT 冻结。

- **形态**：`userMapper.selectList(Wrappers.<User>lambdaQuery(User.class)...exists("select 1 from t_order o where ..."))`——纯 MyBatis-Plus 代码，与自定义 DAO 无关。
- **误判链**：`matchesDaoQualifier` 命中（接收者名以 `Mapper` 结尾）→ `matchesHqlContent` 取**首参**交给 `extractHqlString`；首参是 wrapper 链（既非字面量、非拼接、非引用）→ 落到 `else -> expression.text` 兜底 → **整条链的源码文本**被当作 HQL 字符串 → 链内嵌的 SQL 串让 `SELECT...FROM` / `FROM <标识符>` 正则命中 → 认领。
- **连带事故**：认领后 `hql` 字段＝链源码，实体名由 `FROM` 正则从里面抓出（本例抓成 `t_order`）。点击预览 → `resolveHqlTable` → `findClassByEntityName` 的**无上限全项目类短名扫描**（不存在的名字必扫完整个项目，每名一次 StubIndex 查询；索引构建期每次都是随机磁盘读）→ 池化线程在不可取消的 `ReadAction.compute` 里**持读锁 70 秒**，EDT 同刻等读许可被冻、平台 `SuvorovProgress` 覆盖层接管事件＝弹框空白且关不掉（`SqlPreviewService.kt:1994` 即转储里的循环帧）。
- **修复（两处，缺一不可）**：①`matchesHqlContent` 加「首参必须是字符串承载式」门控（复用 `PreviewReceiverResolver.isStringLikeFirstArg`，与 HIBERNATE_TEMPLATE 家族同口径）**且**把 `extractHqlString` 的源码文本兜底改成 `null`——注意这两条是**冗余防线**，只摘一条仍不回退（反向验证必须整文件还原）；②慢路径护栏：索引构建期（dumb）按未命中降级且不写负缓存、扫描加「上限 + `checkCanceled` + 命中即止」（抽 `EntityAliasSweep`），触顶不写负缓存（未查到 ≠ 确认不存在）。
- **顺带勘正**：同一行第二枚图标来自**批量锚点**（`selectList` 在批量注册表、链首是 mapper 变量 → 场景 D 再挂一枚）；已补「实参是 wrapper 链/变量则让位」。
- **判据速记**：gutter 图标裸数不可信——先按图标配色判归属（本插件＝绿双尖括号 `#4FB477` + 紫放大镜 `#B48EFF`），再按「同一语句几枚」定位重复。**同语句多枚图标＝先查各 provider 的认领条件，别只看图片数量。**

相关计划：`docs/开发计划/20261008-1302-T-GutterDupFreeze-MP链假HQL锚点重复图标与单击冻结-开发计划.md`（含三轮反向验证读数）。
