# WrapperSearchService 服务链搜索补全（回退跳转覆盖 IService 链形态） 开发计划

> **状态（2026-09-15 T-MergeR2IntoMaster 合流定案）**：**已随 T-JumpCapture 路线收敛，O-6 增强废弃**——master 侧用户定性「按表名搜 Wrapper 定位」不可用（同表名≠同调用点，命中即误导），WrapperSearchService 整族已删除、跳转仅依赖拦截器捕获位置 + 缺失三按钮引导；本文档随 r2 分支合流入库留档，不再实施。

- **任务ID**：T-ServiceChainSearch
- **立项时间**：2026-09-06 02:20
- **分支**：`dev`
- **前置**：F-44-6/7 日志→源码精确跳转（SqlSourceInterceptor 标记）；O-1 Phase A 回退搜索（WrapperSearchService）

## 1. 问题

实时日志右键「跳转到源码」在条目无 sourceFile（未部署 SQLVIS-SOURCE 拦截器）时回退「按表名搜 Wrapper」：`resolveEntryJump` 返回 `SearchByTable`（`SqlLogPanel.kt:1037-1055`）→ `searchByTable` 后台搜索（`SqlLogPanel.kt:1104/1114-1145`）。回退搜索 `WrapperSearchService.search` 只搜静态工厂形态：

- 遍历 `WrapperLocator.wrapperFactoryClassNames`（`Wrappers/ChainWrappers/JoinWrappers/MPJWrappers/Db` 五个，`WrapperLocator.kt:1236`），逐类 ReferencesSearch 其工厂方法（`WrapperSearchService.kt:79-82/104-106`，方法名名单 `wrapperFactoryMethodNames`，`WrapperLocator.kt:1222-1226`）。
- `service.lambdaQuery()...` 等 IService 服务链不在搜索范围。这正是 20260902-1700 修复计划点名的缺口原文：「只搜索 `Wrappers.lambdaQuery()` 等 5 个静态工厂类的引用，不覆盖 `service.lambdaQuery()` / `service.getById()` 等 IService 链式写法（绝大多数 MP 项目的实际写法）」。
- F-44-6 精确跳转上线后主路径已绕开此缺口，但未部署拦截器的用户仍命中「未找到表 xxx 对应的 Wrapper」——第二轮调研报告 §5.2 O-6 判定为「绕开未消除」。

探查附加发现（待实施首步实证）：静态工厂通道自身疑似恒空转——search 用 `facade.findClass(className, allScope)`（`WrapperSearchService.kt:80`）查类，而 findClass 参数须为全限定名，名单里全是简名（简名的本职是 qualifier 文本匹配，`WrapperLocator.kt:1229-1236`）；真实项目这些类都在包内，简名查询返回 null → continue，五类全部跳过。该判断需按 §3 步骤 3 用 light 测试复现证实后随本任务一并修正。

## 2. 方案

两条通道搜索：服务链为新增，静态工厂为修复。

### 2.1 服务链通道——复用既有判定，不另造识别

定位链路早已支持服务链（gutter 与预览在用），搜索层只是没把候选喂给它：

- `WrapperLocator.isWrapperFactoryCall`（`WrapperLocator.kt:635-648`）：qualifier 非工厂类时按「方法名 ∈ serviceChainMethodNames 且 isServiceChainStart(call)」识别服务链起点；`service.update(wrapper)` 等消费调用被 `isServiceChainStart`（无实参 + 有后续链调用，O(1) 纯语法，`WrapperLocator.kt:656-662`）天然排除。
- `locateWithTable(ref.element)`（`WrapperLocator.kt:92-100`）→ findWrapperFactoryCall 沿父链找到调用（`WrapperLocator.kt:442-452`）→ buildChainFromFactory 按调用返回类型解析实体（`LambdaQueryChainWrapper<T>` 的 T，`WrapperLocator.kt:474`，`LambdaQueryChainWrapper` 已在类型映射表，`WrapperLocator.kt:1328`）→ TableInfo → 复用现有 `matchesTable` 过滤（`WrapperSearchService.kt:57-60/114`）。

