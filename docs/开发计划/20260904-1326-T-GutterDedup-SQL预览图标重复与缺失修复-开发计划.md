# T-GutterDedup：SQL 预览图标重复与缺失修复 开发计划

> **创建日期**：2026-09-04
> **分支**：master（当前 HEAD `210c802`）
> **优先级**：P1（预览图标错乱直接影响可用性：重复图标扰视线、命中后部分预览无效；缺失图标使 HibernateTemplate HQL 无可预览入口）
> **预估工时**：4~6h
> **来源**：用户真机 demo（`jpa-hibernate-demo`）标注：`commonDao.execute` 重复图标、`getHibernateTemplate().execute` 三图标、`HibernateTemplate.find/findByValueBean` 无图标

---

## 一、问题与根因

### 1.1 现象（demo `jpa-hibernate-demo/.../service/impl/JpaQueryServiceImpl.kt` 相关方法与 DAO 调用）

| 场景 | 用户预期 | 实际 |
|------|---------|------|
| `commonDao.execute("update WmsDockBooking ...", "newStatus", ...)` | 1 个（自定义 DAO HQL） | **2 个** |
| `commonDao.execute("delete from WmsAsn ...", "id", id)` | 1 个 | **2 个** |
| `getHibernateTemplate().execute(new HibernateCallback{ session.createQuery("from ...") })` | 1 个（仅内层 createQuery） | **3 个**（execute×2 + createQuery） |
| `getHibernateTemplate().find("from ... where type = ?", type)` | 1 个（HQL） | **0 个** |
| `getHibernateTemplate().findByValueBean("from ... :warehouseId", sample)` | 1 个（HQL） | **0 个** |

### 1.2 根因：gutter provider 只按**方法名**判中，不校验**接收者类型**

gutter 图标由多个相互独立的 `LineMarkerProvider` 并行产生，各自按方法名锚点，**不感知其它 provider 是否已中标、也不校验接收者到底是哪类对象**。逐一判定：

| 调用 | JdbcTemplateGutter | CustomDaoHqlGutter | HqlGutter | 合计 |
|------|:---:|:---:|:---:|:---:|
| `commonDao.execute("update ...")` | ✅ `execute`∈JDBC_TEMPLATE_METHODS，实参非空 | ✅ 内容匹配 `UPDATE...SET` + qualifier=`commonDao` 以 `Dao` 结尾 | — | **2** |
| `getHibernateTemplate().execute(callback)` | ✅ `execute`∈集合，**实参非空即标**（未校验是字符串） | ✅ 内容匹配（回调体内含 `from Wms...`）+ 方法名含 `execute` 关键字（qualifier 是方法调用不满足 Dao，但"方法名启发单方即达标"） | ✅ 内层 `session.createQuery` | **2 + 1 = 3** |
| `getHibernateTemplate().find("from ... ?", type)` | ❌ `find`∉JDBC 集合 | ❌ 内容匹配，但 qualifier 是方法调用（非 Dao）、方法名 `find` 不含关键字 | — | **0** |
| `getHibernateTemplate().findByValueBean(...)` | ❌ `findByValueBean`∉集合 | ❌ 同上（`findByValueBean` 不含关键字） | — | **0** |

三处代码事实（`CustomDaoHqlLocator.kt` / `JdbcTemplateMethodDetector.kt`）：
- `JDBC_TEMPLATE_METHODS`（JdbcTemplateMethodDetector.kt:32-49）含 `execute`；gutter 快速路径（JdbcTemplateSqlGutterLineMarkerProvider.kt:56-59）**只校验"第 1 实参非空"**，不校验是否字符串、不校验接收者 → `commonDao.execute` 与 `getHibernateTemplate().execute(callback)` 均被 JdbcTemplate 误标。
- `QUERY_METHOD_KEYWORDS`（CustomDaoHqlLocator.kt:87-89）含 `execute`；`isCustomDaoHqlCall` 的判定是 `(qualifierMatch OR methodNameMatch) AND contentMatch`——`execute` 仅靠方法名+内容即达标，**无需 DAO qualifier**，故 HibernateTemplate 的 `execute` 也被 CustomDaoHql 收走。
- `HibernateTemplate.find / findByValueBean / iterate`（HQL 字符串首参）**无任何 provider** 的方法名集合包含它们 → 缺失图标。
- `NamedQueryGutterLineMarkerProvider` 锚 `@` token，不受本类方法名冲突影响（无碰撞）。

