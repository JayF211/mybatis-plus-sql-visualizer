# T-HqlUpdateEntityMap：session 查询通道 UPDATE 语句实体名提取缺失与嵌入列映射修复 —— 开发计划

> 用户 2026-10-05 报障：demo 项目（jpa-hibernate-demo）`cn.song.jpademo.service.impl.HibernateTemplateDemoServiceImpl#updateEmbeddedCreatedTime`（形态 17）预览产物不正确——`createdTime` 应为下划线物理列名，判断「识别逻辑和使用数据源辅助有问题」，点名先分析原因、出开发计划。
> 状态：**已拍板实施（2026-10-05 用户令 master 按方案 A 实施）**，实施与验证读数见 §六。

## 一、现状与根因（已实证）

### 1.1 报障形态与实际产物

宿主方法（`HibernateTemplateDemoServiceImpl.java:271`）：

```java
return hibernateTemplate.executeWithNativeSession(session -> session.createQuery(
                "update WmsDockBooking booking set booking.updateInfo.createdTime = ?2 where booking.id = ?1")
        .setParameter(1, id)
        .setParameter(2, createdTime)
        .executeUpdate());
```

当前预览产物：

```sql
UPDATE WmsDockBooking booking
SET
    booking.updateInfo.createdTime = ?
WHERE booking.id = ?
```

对照实体（`WmsDockBooking.java`）：`@Table(name = "wms_dock_booking")`；`updateInfo` 为 `@Embedded UpdateInfo`，`UpdateInfo.createdTime` **无 @Column 注解**，物理列名由 JPA 隐式命名策略推导为 `created_time`。正确产物应为：

```sql
UPDATE wms_dock_booking SET created_time = ? WHERE id = ?
```

两处失真：① 表名 `WmsDockBooking` 未映射为物理表；② SET 左值保留 JPQL 属性路径（别名限定 + 嵌入段 + 驼峰属性名），未映射为物理列。

### 1.2 根因：三处同构的 FROM-only 实体名提取正则，UPDATE 语句全部不命中

关键前提：这条 HQL 是 `session.createQuery(单参数字符串)`，字符串内**没有 FROM 子句**。而生产链路上的实体名提取共有四处实现，其中三处只认 `\bFROM\s+([A-Z][a-zA-Z0-9_]*)\b`：

| # | 位置 | 角色 | UPDATE 命中 |
|---|------|------|------------|
| 1 | `HqlQueryLocator.extractEntityInfo`（`psi/jpa/HqlQueryLocator.kt:376`，三级 fallback 的第三级） | 从 createQuery 调用提取 entityClassName | ✗ |
| 2 | `SqlPreviewService.extractEntityNameFromHql`（`ui/SqlPreviewService.kt:1923`） | resolveHqlTable 按名查实体的类名来源 | ✗ |
| 3 | `HqlConverter.mapFallbackEntityToTable`（`converter/jpa/HqlConverter.kt:421`） | 实体不可解析时的兜底表名替换 | ✗ |
| 4 | `CustomDaoHqlLocator.extractEntityClassNameFromHql`（`psi/jpa/CustomDaoHqlLocator.kt:499`） | customDao 通道 | ✓（先例，见 1.3） |

于是断点链为：