搜索端做法：`JavaPsiFacade.findClass("com.baomidou.mybatisplus.extension.service.IService", allScope)`（FQN 经 javap 实证：本地 `~/.m2` mybatis-plus-extension-3.3.1.jar，`IService` 声明 `lambdaQuery()` → `LambdaQueryChainWrapper<T>`、`lambdaUpdate()` → `LambdaUpdateChainWrapper<T>`、`query()` → `QueryChainWrapper<T>`、`update()` → `UpdateChainWrapper<T>`），遍历其**无参**方法且方法名 ∈ `WrapperLocator.serviceChainMethodNames`（`WrapperLocator.kt:1244`，与锚点判定单一来源）→ `ReferencesSearch(method, projectScope, false)` → ref.element 交 `locateWithTable` → `matchesTable`。无参过滤同时排除 `update(Wrapper)`/`update(T, Wrapper)` 消费重载，ReferencesSearch 只跑链起点四个方法。

### 2.2 静态工厂通道 FQN 修复

新增简名→FQN 对照清单（证据源随行注释）：`Wrappers` → `com.baomidou.mybatisplus.core.toolkit.Wrappers`（core jar javap 实证）、`ChainWrappers` → `com.baomidou.mybatisplus.extension.toolkit.ChainWrappers`（extension jar javap 实证）、`Db` → `com.baomidou.mybatisplus.extension.toolkit.Db`（`WrapperLocator.kt:1232` 注释）、`JoinWrappers`/`MPJWrappers` → `com.github.yulichang.toolkit.*`（`WrapperLocator.kt:1230` 注释，实施时按项目实际 MPJ 版本 jar javap 复核；findClass 查不到 null → continue，无害跳过）。该修复把静态工厂通道恢复为「设计意图的行为」，服务链通道叠加其上。

### 2.3 性能取舍（沿用现有模型，不新增缓存）

- **EDT 合规**：现有搜索无线程问题——`searchByTable` 已是 `Task.Backgroundable` + `ReadAction.compute`（`SqlLogPanel.kt:1115-1145`），`navigateTo` invokeLater 回 EDT（`WrapperSearchService.kt:125-134`）。新增通道在同一 `run(indicator)` 内顺序执行，不新增任何 EDT PSI 读点。
- **成本控制**：ReferencesSearch 限定 projectScope，代价 O(项目内对目标方法的引用数)，不是全项目 PSI 遍历；服务链通道对 IService 四个无参方法各跑一次，与既有工厂通道同量级。多候选沿用 distinctBy 锚点偏移去重 + 既有多候选选择弹窗（`WrapperSearchService.kt:87-93`、`SqlLogPanel.kt:1148-1163`）。现有实现无结果上限——本次补 `MAX_CANDIDATES`（50，超出截断并 LOG.info），防极端项目弹窗爆列表。
- **不加缓存**：搜索是用户右键触发的一次性动作，跨编辑缓存有陈旧候选风险（文件改了引用没了），表级重复跳转频率低，不值得引入失效复杂度——现有实现无缓存，维持。

### 2.4 具体改动

| 文件 | 改动 |
|------|------|
| `log/WrapperSearchService.kt` | ① 新增 `SERVICE_CHAIN_RECEIVER_FQN`（IService）与 `wrapperFactoryClassFqns`（简名→FQN 对照，注释标注证据源与「实施时 javap 复核」项）；② `search()` 工厂通道改用 FQN 清单；③ 新增 `searchServiceChain`（IService 无参链起点方法 ReferencesSearch → locateWithTable → matchesTable）；④ `MAX_CANDIDATES` 截断 + LOG.info；⑤ `matchesTable` 等纯函数不动 |
| `log/WrapperSearchServiceTest.kt` | 新增 BasePlatformTestCase 用例（fixture 源码级 stub：com.baomidou 包下 IService/链 Wrapper 类、实体、用户 service 接口，myFixture.addClass 注入） |
| `SqlLogPanel.kt` | 无改动（线程模型、弹窗、提示语全复用） |

### 2.5 测试设计

- 服务链命中：`userService.lambdaQuery().list()`（`IUserService extends IService<User>`）→ Single，表名按命名策略推断命中目标表名；
- 静态工厂旧行为回归：`Wrappers.<User>lambdaQuery()`（stub FQN 类）仍命中（同时证伪/证实 §1 附加发现）；
- 消费重载不误报：`userService.update(w)` 不产生候选；
- 不命中：表名不匹配 → None；多命中：两处同表服务链 → Multiple（选择弹窗为 UI，不进单测，与既有 headless 约定一致）。