### 1.3 附带缺陷：图标命中但点击预览无效

JDBC gutter 快速路径只查"实参非空"，`getHibernateTemplate().execute(new HibernateCallback{...})` 会标 JdbcTemplate 图标；但点击走 `JdbcTemplateSqlLocator`（实参须为字符串/拼接才聚合）时**判不中 → 预览为空**。即"图标出现但点了没反应"的另一层错乱。

### 1.4 为什么不能只靠"方法名天然区分"

三个 provider 的注释都声称"方法名集合天然区分互不冲突"（JdbcTemplateMethodDetector.kt:18-19、CustomDaoHqlGutter 的排除逻辑），但事实是**通用方法名 `execute` 被 Jdbc 与 CustomDaoHql 同时收录**，且接收者是自定义 DAO / HibernateTemplate 而非 JdbcTemplate——方法名区分的前提（接收者固定）不成立。

---

## 二、技术方案

### 2.1 接收者类型消歧（核心）

**原则：每个 provider 只认"属于自己家族的接收者"**，不靠方法名独特性占位。新增一个轻量"接收者归属判定"工具，避免各 provider 重复实现：

`ProviderReceiver`（新文件 `ui/gutter/PreviewReceiverResolver.kt`）：根据调用 receiver 解析其类型简单名（或 qualifier 文本启发），归类到 `JDBC_TEMPLATE` / `HIBERNATE_TEMPLATE` / `SESSION / ENTITY_MANAGER` / `CUSTOM_DAO` / `UNKNOWN`。

- **`JdbcTemplateSqlGutterLineMarkerProvider`**：仅当接收者解析为 `JdbcTemplate` / `NamedParameterJdbcTemplate`（或团队自研 DB 工具，如 `DbHelper`，按其 doc 约定）时才标图标（方法名仍在 `JDBC_TEMPLATE_METHODS` 内）。→ 消除 `commonDao.execute` 与 `getHibernateTemplate().execute` 的 Jdbc 误标。
- **`CustomDaoHqlGutterLineMarkerProvider`**：收紧 `execute` 等通用方法名——命中的前提必须是 **qualifier 末段匹配 DAO 后缀**（`matchesDaoQualifier`），单靠 `execute` 方法名+内容不再达标（把 `QUERY_METHOD_KEYWORDS` 中 `execute` 的"方法名启发"单独降级为"须搭配 DAO qualifier"）。→ 消除 HibernateTemplate 的 `execute` 被 CustomDaoHql 误收；`commonDao.execute`（未带标注）与 `findByQuery` 等仍正常。
- **性能红线权衡**：既有设计刻意 O(1) 纯文本判定避免 EDT 慢操作。方案采用**有界 receiver 解析**——qualifier 是 `PsiReferenceExpression` 时按变量名后缀判定（O(1)）；需解析类型时仅对明确白名单接收者做一次 `resolve()`，失败回落不做图标，避免误标优先于漏标。所有 PSI/resolve 仍在 `getLineMarkerInfo`（后台 daemon 线程，非 EDT）执行，无 EDT 慢操作风险。

### 2.2 新增 HibernateTemplate SQL-string 方法覆盖

`HibernateTemplate.find / findByValueBean / iterate`（首参 HQL 字符串）补齐图标与预览：