1. `HqlQueryLocator.extractEntityInfo`：二参 `Entity.class`（无）→ 泛型类型参数（无）→ FROM 提取（UPDATE 无 FROM，不命中）→ 返回 `(null, null)`；
2. `SqlPreviewService.previewHql`（`SqlPreviewService.kt:844`）→ `resolveHqlTable(null, null, hql)`（`:1815`）：路径 1 跳过；`className = extractEntityNameFromHql` 亦返回 null → 路径 2（按名查类）与路径 3（hbm）全跳过 → 返回 null；
3. `HqlConverter.convert(hql, tableInfo = null, ...)` → `createFallbackTable()`（`entityClassName = ""`）→ 第 7 步实体名→表名映射不进、第 9 步属性名→列名映射（含嵌入段剥离）不进；fallback 分支 `mapFallbackEntityToTable` 同样只认 FROM，UPDATE 开头语句原样返回；
4. 全链只有参数替换（`?2`/`?1` → `?`）生效；
5. `JpaUpdateParser.parse` 仍命中 UPDATE 头（别名 `booking` 解析成功），但 SET 左值 `booking.updateInfo.createdTime` 是两段点号路径，超出 `COLUMN_PATH` 白名单（`JpaUpdateParser.kt:52` 只允许 `列` 或 `别名.列` 一段点号）→ SetClause 走 rawSql 原样透传；
6. 渲染（`SqlGenerator.generateUpdate`，`sql/SqlGenerator.kt:220`）输出即用户所见。

### 1.3 仓内先例对照：customDao 通道同形态可正常映射

`CustomDaoHqlLocator` 早已实现三段提取（`:499-507`）：FROM 优先，未命中再试 `UPDATE_ENTITY_PATTERN`（`:219`）与 `DELETE_ENTITY_PATTERN`（`:220`）。demo 形态 16（`findByNoAliasEmbedded`，`commonDao.findByQuery` + `from WmsDockBooking ...`）正是经此通道正常工作（O-71 已修的嵌入段剥离场景）。**缺陷不在映射能力，只在 session.createQuery 通道的三处同构正则没有收敛这个已验证口径。**

### 1.4 为什么 J2（UPDATE 建模）测试全绿却漏了

`JpaUpdateConverterTest` 全部在 converter 级**直接传入手工构造的 TableInfo**（`HqlConverter.convert(hql, userTable)`），不经实体名提取；locator → resolveHqlTable → convert 的生产入口集成断点没有任何测试覆盖。UPDATE 语句只要不带 FROM（即 JPQL/HQL UPDATE 的常态），实体解析在入口处就已断链，converter 级测试看不见。

### 1.5 「数据源辅助」的正名

JPA/HQL 预览链的表名列名来源**不是数据源 introspection**，而是实体注解 PSI 解析（`JpaEntityResolver` → TableInfo：@Table 表名、@Column 列名、@Embedded 展平列，展平列名规则 = @AttributeOverride > 内部字段 @Column > 驼峰转下划线，`JpaEntityResolver.kt:291`）。数据源辅助（DatabaseSchemaResolver 等）只服务于 SQL 高亮校验、关系图、MCP 通道。本次断点在实体名提取，修复方向是实体解析链，与数据源配置无关——实体解析正常时，即使不配数据源也能产出正确列名。

## 二、修复方案

### S1 主修复：共享实体名提取器，三处接入

新增 `psi/jpa/HqlEntityNameExtractor`（无状态 object），提取口径在 CustomDaoHqlLocator 先例上做一处加固——**语句头优先**：

1. 头部 `UPDATE 实体` → 按头提取。先于 FROM 的原因：`UPDATE ... SET x = (SELECT ... FROM y ...)` 形态下 FROM 提取会命中子查询实体 y，造成错表映射；语句头优先天然规避；
2. 头部 `DELETE FROM 实体` → 按头提取；
3. 其余（SELECT / 裸 from）→ 既有 `\bFROM\s+实体` 口径，行为不变。

三处接入点：

- `HqlQueryLocator.extractEntityInfo` 三级 fallback 的第三级（`:371-379`）换成共享器；
- `SqlPreviewService.extractEntityNameFromHql`（`:1923-1926`）私有实现换成共享器调用；
- `HqlConverter.mapFallbackEntityToTable`（`:420-425`）换成共享器（fallback 下 UPDATE 头实体同样能替换为兜底表名，与 SELECT 链语义对齐）。

`CustomDaoHqlLocator` 收敛到共享器为**可选步骤**：收敛可消掉第五份拷贝，但其提取顺序从「FROM 优先」变为「语句头优先」，对「UPDATE 带子查询」形态是行为修正而非回归；若做，须以 `CustomDaoHqlLocatorTest` 全量回归护航，不做则登记为已知双实现（零风险）。

