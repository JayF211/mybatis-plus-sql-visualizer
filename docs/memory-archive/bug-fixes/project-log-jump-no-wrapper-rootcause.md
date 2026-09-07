---
name: project-log-jump-no-wrapper-rootcause
description: SQL日志右键跳转源码提示「未找到表对应的Wrapper」根因——事后搜索Wrapper本质不可靠，正确方案是SqlSourceInterceptor自动注入（F-44-7）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c734d45f-3322-46ab-a909-a964b378b74d
---

SQL 日志列表右键「跳转到源码」提示 `未找到表 "xxx" 对应的 Wrapper`（2026-09-02 分析）。

## 根因：事后搜索 Wrapper 本质不可靠

`WrapperSearchService.search` 从 SQL 提取表名后搜索项目代码中引用该表的 Wrapper 链——这是**事后猜测**，搜索范围仅覆盖 `Wrappers`/`ChainWrappers` 等 5 个静态工厂类，不覆盖 `service.lambdaQuery()`/`service.getById()` 等 IService 链式写法（绝大多数 MP 项目的实际写法）。即使搜索成功，找到的 Wrapper 也不一定是产生该 SQL 的那条。

## 正确方案：SqlSourceInterceptor 自动注入（F-44-7）

**用户确认**：正确做法是在 SQL 执行时记录源码位置（`SqlSourceInterceptor`），而非事后搜索 Wrapper。

**可行性分析结论**（4 种方案对比）：
- **A. 自动生成配置类**（⭐⭐⭐ 推荐）：插件在用户项目中创建 `SqlSourceInterceptor.java` + `@Configuration` 类，一次配置永久生效，IDE/命令行/生产均有效
- B. Java Agent 注入：仅 IDE 运行有效，需字节码工程，复杂度高
- C. Spring Boot Starter：需发布 Maven artifact，仅 Spring Boot
- D. 混合方案：体验最好但开发成本最高

**方案 A 核心设计**：
- `ProjectTypeDetector`：检测项目类型（Spring Boot / MyBatis 原生），定位源码目录和主包名
- `SqlSourceInterceptorAutoInjector`：幂等注入核心逻辑（检查已存在则跳过）
- `SourceInterceptorTemplates`：拦截器类 + 配置类代码模板
- 设置页新增开关 `autoInjectSqlSourceInterceptor`

**开发计划**：`docs/开发计划/20260902-1710-方案A自动生成SqlSourceInterceptor配置类-开发计划.md`

**实施完成**（2026-09-02，全部编译通过）：
- 新增 3 个 Kotlin 文件：`ProjectTypeDetector.kt`、`SourceInterceptorTemplates.kt`、`SqlSourceInterceptorAutoInjector.kt`
- 修改 `PluginSettings.kt`（新增 `autoInjectSqlSourceInterceptor` 设置项）
- 修改 `PluginSettingsPanel.kt`（新增 UI 复选框 + `triggerInterceptorInjection()` + isModified/apply/reset 集成）
- 修改中英 i18n bundle（新增 3 条文案）
- 旧的 EntitySearchService 方案已废弃（被用户否定）

**实施中修复的编译/运行陷阱**：
1. `GlobalSearchScope.projectScope` 不含依赖库 → `findClass` 必须用 `allScope`（Spring Boot/MyBatis 类在 jar 中），`AnnotatedElementsSearch` 保持 `projectScope`
2. 生成的 Java 代码不能用 Kotlin 扩展方法（如 `substringBeforeLast`）→ 需自定义纯 Java 静态方法
3. 生成的 Java 拦截器方法不加 `@Override` → 避免 MP 版本差异导致方法签名不匹配；`ResultHandler<?>` 改为 `ResultHandler`
4. **bean 名称冲突**：不要新建 `MybatisPlusInterceptor` bean（用户项目已有），改为只定义 `SqlSourceInterceptor` bean + `SmartInitializingSingleton` 注入已有的 `MybatisPlusInterceptor`
5. **初始化顺序**：用 `SmartInitializingSingleton`（非 `@PostConstruct`）确保用户 bean 全部初始化完成后再注册拦截器
6. **拦截器捕获到自己的位置**：`locateSource()` 需添加 `isSelf()` 检查跳过拦截器自身帧
7. **路径解析失败**：标记路径（如 `cn/example/Service.java`）相对于源码根目录，原 `resolveEntryJump` 用 `project.basePath` 拼接找不到文件 → 新增 `findSourceFile()` 在所有源码根目录（`src/main/java` 等）中搜索
8. **@Primary + ObjectProvider 循环依赖**：不用 `@Primary`，改为 `getBeansOfType()` 搜索所有 bean，无则 `registerSingleton` 创建
9. **COUNT 查询无来源标记**：MP `PaginationInnerInterceptor` 执行的 COUNT 查询不经过本拦截器 → 使用**方案 B**（标注"MP 分页计数"）。新增 `SourceMarkers.MP_PAGINATION_COUNT = "__MP_PAGINATION_COUNT__"` 特殊标记。当控制台桥检测到 SQL 无 pendingSource 且为 `SELECT COUNT(*)` 查询时，使用特殊标记。UI 层检测到标记后显示"MP 分页计数"而非文件路径。**方案 C（按线程关联）被用户否定**：COUNT 由 MP 内部触发，按线程回溯来源会定位到错误的代码行（如 line 35 而非实际触发处 line 40）
10. **来源显示过长（Windows）**：`HistoryCellRenderer` 用 `File.separatorChar`（Windows `\`）但标记路径始终用 `/` → `substringAfterLast('\\')` 找不到分隔符返回全路径。修复：改用 `substringAfterLast('/')` 始终正确提取类名
11. **MP 分页 COUNT 查询不在列表显示文件路径**：`resolveEntryJump` 检测到 `SourceMarkers.isMpPaginationCount()` 标记时返回 `LogJumpTarget.NoSource`（不尝试跳转），右键菜单提示"无源码位置"
12. **实时日志列表新增来源列**：`LogEntryListCellRenderer` 新增 `formatSourceHtml()` 在 SQL 摘要前显示来源（灰色 `[类名:行号]` 或 `[MP 分页计数]`），选中态白色

**Why:** 事后搜索 Wrapper 本质不可靠，必须在 SQL 执行时通过 InnerInterceptor 捕获源码位置。
**How to apply:** 用户只需在设置页开启开关，插件自动生成配置类到项目源码目录，重启应用后实时日志携带源码位置。相关：[[project-f44-reliable-jump-and-test-coverage]] [[pitfall-java-code-gen-no-kotlin-ext]]