- **gutter**：新 `HibernateTemplateGutterLineMarkerProvider`（或并入现有 provider），锚接收者为 `HibernateTemplate` 且方法名 ∈ {`find`, `findByValueBean`, `iterate`, `findByNamedParam`}、首参为字符串/拼接/变量 → 标图标。
- **预览**：复用 `CustomDaoHqlLocator` 的同构定位（HQL 首参 + FROM 实体提取）或新增轻量定位；转换走既有 `HqlConverter` / `buildJpaPreviewResult`。`findByValueBean` 的参数按实体属性名（`:warehouseId` 对实体字段）——此场景参数值来自 sample 对象，静态不可求值，参数回填表以 `:name` 命名参数提示即可，值与 custom DAO `POSITIONAL` 同构。
- 与 2.1 的 `HIBERNATE_TEMPLATE` 接收者归类联动：`execute(callback)` 不标（实参非字符串），`find`/`findByNamedParam`/`findByValueBean` 标。

### 2.3 JDBC gutter 快速路径补"字符串实参"校验

`JdbcTemplateSqlGutterLineMarkerProvider` 把"实参非空"升级为"第 1 实参为字符串/拼接/字符串变量"（复用 `hasStringFirstArgument` 等价逻辑），杜绝 `execute(callback)` 这类"有图标但点击无效"。

### 2.4 （可选）统一预览家族去重护栏

若需根治"双 provider 同时中标"，可加一个跨 provider 共享的 `PreviewFamily` 解析：对同一 `PsiMethodCallExpression`，按固定优先级（Wrapper→@注解→JPA→HQL→CustomDaoHql→Jdbc→HibernateTemplate）归属唯一家族，各 gutter 仅认自家。此为较大重构，建议先落地 2.1~2.3 增量修复（覆盖本批全部用户场景），去重护栏留作后续可选加固。

---

## 三、实施步骤

| # | 任务 | 说明 |
|---|------|------|
| 1 | 建 `PreviewReceiverResolver` | 接收者归类（JDBC / HibernateTemplate / Custom DAO / Session / UNKNOWN）；qualifier 文本启发为主、白名单类型 resolve 兜底 |
| 2 | 改 `JdbcTemplateSqlGutterLineMarkerProvider` | 加接收者∈JDBC 家族校验；"实参非空"升级为"首参字符串"；补该类 gutter 测试 |
| 3 | 改 `CustomDaoHqlGutterLineMarkerProvider` / `CustomDaoHqlLocator` | `execute` 等通用方法名需 DAO qualifier 才命中；补/改测试 |
| 4 | 新建 `HibernateTemplateGutterLineMarkerProvider` + 定位/预览 | find/findByValueBean/iterate 等 HQL 首参 → 图标 + HQL 预览（`findByValueBean` 参数表按 `:name` 提示） |
| 5 | plugin.xml 注册新 provider；i18n 键 | 仿既有 gutter 注册（语言=JAVA） |
| 6 | 补测试 | 按第四节：三场景各断言恰 1 个图标、通用方法名不跨家族误标的回归 |
| 7 | 跑测试 | gradlew 前 `python parallel_tasks.py check`（登记独立沙盒）；gutter 包 + psource + psi.jpa 相关包全量 |
| 8 | 沙盒真机验证 | runIde 打开 demo `JpaQueryServiceImpl`：三场景逐一定位，确认图标数=预期；点击/Alt+Shift+Q 预览有效；docs/MEMORY.md 同步 |
| 9 | 收尾 | 提交信息 `fix: T-GutterDedup SQL预览图标重复与缺失修复（接收者消歧+HibernateTemplate覆盖）`；CHANGELOG/变更记录三处同步 |

---

## 四、测试计划

| 用例 | 做法 | 断言 |
|------|------|------|
| `commonDao.execute(hql...)` 恰 1 图标 | demo/测试构造 `commonDao.execute("update WmsDockBooking ...", ...)` | JdbcTemplateGutter 不标（接收者非 JDBC）；CustomDaoHql 标 1；全 provider 合计 1 |
| `getHibernateTemplate().execute(callback)` 恰 1 | `execute(new HibernateCallback{ session.createQuery("from ...") })` | JdbcTemplate 不标（实参非字符串）；CustomDaoHql 不标（无 DAO qualifier）；仅 HqlGutter（createQuery）=1 |
| `getHibernateTemplate().find(hql, ?)` 恰 1 | `find("from WmsDockBooking ... where type = ?", type)` | HibernateTemplateGutter 标 1；无其它 provider 重复 |
| `findByValueBean(hql, sample)` 恰 1 | 同上 | HibernateTemplate 标 1；参数表含 `warehouseId` 命名参数提示 |
| 通用方法名不跨家族误标 | `iterator`/`query(...)` 等在不同接收者 | 各调用至多一个图标（接收者归类驱动） |
| JDBC 家族回归 | 真 `jdbcTemplate.queryForList("select ...")` | 仍标 1 图标，预览有效 |
| 既有 HQL/CustomDaoHql 回归 | `session.createQuery`、`dao.findByQuery` | 图标数与预览不变 |

