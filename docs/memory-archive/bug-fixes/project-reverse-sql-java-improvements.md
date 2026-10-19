---
name: project-reverse-sql-java-improvements
description: SQL→Java 反向链路质量大修已提交（c1b8f56 + b77361f，23+10 文件）——? 占位符/实体推断/调用形态 MP·MPJ 1.5.6 实证/继承链 MPJ 能力/性能修复/缓存管理/交互优化，1111 全绿
metadata:
  node_type: memory
  type: project
  originSessionId: sess_51eafb4b-6feb-4624-a097-590ca8ab7f68
---

2026-08-16 SQL→Java 反向链路连续多轮优化，全部由用户反馈驱动，**已提交 c1b8f56（23 文件 +1512/-148，提交时精确排除并行会话的图标改动，见 [[pitfall-parallel-inflight-block]]）；最新 1104 全绿（clean test --rerun-tasks）**。最后两轮（类选择字段交互 + 空态提示）未提交。

1. **`?` 参数占位符反向识别**（[[project-reverse-placeholder-bugfix]]）：`parseValue` 的 `?` → `SqlValue.Placeholder("?")`，WHERE 各算子（eq/ne/gt/ge/lt/le/in/between/like 占位符→LikePosition.NONE）+ UPDATE SET/INSERT VALUES/LIMIT 全链路；生成器头部统一注释（hasPlaceholder 扫描）。
2. **AUTO 优先对象引用**：`EntityClassFinder.find` 两级查找——类名推断快路径 + 未命中时全量 @TableName 注解值/推断表名反查（`AccountRoleRelEntity`↔`sys_account_role_rel` 兜底）；结果按类名排序。
3. **手动选择实体类**（可选开关 `reverseManualEntitySelection` 默认开）：`ui/EntitySelectionDialog` 按表逐行分组（主表 MAIN_KEY + JOIN 副表别名），自动候选预选第一个、Goto Class 挑选、✕ 清除；JOIN 一次多选替代逐表弹窗、主表 0 候选兜底（弹窗前先写回落结果）；开关关 → 纯自动推断不弹窗。
4. **调用形态修正 + 变量化**：分页 SQL（LIMIT）→ `Page<X> page = xxxService.page(new Page<>(c, s), wrapper)`（Mapper→selectPage；current=offset/size+1 推断、占位符回落 1/10+注释；分页调用不再生成 `.last("LIMIT...")`）；VO 与实体同名回落普通调用；**调用类全部 8 处调用行变量化**（`callerVar`：`BannerService`→`bannerService`，复制即用）。
5. **MP/MPJ 全量 API 实证审计**（用户本地 jar javap/源码，MP 3.5.14 + MPJ 1.5.6）：MP 全通过（IService/BaseMapper/AbstractWrapper 全部条件方法含 **notLikeLeft/notLikeRight 存在**、Query.select(R...) 变参）；MPJ 侧：**`listVo`/`pageVo`/`selectVoPage`/`selectVoList` 全部不存在**（旧 1.2.x 命名）→ VO 查询改 `selectJoinList(Vo.class, wrapper)`、分页+VO `selectJoinListPage/selectJoinPage(page, Vo.class, wrapper)`（参数顺序 page→VO→wrapper）；**字符串 raw join 仅 StringJoin 合法**（MPJLambdaWrapper 无 leftJoin(String)）→ 副实体缺失/ON 多条件回落 MPJQueryWrapper（含显式 MPJ_LAMBDA 也强制回落）；**DeleteJoinWrapper 无 (Class, Class...) 构造** → `new DeleteJoinWrapper<>(主.class)` + `.delete("t", "t1")` 目标声明，不可映射明确报错；MPJQueryWrapper 包名实证 `com.github.yulichang.query`。
6. **调用类继承链实证 MP/MPJ 能力**：`SqlToJavaConverter.invocationHasMpj`（索引取类 + 接口/父类递归匹配 MPJBaseService/JoinService/MPJBaseMapper/JoinMapper）→ `InvocationTarget.mpj` 驱动：无 MPJ 能力 → VO 回落实体查询（注释「调用类无 MPJ 能力，VO 查询不可用」）、多表 JOIN/删除明确报错；面板后台 ReadAction 解析。
7. **类选择字段交互 + 空态引导**：整块可点（Swing 事件不冒泡，见 [[pitfall-swing-mouse-no-bubble]]）；清除图标 12×12（IconScaler，见 [[pitfall-ipgp-stripped-ui-classes]]）；两 tab 输入/结果区空态灰字提示行（DocumentListener 显隐，`Editor.setPlaceholder` 2024.3 不可用实证）。
8. **无实体场景性能修复**（[[project-reverse-entity-scan-performance]]）：EntityClassFinder 反查全量扫描（O(项目类数)）→ P0 项目级缓存（PsiModificationTracker 失效，存类名+全限定名非 PsiClass）+ P1 启发式去首段候选 + P2 跳过接口枚举；估算 100-500 倍提速。
9. **设置页插件缓存管理**（用户多轮反馈迭代）：`util/PluginCacheManager`（`clearAll`/`sizeBytes`/`formatSize`）统一缓存入口——**后续新增任何缓存类型只在此注册，设置页零改动**；「更新与诊断」分区「插件缓存」行：显示**缓存大小**（B/KB/MB 格式化，「当前缓存：{0}」，所有打开项目 openProjects 汇总；大小估算 = UTF-16 字符×2 + 条目/值固定开销近似）+「清除缓存」按钮；**清除反馈 = 信息行状态文本「已清除」持续显示**（仿检测更新 statusLabel 语义——按钮文本变化方案被用户否决；Timer 废弃）。
10. **更新分区布局**（用户多轮反馈迭代，示例布局驱动）：最终形态 = 设置面板 `addRow` 标签「版本信息」+ UpdateSection 内容行「当前版本：2026.8.16 [检查更新] [下载并安装]（状态文本）」流式左对齐；「插件缓存」行位于版本信息**上方**（用户要求）；bundle 文案「当前版本」（先改「版本信息」再被用户改回）。**对齐教训**：UpdateSection 组件内部自建标签列（versionInfoLabel）与外部 addRow 标签列**宽度各自计算 → 左侧标签/内容列错位**（用户反馈「调整对齐下左侧的」，deepseek-eyes 截图分析定位）——修复 = 标签移出组件、全部走 addRow 统一标签列，组件只含内容行（组件内行自然与内容列对齐）。
11. **bundle 脚本事故修复**：python 脚本插入 `settings.cache.cleared` 键时与 `settings.cache.clear` 合并为一行（键值错乱）→ UI 显示原始键名（用户截图 + deepseek-eyes 分析定位，见 [[pitfall-script-edit-kotlin-escapes]]）；已修复两个 bundle 文件。

**Why:** 用户核心工作流「先 Java→SQL 再复制 SQL→Java」round-trip + 明确要求「严禁调用不存在的 API、生成代码复制即可编译运行」（见 [[feedback-compilable-generated-code]]）；方法名表以用户本地 jar 反编译为唯一权威（官方文档不记录 Internal/新版本 API）。
**How to apply:** 用户场景最终输出 `LambdaQueryWrapper<BannerEntity>` + `.eq(Entity::getX, "?")` + `Page<BannerEntity> page = bannerService.page(new Page<>(1, 10), wrapper);`；tab 标题已标 Beta（`log.tab.sqlToJava=SQL → Java（Beta）`）；测试数字以 `clean test --rerun-tasks` 为准。

相关：[[project-t36-deliveries]] [[project-reverse-placeholder-bugfix]] [[project-reverse-entity-scan-performance]] [[pitfall-ipgp-stripped-ui-classes]] [[pitfall-kotlin-incremental]] [[pitfall-heavy-psi-interface-resolution]] [[feedback-compilable-generated-code]]