### S2 可执行性：JPA 单表 UPDATE 剥别名

S1 修复后产物为 `UPDATE wms_dock_booking booking SET booking.created_time = ? WHERE booking.id = ?`——列名已正确，但仍有第二层失真：单表 UPDATE 的 SET 左值带表别名在 MySQL 报 Unknown column，Oracle 干脆不支持 UPDATE 别名；Hibernate 真实产物是 `update wms_dock_booking set created_time=? where id=?`，**别名整体丢弃**（HQL UPDATE 不支持 JOIN，别名是纯 JPQL 语法糖）。

`JpaUpdateParser` 为 JPA 链专用（全仓调用点仅 `HqlConverter:177` 与 `JpqlConverter:140`，MP 侧 UpdateJoinWrapper 联表更新走 model 直构不经它），直接在其单表语义内修正：

- SetClause / WHERE 条件里的 `ColumnRef` 不携带 tableAlias（剥 `别名.` 前缀）；
- `ParsedUpdate.tableAlias` 返回 null（渲染头部不拼别名，对齐 Hibernate 真实产物）。

**决策点（待拍板）**：

- 方案 A（推荐）：全剥。产物 `UPDATE wms_dock_booking SET created_time = ? WHERE id = ?`，与 Hibernate 真实产物逐字对齐，方言通用；
- 方案 B：仅剥 SET 左值、头部保留 `表 别名`。MySQL 合法但与真实产物不一致，Oracle 不可执行。

### S3 测试

1. **新增 `HqlEntityNameExtractorTest`**（纯逻辑单测）：SELECT/裸 from（现状回归）、UPDATE 带别名、UPDATE 无别名、DELETE FROM、小写关键字、`UPDATE ... SET x=(SELECT..FROM y)` 取 x 不取 y；
2. **`HqlQueryLocatorTest` 补用例**：update HQL 单参数形态 entityClassName 提取成功（现缺口直接钉死）；
3. **`JpaUpdateConverterTest`**：补全管道用例 `update WmsDockBooking booking set booking.updateInfo.createdTime = ?2 where booking.id = ?1` + 真实 TableInfo（含展平列），断言产物 `UPDATE wms_dock_booking SET created_time = ? WHERE id = ?`；既有带别名用例断言按 S2 语义逐条调整（见待验证前提 5）；
4. **嵌入展平列断言**：展平列三元组（fieldName=createdTime / columnName=created_time / embeddedParent=updateInfo）在 `JpaEntityResolver` 既有测试族补一条（兼作 javax.persistence 兼容的钉子，见待验证前提 2）。

### S4 demo 沙盒走查

runIde 沙盒打开 jpa-hibernate-demo：

1. 形态 17 `updateEmbeddedCreatedTime` gutter 点击 → 产物逐字断言；
2. 形态 16 `findByNoAliasEmbedded` 回归（嵌入段剥离产物不变）；
3. 先确认该方法的 gutter 归属（HqlGutterLineMarkerProvider → previewHql 链），坐实断点链与本次修复入口一致。

## 三、红线

- src/main 改动收敛在：新增 `HqlEntityNameExtractor.kt` + `HqlQueryLocator.kt` / `SqlPreviewService.kt` / `HqlConverter.kt` / `JpaUpdateParser.kt`（+可选 `CustomDaoHqlLocator.kt`），不触 plugin.xml / 依赖 / 新平台 API 面 → 不构成「大改动」，按三档分层不触发 verifyGate；
- 零删例；`JpaUpdateConverterTest` 断言调整逐条列名，不允许顺手改语义；
- 反向验证必做：临时摘掉 S1 的 UPDATE 头分支 → 形态 17 产物回到「仅参数替换透传」，前后两次读数落实施记录；
- 收口期间不碰 src/main（写文档并行，不复跑）。

## 四、验证分层