---

## 六、实施记录（2026-09-04，已实施）

实际落地对原方案的收敛/微调：

1. **未新增 `HibernateTemplateGutterLineMarkerProvider`**，改为**扩展 `CustomDaoHqlLocator.isCustomDaoHqlCall`** 按接收者分派认领 `getHibernateTemplate().find/findByNamedParam/findByValueBean/iterate`（首参字符串形态）。理由：图标本就是通用"SQL 预览"图标，CustomDaoHql 的 gutter/导航/预览链路（`previewCustomDaoHql` → `HqlConverter`）可直接复用，避免重复 provider 与 plugin.xml 注册。
2. **HIBERNATE 家族不走内容启发**：`find(hql, params)` 的 HQL 可能是**方法参数**（无初始化器，内容看不到），而 `find/findByNamedParam/findByValueBean` 方法名本身即 HQL 语义、首参恒为 HQL 字符串，故仅按"方法名 + 首参字符串形态"判定（`isStringLikeFirstArg` 覆盖字面量/拼接/String 变量与参数）。
3. **`PreviewReceiverResolver`**（`psi` 包）为唯一新增工具：`classify(call)` 归 CUSTOM_DAO/HIBERNATE_TEMPLATE/JDBC_TEMPLATE/JPA_SESSION/UNKNOWN；`getHibernateTemplate()` 方法调用型 qualifier 用名称启发（含 hibernate+template），变量型用名后缀启发 + 类型解析兜底。`isStringLikeFirstArg` 首参 String 判定（`PsiVariable` 覆盖参数/局部变量）。
4. **Jdbc gutter** 加两道门：接收者 `== JDBC_TEMPLATE` + 首参 `isStringLikeFirstArg`。
5. **`QUERY_METHOD_KEYWORDS` 移除通用名 `execute`**（改由 DAO qualifier 门控），避免与 Jdbc/HibernateTemplate 的 `execute` 跨家族碰撞。

**验收**：`DemoRecognitionTest` 直接加载真实 demo 文件（`HibernateTemplateDemoServiceImpl` + `CommonDaoHibernateImpl`）逐处调用断言——每处 ≤1 图标（无重复）、`commonDao.findByQuery/findByQueryUniqueResult/execute` 与 `getHibernateTemplate().find/findByNamedParam/findByValueBean`/`session.createQuery` 各恰 1（无缺失）、`getHibernateTemplate().execute(callback)` 判 0；`GutterIconDedupTest` 11 个 demo 形态用例 + gutter/psi.jpa/psource/JPA 全量回归绿。

---

## 五、风险与回滚

- **风险中低**：改动集中在 gutter provider 匹配逻辑 + 1 个新接收者判定工具 + 1 个新 provider，不动既有转换器；错误倾向为"少标一个图标"（若接收者无法解析则保守不标），而非错误预览 SQL，方向安全。
- **性能**：接收者判定以 O(1) qualifier 文本启发为主，类型 resolve 限白名单且发生在后台 daemon 线程，不引入 EDT 慢操作；后续可再回头对 resolve 频度做缓存。
- **行为差异**：`execute` 在非 JDBC/非 DAO 接收者（如 HibernateTemplate）上不再叠加图标——正是本次要修复的用户痛点；真 JdbcTemplate/NamedParameterJdbc 的 `execute(sql)` 行为不变。
- **回滚**：各改动独立 commit，revert 单个即可；新 provider 未注册前对既有无影响。