边界：① Kotlin 文件里的服务链调用在 locateWithTable 走 F-4 MVP 路径 table=null（`WrapperLocator.kt:114-135`），被 matchesTable 过滤——首批只覆盖 Java 调用方，与既有搜索的有效覆盖一致；② 用户 service 子接口若覆写 `lambdaQuery`，引用 resolve 到覆写方法而非 IService 声明，搜不到——罕见形态，记录为已知边界；③ `getById/save` 等非链式 IService 方法不在本次范围（O-6 行描述只补链形态）。

## 3. 实施步骤

1. 本计划提交立项（docs）。
2. 注册并行沙盒（`python C:\Users\USER\.zcode\cli\parallel_tasks.py check` → `register --name svc-chain-search`）+ build.gradle.kts 临时沙盒隔离。
3. 先写复现用例（简名 findClass 恒空 + 服务链不命中），实证 §1 附加发现并留下量级基线（候选数/耗时日志）。
4. 实施 §2.4 改动。
5. 编译 + 定向测试（WrapperSearchServiceTest）→ 全量测试。
6. 文档三处同步：README 功能区「未注册时按表名智能搜索实体近似跳转」补服务链表述（`README.md:76`）、docs/变更记录.md 追加（日期倒序）、本计划 §变更记录回填。
7. 还原沙盒隔离、注销登记（`done`）。

## 4. 验收标准

- [ ] 未部署拦截器场景：`service.lambdaQuery()`/`lambdaUpdate()`/`query()`/`update()`（无参链起点）链可被回退搜索命中并正确跳转（Single 直跳 / Multiple 弹选择）
- [ ] 静态工厂通道恢复设计意图行为，FQN 对照覆盖全部五类，既有候选不丢
- [ ] 消费调用（`service.update(w)` 等）不误报；表名不匹配不命中；精确跳转优先级与 NoTable 提示等旧行为不变
- [ ] 搜索线程模型不变（Task.Backgroundable + ReadAction），无新增 EDT PSI 读
- [ ] 新增用例全绿 + 全量回归绿

## 5. 风险

- 服务链全项目扫描的成本：ReferencesSearch 四方法 × projectScope，量级与既有工厂通道同阶；`MAX_CANDIDATES` 截断兜底极端项目；实施步骤 3 的复现用例同时输出候选数/耗时作量级证据。现有 `searchInClass` 本就无遍历范围上限参数可复用（ReferencesSearch 的 scope 即范围限制），故不引入自研遍历。
- EDT 合规：全部 PSI 读在 Task.Backgroundable 的 `ReadAction.compute` 内（既有结构，`SqlLogPanel.kt:1125`），导航与弹窗走 invokeLater（`WrapperSearchService.kt:126`）——本任务不触碰线程模型。
- IService FQN 解析依赖 MP 在用户项目 classpath（与既有工厂类 findClass 同前提）；MP 缺席 → findClass null，通道跳过回退「未找到」提示，不劣化现状。
- 引用 resolve 到子接口覆写方法时漏搜（§2.5 边界②）：影响面小，记已知边界，不做兜底全项目扫描（成本失控反向违背 O-6 初衷）。

## 变更记录

- 2026-09-06 T-ServiceChainSearch 立项：O-6 WrapperSearchService 服务链补全（IService 无参链起点 ReferencesSearch 复用 locateWithTable 判定 + 静态工厂简名→FQN 修复 + 候选上限）。
- 2026-09-06 T-ServiceChainSearch 交付：§1 附加发现「简名 findClass 恒空」复现实证（heavy 集成用例：静态工厂/服务链代码在场均 0 候选，4 用例失败留证）后随本任务修复；服务链通道（IService 无参链起点，方法名过滤复用 serviceChainMethodNames）+ 简名→FQN 对照（五类全部本地 jar javap 实证）+ MAX_CANDIDATES=50 截断；测试基类由计划的 BasePlatformTestCase + myFixture.addClass 调整为 HeavyPlatformTestCase + 磁盘源码注入（本环境 myFixture.addClass 不可解析、Light 跨文件 resolve M27 已实证不可用，沿 InvocationMpjHeavyTest 先例），定向 12/12 绿、全量回归绿；README:76 与 docs/变更记录.md 同步。