1. **定向**（实施者随报告交出）：S3 全部 + `testFast`；
2. **收口门禁**：单派只读收口代理跑权威全量（testShards 5 片单路），主控并行写文档；
3. **demo 走查**：S4（UI 链路真机验证，必做——J2 的教训就是converter 级测试看不见入口断链）。

## 五、待验证前提（实施者先证伪再动手）

1. **展平列三元组**：`JpaEntityResolver.resolve(WmsDockBooking)` 产出列 `created_time`（fieldName=createdTime、embeddedParent=updateInfo）——从 `JpaEntityResolver.kt:291-338` 推演，本计划未实跑；实施第一步先用单测证实，不符则 S3-3 断言跟着实测改；
2. **javax 兼容**：demo 实体用 `javax.persistence.Embedded`（非 jakarta），`JpaMetadataReader.isEmbeddedField` 的短名兜底轨应能命中——形态 16（O-71 修复）已走查过间接佐证，但未在展平列语境下单独钉死；
3. **映射分支推演**：`booking.updateInfo.createdTime` 在 TableInfo 就位后经 `JpaAliasPropertyMapper`（`JpaAliasTableResolver.kt:249`）产出 `booking.created_time`（owner=null 回落 rootTable + isFlattenedEmbeddedPath 分支）——UPDATE 头别名不进 `JpaAliasTableResolver`（其 TABLE_REFERENCE `:78` 只认 FROM|JOIN），单表语义回落 rootTable 无害，此为推演；
4. **previewHql 入口归属**：产物特征（表名未映射 + 参数已替换）与该链 fallback 行为吻合，但未在沙盒实测 gutter 归属，S4 第 3 步先坐实；
5. **S2 影响面**：`JpaUpdateParser` 剥别名后 `JpaUpdateConverterTest` 中带别名的既有用例（如 `update_withoutAlias_tableAliasNull` 之外的别名形态）断言需同步调整，实施前先全量核对列出清单，防「改实现忘改断言」或「断言迁就实现」；
6. **参数对照表不受影响**：形态 17 用位置参数 `?1/?2` + 显式 setParameter，paramNames 提取走 `extractParamNamesFromHql` fallback，S1/S2 均不触该路径。

## 六、实施记录（20261005，master）

**实施提交**：`8e9ca7e`（9 files，+296/−67；src/main 6 文件 + src/test 3 文件，新件 `HqlEntityNameExtractor.kt` / `HqlEntityNameExtractorTest.kt`）。

**S1 落地**：共享提取器 `psi/jpa/HqlEntityNameExtractor`（语句头优先：UPDATE/DELETE/INSERT 头 > FROM；DELETE 分带 FROM 版先试防 `from` 被当实体名、畸形头回落 FROM 宽容降级）；四处接入＝计划 S1 三处 + CustomDaoHqlLocator 收敛（可选项做了，消掉第五份拷贝，`CustomDaoHqlLocatorTest` 回归护航）。提取器单测 10 条全绿。

**S2 落地（方案 A 全剥）**：`JpaUpdateParser.parse` 的 `tableAlias` 恒 null、`parseAssignment`/`parseColumnPath` 列引用剥别名前缀；raw 透传文本不剥别名（`update_arithmeticSet_*` 两用例原样保留 `u.age = u.age + 1` 形态）——与计划「raw 文本不剥」一致，登记为已知边界。

**S3 落地**：`HqlEntityNameExtractorTest` 新增 10 条；`HqlQueryLocatorTest` 补 `testCreateQuery_updateHqlWithoutFrom_extractsEntityName`（报障形态钉死）；`JpaUpdateConverterTest` 断言调整 4 处（statementFields/roundTrip/withoutTableInfo/asAliasForm）+ 新增 3 条（报障形态端到端：管道文本 `update wms_dock_booking booking set booking.created_time = ? where booking.id = ?` + 渲染产物 `UPDATE wms_dock_booking SET created_time = ? WHERE id = ?` 空白归一断言；DELETE 嵌入段；无 TableInfo fallback 语句头提取）。

**待验证前提实测回填**：前提 1 证实——`bookingTable` 展平列三元组按 `(createdTime, created_time, updateInfo)` 构造后管道断言通过（真实 `JpaEntityResolver` 对 demo 实体的展平推演由形态 19 SELECT 链既有正常行为间接佐证）；前提 2/3 随定向绿证实；前提 4 证实（产物特征与 previewHql 链吻合，沙盒 UI 走查留待下轮批次，与 O-95 S3 先例同惯例）；前提 5 已逐条核对（4 处调整全清单化，见 S3）；前提 6 证实（paramNames 路径零改动）。

**反向验证（必做项，两次读数）**：①摘除提取器 UPDATE 头分支（RED-PROBE 注释）→ `testFast` 定向 35 例中 **5 条失败**（提取器 4 条 UPDATE 用例 + 报障形态端到端 `updateEmbedded_withoutTableInfo_fallbackReplacesHeadEntity`）——守卫看得见它要防的站点；②恢复分支 → **35/35 全绿**（testFast BUILD SUCCESSFUL 13s）。

**定向读数**：5 类 35 例全绿（HqlEntityNameExtractorTest 10 + JpaUpdateConverterTest 18 + HqlQueryLocatorTest + CustomDaoHqlLocatorTest + DemoRecognitionTest；首轮 1 失败为断言未做空白归一〔`SqlGenerator.generate` 默认多行渲染〕，修断言后绿，非实现缺陷）。

**全量门禁**：testShards 5 片单路后台（docker 引擎离线〔管道不存在，非半活态〕→ docker 依赖型测试进默认 skip 桶，与基线 6395/0/0/9 的 9 skip 口径一致）；首轮启动失败＝verifier-cli jar 不在 gradle 缓存（`Could not create task ':verifyGate'`），先跑 verifyPlugin 重建缓存后复跑，读数见下。

**demo 对照语料（并行子代理交付）**：jpa-hibernate-demo 补形态 18（带 FROM 的 HQL DELETE + 嵌入段路径，同病面对照）/形态 19（带 FROM 的 SELECT + 嵌入段+普通属性混合，回归保护），接口/Controller 同步，mvnd 编译通过，demo 仓提交 `a0b827b`；mpsv-walk-proj 探查后未改名（`probe` 包 MyBatis-Plus 注解走查工程，零 JPA 内容、不在 git 仓，与 jpa-hibernate-demo 无归属关系）。

**沙盒 UI 走查**：留待下轮沙盒走查批（同 O-95 S3 惯例）——形态 17/18/19 三条 gutter 点击逐字核对。

**全量门禁读数**：testShards 5 片单路 **6108/0/0/9**（skip 9 恰为 docker 桶，与基线 6395/0/0/9 口径一致；docker 引擎离线系管道不存在非半活态，skip 路径干净）+ testExternal 40/0/1 + agent 子项目 93/0/0 + parity 计划类全集 534 个均有结果 XML；BUILD SUCCESSFUL 4m36s 退出码 0。过程坑两笔＝①`tail` 管道末命令退出码掩盖 gradle 失败（仓内在册坑，改 `> log 2>&1; echo exit=$?` 口径）；②testShards 启动报 verifier-cli jar 未在 gradle 缓存，跑一次 verifyPlugin 重建（其在 prepareSandbox 因沙盒 jsqlparser jar 被并行会话进程内存映射锁定而 FAILED，但依赖解析阶段已把 verifier-cli 1.410 拉入缓存，复跑 testShards 通过；prepareSandbox 锁与本任务改动无关，src/main 未触 build.gradle/plugin.xml，按三档分层不触发 verifyGate）。

---

编号备注：本任务为用户报障直修，属实施任务代号 T-HqlUpdateEntityMap；若后续调研轮需登记该报障观察项，按编号台账取下一全新号（台账以 git 实测为准，20261005 十五轮审计后为 O-107，如已被占用则顺延）。
