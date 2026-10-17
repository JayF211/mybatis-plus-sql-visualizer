# dev 合流后全功能沙盒验收记录

## 本次授权与执行方式

用户要求先将当前 master 合入 dev，再用 dev 沙盒完成新增及历史未验功能的 UI 验收，IDEA 全屏并置顶。用户随后明确允许：本批持续验收时，将已记录问题交独立工作树子代理修复；批末统一合入、重启沙盒、复验并进入下一批。主控亲自操作 GUI，子代理仅负责代码及定向验证，不并发操作桌面。

本次授权取代上一轮“禁止启动沙盒UI”的限制；它不代表允许修改业务数据库、真实公开发布诊断内容或替用户登录第三方账户。数据库验证使用本地合成环境及已允许的 DBX 开发测试连接。真实外发保持逐次内容授权。

## 冻结与环境

- 合并前 dev：`66f8645e2b4d88964106da1a6cd1bdd60dc7a6ec`。
- 当前合入 master：`d7cdfb8f`。主工作树的未跟踪 JDK8/Redis 调查计划不纳入。
- 合并提交：`be3ff18b`，父提交 `66f8645e`、`d7cdfb8f`；当前沙盒为该提交构建，实际插件文件哈希待记录。
- 启动前编译：243配置下 `compileKotlin compileTestKotlin -Pkotlin.incremental=false --no-configuration-cache` 成功，2026-10-16 17:54:44退出0，3m4s；尚未执行本次定向/全量测试，不能记为UI通过。
- 目标平台：默认 IU 2024.3.7.1（243）；不使用 gifRecord 切换262。
- 独立沙盒容器：`build/MPSV-MasterMerge-UI243`，通过 `.local/master-merge-ui243.init.gradle` 配置并关闭runIde自动重载，防止后台修复构建改变本批在验版本；实际子目录、PID、全屏/置顶证据待启动后记录。
- 演示工程：`F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo`。启动前工程干净，8081～8088无监听；本地MySQL已实测8.0.12可连接。
- 旧统一门禁7787/0/0/10不覆盖本次合流，旧UI结果只作准备参考。

## 覆盖清单

以下每项按真实操作补结果，未执行不得预填通过。视觉基准243 Light/Dark × IDE缩放100%/125%；日志页再交叉900/460/250/230px，达不到的宽度记真实最小值。一次性提示、重启持久化与跨项目状态另测，不由单次截图代替。

| ID | 功能与必须覆盖的子场景 | 主要操作与判别点 | 状态/证据/缺陷 |
|---|---|---|---|
| U01 | O146/V179原生列表高亮 | 日志/历史/预览同源配色，三显示模式、选中/失焦/搜索/CRUD色；长句省略但复制完整；200条滚动 | 部分：已验B3 Light125%历史七条列表关键字蓝、标识符紫、耗时绿及同结构徽章；待三处同源配色、三显示模式、选中/失焦/搜索/CRUD色、长句完整复制、200条滚动及其余视觉矩阵 |
| U02 | O148/V181尾随与排序 | 滚轮/拖条/导航键各自暂停，手动回底不恢复；显式滚底恢复、清空重启；满200继续追加；七排序与慢筛选 | 部分：已验B2清空5条后继续捕获新2条；待清空后的尾随状态、三类暂停、手动回底不恢复、显式恢复、满200追加、七排序及慢筛选 |
| U03 | O149/V182四页单行 | 第四页收窄/扩宽，MorePopup鼠标/键盘可达，空历史常驻 | 部分：已验B2约300物理像素窄栏四页单行、MorePopup列出历史且Enter切入；待扩宽往返、鼠标选入、空历史常驻及精确宽度/主题缩放矩阵 |
| U04 | 快速预览与首次引导 | 单击选择/双击完整、多选/空白；编辑钉住、恢复跟随；历史换文件迟到防回灌；首次引导跨页/重启 | 部分：已验B1单击选择及就地提示、提示打开底部预览、双击完整预览、编辑草稿钉住和恢复跟随；待多选/空白、历史切文件迟到防回灌、引导跨页/重启 |
| U05 | 工具栏/交流群/帮助 | 11动作宽窄可达、帮助收尾、搜索快捷键；二维码异步成功/失败/关闭；本地帮助 | 部分：已验UI-003暗色宽栏清除图标及清空后继续捕获、约300像素窄栏图标、Light125%图标；待11动作完整宽窄可达、帮助收尾/快捷键、二维码三态、本地帮助及其余视觉矩阵 |
| U06 | R53双通道状态与暂停 | 捕捉启停、增强关开、暂停只停agent入队、控制台照常、恢复不重放；状态栏显隐 | 部分：已验B1真实agent捕获、暂停后两条控制台记录无源码/耗时、恢复后两条带源码/耗时且暂停记录未补放；待捕捉停止/重启、增强开关与暂停分别核验、状态栏显隐 |
| U07 | O147/V180自然接管融合 | 真实同次控制台/agent一行融合与源码、耗时、历史回读；无源码轻提示；不同执行不强行合并 | 待验 |
| U08 | R60各出口脱敏 | 列表/历史/底部/完整预览/复制/导出；采集和展示开关独立；合成敏感值不漏、原文件不擦写 | 待验 |
| U09 | 调用栈与最近执行 | 深栈两端、内部折叠、摘要点击/悬停、逐帧跳转、复制全文；最近执行多选/编辑/完整预览 | 部分：已验B1完整预览栈弹层内部折叠、省略24帧提示和业务帧可见，列表跳Java定位到58行；待深栈两端、摘要可读性/悬停、逐帧跳转/复制全文及最近执行专项 |
| U10 | Kotlin主动入口降级 | 构造行/选择区给限制说明、无假gutter；普通行不误报、Java正对照 | 部分：已验B3 Kotlin ktQuery链主动入口给限制说明且无插件gutter，普通声明行提示未找到SQL源，B1 Java Wrapper可预览；待构造行与选择区专项 |
| U11 | 三类持久数据清理 | 逐项取消/确认；捕获目录不擅删；历史/收藏/参数/方案边界；旧面板/迟到写回不复活；重启 | 部分：已验B3末两类清理确认框明示范围和互不删除边界并选择No；待三类逐项确认/取消完整覆盖、实际删除边界、捕获目录保护、旧面板/迟到写回及重启 |
| U12 | O145/V178来源方言 | 注解/XML/Wrapper/JPA/HQL、目录继承/文件覆盖、模块/定义owner；显式scope无法解析为UNAVAILABLE，旧日志不猜URL | 部分：已验B1 Wrapper与B2 XML真实入口生成SQL、B2不同宿主嵌入模型输出未串兄弟模型；待来源方言专项、注解/JPA/HQL全入口、目录/文件覆盖、模块/定义owner、UNAVAILABLE及旧日志不猜URL |
| U13 | UUID学习/注入/刷新 | 实际SqlFile、执行后学习、重命名/改测试URL/删除重建、清绑定、跨项目、迟到预热 | 待验 |
| U14 | session与active双闸 | 两路径分别验证；手动冲突阻断、自动重渲染零提交后二击、已编辑不覆盖；未知QUERY确认取消/未知写拒绝 | 部分：已验B1同源session创建及native query SUBMITTED，已绑定控制台再次执行得37行；待active独立路径、手动冲突、自动重渲染零提交后二击、编辑保护及未知QUERY/写负例 |
| U15 | R42/快捷模板/旁路 | EXPLAIN/COUNT/抽样、日志转SQL、影响面；长WHERE保末尾条件正例1/负例3，无WHERE/多表/注入负例 | 部分：已验B1预览EXPLAIN真实返回MySQL表格、B2日志转SQL双行还原；待快捷模板/旁路专项、COUNT/抽样/影响面、长WHERE正反例及无WHERE/多表/注入负例 |
| U16 | 富错误与超时披露 | 预览/模板三动作/日志转SQL：缺表/语法错/失效连接，原文/状态/厂商码/位置复制；有无源定位；原生超时边界 | 部分：已验B1预览错列42S22/1054及库/数据源披露、完整复制错误、定位选中失败语句；待缺表/语法错/失效连接、模板三动作/日志入口、有无源定位与原生超时边界 |
| U17 | 五条金路径运行对照 | Wrapper分页、XML动态、JPA/HQL、捕获源码历史、执行EXPLAIN；同次输入/实际SQL/目标/结果，不跨样本拼证 | 部分：已验B1 Wrapper填参及同面板真实执行/EXPLAIN、B2 XML动态条件预览；待五条金路径逐条同次输入/实际SQL/目标/结果对照，现有分散记录不拼为整条金路径 |
| U18 | R90计划历史 | 默认关闭/Apply/Reset、事前和回调授权；两次计划/重启/只读基线；项目UUID参数隔离；清理迟到；数量/字节界限 | 部分：已验B1启用独立留存、两次结构化计划与只读历史基线，B2跨IDE重启读回，UI-001披露可读及UI-002中文标签复验；待默认关闭/Apply/Reset完整流程、事前/回调授权负例、项目UUID参数隔离、清理迟到与数量/字节界限 |
| U19 | R91上下文v2 | 有无计划、编辑/换目标/清历史；匿名值标识符注释、白名单计划、16KiB；复制零新查询/网络 | 部分：已验B1有/无计划复制、值掩码/标识符隐藏及已获计划exact match摘要；待编辑/换目标/清历史失效、注释与计划白名单负例、16KiB及本轮零新查询/网络活探针 |
| U20 | R92/48填充模拟 | CRUD和批量、四fill型、AUTO/Version、WHERE/UPSERT排除、显式优先、恶意输入、两预设/清理/换目标 | 部分：已验AUTO主键默认不勾、模拟默认关闭，B3布局/普通值优先/分号拒绝、INSERT与UPDATE字段差异、引号转义/数值拒绝、WHERE id/revision与UPSERT不填、取消列/关闭模拟；待CRUD与批量全形态、四fill型逐项/Version完整边界、两预设/清理/换目标，未执行写SQL或Handler |
| U21 | R93/52能力单源 | meta悬停、报告、执行确认跨方言；不支持能力明确、字面量关键字不误报 | 待验 |
| U22 | R94诊断审阅 | 第七出口与旧六出口、删段编辑撤同意、超限、取消、最终正文；实际公网发送不擅执行 | 部分：已验B1七出口可见、第七审阅公开目的地/无附件、取消SQL段、编辑撤同意、22520/16384超限显示及取消未发送；待旧六出口实际行为、超限提交拦截与最终正文核对，实际公网发送须另获授权 |
| U23 | R22参数预设全边界 | A/B语句同名隔离、默认/P1/P2快照、重命名/删除/20上限、折叠与IDE重启、foreach/OGNL | 部分：已验B1默认项禁重命名/删除、UI-P1保存与参数/条件快照往返、清除输入不删方案，B2重启普通参数召回且预设栏收起；待A/B同名隔离、P2、命名项重命名/删除/20上限、命名快照重启与foreach/OGNL |
| U24 | R51/54历史参数耗时 | 缓存优先、历史快照兜底、不串条目；精确耗时优先/同结构中位数；模拟键不回落 | 待验 |
| U25 | R33/23/45/61执行计划 | 原生/结构化切换、MySQL表格/PG树、无源/未填参/未知格式回退、两次diff、体检、文本不可比 | 部分：已验B1 MySQL原生表格、启用结构化后无绑定回原生/绑定后结构化、两次无变化比较与只读历史，B2中文基线标签；待主动原生/结构化切换、PG树、无源/未填参/未知格式回退、有变化diff、体检与文本不可比 |
| U26 | R46 Schema预检 | 已预热错列建议；未预热/复杂语句跳过；DML取消不写、只读提示 | 待验 |
| U27 | R49/30/12条件分页 | Wrapper/EasyQuery布尔三态、XML未知警示；COUNT/LIMIT改参保留、COUNT独立开关、手写LIMIT不重复 | 部分：已验B1 Wrapper动态条件缺省标记及填参，B2 XML真/假标记、取消status移除条件、全不选后清空WHERE/参数；待Wrapper/EasyQuery三态全覆盖、XML未知警示、COUNT/LIMIT改参保留及两类开关/去重边界 |
| U28 | R36/O91/R4导出 | 回放事务/分号/块、可移植CRUD/回滚、日志历史四格式、过滤/选择集及脱敏 | 部分：已验B1 MySQL单SELECT回放文件含START TRANSACTION/实值/末尾分号/COMMIT，未执行；待多语句/块及不支持事务方言、可移植CRUD/回滚、日志历史四格式、过滤/选择集与脱敏 |
| U29 | R31/R16/O90检索对比 | 1/2/3条Diff与超千行、正则/Aa/非法regex/文件名行号、同结构计数/聚焦取消 | 部分：已验B2历史原文搜索/合法正则与双选Diff，B1同结构计数5→7→9；B3非法正则误报空文件已复现UI-009；待修复GUI复验、1/3条及超千行Diff、Aa/文件名行号、聚焦取消 |
| U30 | O92/DDL/R3日志还原 | 剪贴板有/空且只点击读取、日期、DDL不伪回填、格式化引号注释保真 | 部分：已验B2真实双行Preparing/Parameters还原id=6及username字符串、错拼一行时未回填告警；待剪贴板有/空与点击读取时机、日期、DDL边界、格式化引号/注释保真 |
| U31 | R10 ShardingSphere | 真实Logic/Actual双段、交错/含逗号/关开；普通日志三族回归，样例解析不代替真应用 | 部分：已验B3真5.5.2框架三条Logic/四条Actual及PASS、增强四条物理SQL、暂停增强后控制台逻辑SQL；参数未还原/来源提示错误/路由附注缺失已记UI-008；待B4修复包真框架复验、交错/含逗号/开关矩阵及普通日志三族回归 |
| U32 | R2/XML编辑辅助 | 六组gutter逐组关开；高亮#/$、闭合、参数/列/resultMap补全、引用导航/查找/折叠；第三方让位 | 待验 |
| U33 | R24原生MyBatis注入 | 单config成功；无config/多config/非法plugins不注入；Boot回归，专属测试副本 | 待验 |
| U34 | premain/attach关开 | 先建池再attach；两来源分别两轮关开；PS/batch/clearBatch；裸?边界及启动无异常 | 待验 |
| U35 | O69屏蔽规则 | 精确/结构/全部结构、空白大小写、存量和新入队、取消清空、双项目/重启/10次开合 | 待验 |
| U36 | 历史全量与万条UI | 首进/搜索/切文件/刷新/迟到/滚动；列表清除不删已落盘；保留3/0/非法值只用测试目录 | 部分：已验B2独立目录保存7条且列表清空未抹去记录、手动刷新/搜索，B3修复后首切显示既有文件并可见两文件；待UI-004空目录扫描→新文件→首切原路径、万条/迟到/滚动、切文件完整流程及保留3/0/非法值 |
| U37 | O64/O73回滚与注入范围 | identity ON/INSERT/OFF整批，普通多语句对照；test/bootRun/直接Run注入范围；真实写另核授权 | 待验 |
| U38 | SQL转Java完整形态 | GROUP/HAVING/DISTINCT/聚合/INSERT NULL/UNION/双引号、五Wrapper、MPJ/VO、多实体、往返 | 部分：已验B2 INSERT NULL/DISTINCT/UNION静态输出，B3 Query及Lambda/AUTO聚合回落、双引号类型名修复；UI-005合流相关两类68项全绿，UI-010 B4旧请求类18项1失败处理中；待竞态修复/GUI复验、五Wrapper/MPJ/VO/多实体/往返及生成代码运行对照 |
| U39 | MCP11工具与禁用直调 | 243缺MCP降级先验；有能力平台另验list/call/TOOL_DISABLED/路径/限额/脱敏/审计，不以243代证 | 部分：已验B3 243平台设置入口给2025.2+/MCP要求，顶部就绪误报记UI-011；B4 McpToolsetShellFactory 5项1失败处理中；待修复后243文案GUI复验及有能力平台11工具list/call/禁用直调/路径/限额/脱敏/审计 |
| U40 | R50/R62可发现性 | 旧诊断Markdown/HTML与R94区别；更新正常404断网；空态三径、绑定三态、立即试用重启、Ctrl+A | 待验 |
| U41 | 预热恢复与生命周期 | 冷预览、降级自然恢复/编辑/重试/关闭；attach-detach、心跳空闲/重连/PID消亡/关非主控制台页 | 待验 |
| U42 | 本次master新增实体作用域 | 依赖JAR嵌入前缀、代码式/自定义策略、同名实体跨模块/库、根实体/副表、类型化命名查询歧义 | 部分：已验B2源码模块拼接/StringBuffer/helper三形态嵌入前缀及切宿主模型未串兄弟模型；待真正依赖JAR嵌入前缀、代码式/自定义策略全边界、同名跨模块/库、根实体/副表及类型化命名查询歧义 |
| U43 | 库实体反查性能与入口 | 快路径/非常规@TableName、后台扫描开关、显式在依赖库查找/保留已选、取消/连续输入不卡 | 部分：已验B3独立宿主LegacyBooking来自本地Maven JAR且真实调用正向解析表/列/常量；待表名反查快路径/非常规@TableName、后台扫描开关、显式依赖库查找/保留已选、取消/连续输入性能，正向解析不代证反查 |
| U44 | 本次master预热死锁与耗时色 | 冷启预览无WIL自锁、准备超时可恢复；正常绿/半阈值橙/慢红/未知灰/选中对比 | 部分：已验B3 Light125%历史列表耗时绿色显示；待正常阈值对应关系、半阈值橙/慢红/未知灰/选中对比及冷启WIL自锁/准备超时恢复专项 |

## 每项证据格式

`ID/子项｜批次冻结SHA/插件哈希｜IDE build/主题/缩放/宽度｜样本参数与目标代号｜实际操作｜结果｜正反例是否触达｜截图或会话图像/日志/SQL证据｜通过/失败/受限/待验｜缺陷号｜修复SHA｜复验结果`

未执行、入口缺失、真实provider没有返回、自然融合事件未出现时分别记录，不能合并写成通过。243无MCP平台能力不代表该工具已验收；需要另一个支持版本验证时单独记录版本。

## 第一批实际操作记录

- 环境：243沙盒JBR进程PID54712；初次信任仅演示工程，增强捕获确认框已展示采集范围/本机机制/取消冷却说明，点击同意。主窗口通过原生Enter Full Screen切换，frame-068c1c00证实1920×1080全屏；窗口ID由4591970变为4657506。系统级置顶尚未完成；动作搜索仅有Folders Always on Top（文件夹排序），不能冒充窗口置顶。
- CUA取证：Swing弹层有独立窗口，主窗口截图不包含搜索/下拉浮层；必须list_windows定位后绑定实际窗口。UIA只显示标题栏时按截图操作，工具“unchanged”仅指稀疏结构，不等于画面无变化。菜单不得因主窗口截图缺席就判产品失败。
- U17/U27局部：`mybatis-plus-demo/UserServiceImpl.java` 36行gutter预览成功，显示三项动态条件与`恒真·缺省`。输入username=`ui_alpha`、status=1、age=18，SQL即时回填且逻辑删除保持，frame-4ae4ef7c。行首空白处快捷键曾给“未找到源”，暂不定缺陷，后续在表达式内做对照。
- U23局部：管理预设默认项重命名/删除禁用；另存UI-P1成功。选UI-P1后改username为ui_beta、取消status，切默认得到修改后的工作值（frame-e908acd5）；再从下拉选择UI-P1，恢复ui_alpha/status=1勾选/age18（frame-762e6444）。证明本次普通参数与条件快照未被编辑覆盖；跨方法/foreach/20上限/重启仍未验，不将U23整项标完成。

- U23继续：清除按钮将三个输入清空但保持条件勾选，SQL恢复问号（frame-1c18d480）；默认/UI-P1往返能恢复已存快照，命名方案未被清除按钮删除。
- U22局部：分享窗口七出口齐，默认Markdown；第七入口打开审阅表单，目的地公开需求池明确，无附件控件，初始正文618字节、值与标识符投影为问号/identifier_N（frame-3640075a）。取消SQL结构段后正文187字节（frame-96e2d961）；勾同意正对照frame-e25c2359后编辑正文，勾选撤销、预算变27（frame-045860ec）。输入最终超过预算实测22520/16384，同意保持未勾，提交按钮仍显示可点；尚未触发提交验证，不能据按钮外观判安全闸失效。最后点取消，未实际发送。大文本CUA paste曾粘入非预期剪贴板内容，改分段typeText且以界面字节数为准，工具传入长度不是实际录入证据。

- U19无计划正例：真实更多菜单复制AI上下文，再粘入本地预览编辑区读取（随后Undo恢复原SQL），frame-3bf39ae8显示`AI context v2`、值掩码4、标识符隐藏、`EXPLAIN: not obtained`、静态tables=1/columns=12。未点击执行，未对外发送；零网络/JDBC活探针仍以此前独立证据为辅，不以无弹窗代证。
- U28回放正例：GUI选择MySQL事务包装，保存 `build/ui243-replay-b1.sql`；文件实际包含START TRANSACTION、填实值ui_alpha/1/18的完整SELECT、单条末尾分号和COMMIT；未执行脚本。截图frame-c68d9bd3及本地导出文件构成实物；不支持事务方言与多语句边界仍待验。
- 当前实际插件：IU-243.28141.41，SQL Visualizer 2026.10.16；沙盒lib主JAR 6,012,505 bytes，SHA-256 `b169010e96134f757bfc368607c4e7ef70225bdaca8d9160171844e0817566d1`。启动日志未检出ERROR/SEVERE。
- 缺样准备独立demo树 `mybatis-plus-sql-visualizer-demo-ui-fixtures`：689e91a填充四型、3a74a13普通MP模型JAR、7b9806b真实ShardingSphere/H2；均已编译，后者CLI两单分片及跨分片查询真实通过。UI尚未打开该树，不冒称分片捕获已验。

- U14/U16准备：首按执行在预览底部给红色“没有与MySQL匹配的已配置数据源”，可复制完整错误（frame-abd735fe）。原生Database配置正对照明确报Driver files are not downloaded（frame-791759ea）；因此先判新沙盒缺驱动，不将这次拒绝冒充双目标闸已触达或产品缺陷。已由原生按钮下载MySQL驱动，进入Test Connection，后续结果继续记录。

- U14同源正例已触达真实链：补齐驱动与演示工程已有本地测试密码后Test Connection成功（MySQL8.0.12，frame-ac0e813e）。Runner弹层真实列出五个本地MySQL源，选mp_demo，日志19:03:19 `session created target=8d6c2a1b-f817-44fd-a3db-6798b116bc78/MySQL`、19:03:20 `native query SUBMITTED`；原生网格0行。取消username与age条件后再次执行，原生Result2显示37行（frame-b8ee992e），说明已绑定控制台可用；冲突/未知与重渲染负例尚未覆盖。
- U25原生EXPLAIN正例：同面板执行计划得到真实MySQL表格一行（frame-2f41afbd），尚未启用结构化及持久化，不能关闭R90子项。
- U16预览入口：手动编辑为`SELECT missing_ui_column FROM user`后执行，底部明确`[42S22][1054] Unknown column...`及当前库/数据源，旧结果保留但失败状态清楚（frame-e33c6cb2）。点击复制错误再本地粘贴，状态码与库上下文完整（frame-af1a791d）；Undo恢复错误SQL后点击定位，整条失败语句选中（frame-86d03436）。其他三个模板/日志入口尚未验。
- 日志附带发现两次原生DatabaseView.PropertiesAction的SlowOperations，已派只读归因；未证本插件责任前不擅改平台相关代码。兼容回退MySQL的Wrapper在目标MySQL时直接执行也交独立复核其契约，不把未提示误判为安全缺口。

- U18/U25：启用结构化和独立留存后，第一次无绑定回原生；已绑定后再点得到结构化MySQL计划、提示已保存（frame-2fad9ab2）；第二次出现无变化比较与历史基线（frame-499e339e），切历史项能只读重绘（frame-1ba5494c），基线文案缺陷UI-002另记。U19有计划复制实测`already acquired; exact match; type=ref; rows=1; filteredPercent=5.0`（frame-9c51ccd3），没有原始NOTICE条件；重启和隔离负例仍待验。
- U04/U09：5条真实agent日志单击只选中并出一次性就地提示（frame-17f6a30a），从提示打开底部预览；编辑`SELECT 42 AS ui_draft`后选第二条仍保草稿（frame-99f47ca8），恢复后跟随第二条id=2（frame-7753cff7）。双击完整预览有参数2和栈摘要；栈弹层可见内部折叠、采集省略24帧不可展开与业务n1行（frame-c0347c60）。列表右键跳Java实际定位LogFeederController.java:58（frame-a07cd13b）。摘要仍先显示agent内部帧，列后续可读性复核，不把弹层业务可见当摘要也合格。
- U06真实暂停/恢复：IDE Run启动MybatisPlusDemoApplication，PID24408、8081；捕捉后请求ids1~5得到5条带源码/耗时记录。暂停增强后ids6/7仍经控制台入列表，无耗时/源码（frame-c88ccc5d）；恢复后ids8/9新增两条带源码/耗时，总数9、旧暂停两条未补放重复（frame-4ab3cd15）。同结构徽章同步5→7→9；自然跨通道同次MERGED与持久历史仍待验。

## 缺陷与批次

缺陷按实际发现批次登记；下表保留首次状态，最新实施与复验以各批次记录为准。合并冲突属于准备工作，不冒充UI发现。

代码预审线索（未归为UI缺陷）：合并复核指出 `XmlInlineSqlPreviewService` 的HBM入口可能未传tag/context且runCatching可能吞取消；列入U12/U42同名模块和定义owner专项，先复核实际可达路径与用户结果再安排修复，不将源码推断当作已复现。

| 缺陷 | 发现批次/用例 | 复现/实际与预期 | 独立修复分支/提交 | 定向验证 | 沙盒复验 |
|---|---|---|---|---|---|
| UI-001 | B1/U18、U40 | Settings默认998×742控件右移，开关文案与R90披露被截；frame-43bc71fb/766d6787 | t-devui-settings-layout：b0433976→dev 38a595cf | 独立27/0/0/0；撤首列过滤2/2红、恢复27绿；B2十类合流116/0/0/0 | B2中文998×742布局/披露/下拉复验通过；B3 Light125%原生1247×925顶部与末尾可读；英文及其余视觉矩阵待验 |

| UI-002 | B1/U18、U25 | 基线显示内部R61、历史原UTC时间+UUID，不易区分会话与快照；frame-499e339e/40716065 | t-devui-plan-baseline：8fd89abc→dev 3f55c74a；独立证据09c8c978 | 两类18/0/0/0；临时R61标签4/4红、恢复18绿；纳入B2十类116/0/0/0 | B2中文本地时间/快照来源及“上次执行（当前会话）”复验通过，跨IDE重启读回；不代证R90隔离/清理等其余子项 |

| UI-003 | B1/U05；用户现场指出 | ClearAction已注册但未配图标，宽栏第三动作占位；frame-9baa1a8e | 原12c01f4d→dev 44ee9d11 | 定向9/0/0/0；摘图标三例2红1绿、恢复9绿；纳入B2十类116/0/0/0 | B2暗色宽栏空/有数据图标可见，清5条后继续捕获2条；约300像素窄栏及B3 Light125%图标可见；其余主题/缩放/精确宽度待验 |

| UI-004 | B2/U36 | 设置时空扫后首切历史不刷新，手动刷新才显示7条；JBTabs old/new反置且首扫兜底已消耗 | t-devui-history-refresh：9e32ad43→dev e6f3de57 | B3首跑历史两类19项全绿；五类最终98/0/0/0；恢复错误回调顺序与移除填充高度失效联合三例全红，恢复后98绿 | B3首切可显示既有7条文件，Light125%可列两文件；空目录扫描→写入新文件→首次切入原复现路径仍待专项 |
| UI-005 | B2/U38 | GROUP/HAVING聚合AUTO生成不可编译Lambda、Query连续select覆盖投影；双引号表名又污染Java类型名 | t-devui-reverse-projection→dev f9fd950d/ad91a474/c39a5c4d；夹具后备续修8aeb0b50 | 独立MP3.5.7真实编译及SQL片段两类68/0，基线5红；合流首跑68项6失败为夹具CodeSource问题，续修后五类98/0/0/0 | B3 Query单select、双引号类型/列边界及Lambda/AUTO回落QueryWrapper<User>同样本复验通过；不代证所有生成形态；旧异步回灌另记UI-010 |
| UI-006 | B2/U20 | 参数区开关右移、模拟输入在横向视口外；frame-307e73ef/219fc32e；初修后安全说明高度仍不足 | t-devui-fill-layout：97a6b6b4→dev 02201dbc；高度缓存续修纳入B3 8aeb0b50运行包 | B3首跑FieldFill 11项2失败，续修11绿；与历史回调联合反向三例全红，恢复五类98/0/0/0 | B3开关/说明左对齐、两组输入纵排无需横滚，显式优先及非法值字段提示完整；完整宽度/主题缩放矩阵待验 |
| UI-007 | B3归因；U37/U41相关 | B2日志证实非主配置仍attach RemoteJdbcServer并获CONFIG ACK；授权不涵盖平台helper，未证明与退出等待有因果关系 | dev e50e2b4c、867ff37d；独立签名补交6f756735 | 独立六类69绿/撤拒绝14项9红/恢复69绿；B4十五类共233项、2失败、0错误、0跳过，13类全绿；失败分别为UI-010与UI-011，整批未通过 | 第三批包未纳入；待新包真实helper负例与正常应用正对照，不以第三批正常退出代证过滤修复 |
| UI-008 | B3/U31 | 真5.5.2 Actual无Parameters前缀，参数未恢复、误提示Hibernate且路由附注未见；物理参数不可安全映射Logic | dev abf4d745、5f2c614c、64295b4a、6a7c90b6、8bf18589；最终保守问号与只读路由，不自动回填无类型Actual参数 | 纳入B4十五类233/2失败/0错误/0跳过，失败归属UI-010/UI-011；本项新增测试所属类全绿不代证真实GUI；本轮反向尚待收口 | 待真框架修复包复验、只读路由与脱敏开关/已开面板响应；旧框架PASS不等于插件还原通过 |
| UI-009 | B3/U29 | 历史正则输入非法左括号误报“该文件暂无SQL条目”，合法正则立即恢复2条；frame-178d73d2/d5d54289 | dca7b369→dev 4ead7c50 | 纳入B4十五类233/2失败/0错误/0跳过，失败归属UI-010/UI-011；本项所属类全绿；反向尚待收口 | 待非法正则错误反馈、无匹配空态与原生开关观察的修复包GUI复验 |
| UI-010 | B3/U38 | 切AUTO发旧ui_probe查询后输入换user，旧候选弹层仍出现且取消后旧输出残留；frame-84fa67aa | dev 659ac9b8、b0935599 | B4 SqlToJavaPanelStaleRequest 18项、1失败；失败正处理，未通过，不以再次转换正确代证竞态修复 | 待定向失败修复、反向闭环及旧请求候选/输出不回灌GUI复验 |
| UI-011 | B3/U39 | 243页顶称11工具已注册可调用，平台设置入口却提示需2025.2+及MCP Server；就绪文案矛盾，导航降级正常 | dev b02dda7f | B4 McpToolsetShellFactory 5项、1失败；失败正处理，未通过；243/253/262签名登记不代证平台实际调用 | 待定向失败修复与243能力/开关文案GUI复验；有能力平台11工具list/call等另验 |

## 第二批修复合流与复验

首批沙盒通过原生Exit后，运行中演示应用Terminate卡在Waiting for process detach；90秒有界等待结束仍在运行，已先保存JDK8线程现场，再按IDE原生Kill process结束本次应用。归属核实为PID24408、父54712、命令行含本次MPSV-MasterMerge-UI243 agent路径；没有操作其他进程。随后runIde任务退出0。线程现场见会话日志call_BS521Rpri31WTvUICa2mT7yM-stdout.log，归因未定，不预判插件关闭死锁。原始界面保持be3ff18b直到本批结束。

- UI-001：原修复b0433976→dev `38a595cf`，独立定向27/0/0/0，撤首列过滤2/2红、恢复27绿。
- UI-002：原修复8fd89abc→dev `3f55c74a`；此前并非中断，而是主动留待测试。用户要求检查后已恢复原代理补验：两类18/0/0/0，临时R61标签4/4红，恢复18绿；证据记录09c8c978留在独立分支。
- UI-003：原修复12c01f4d→dev `44ee9d11`，定向9/0/0/0，摘图标三例2红1绿、恢复9绿，清空/尾随业务未改。
- 三个源码修复均已合入，未合入代理的旧整份验收文档以免覆盖主控现场账。合流后定向回归在后台运行，通过后以同一独立配置启动第二批；尚不能写UI复验通过。
- 子代理核验：四个修复/夹具工作树均干净，有提交和实际证据；没有发现掉线遗留的未提交成果。只有UI-002缺执行证据，已补齐。

- UI-004已证根因：JBTabs真实243回调参数是oldSelection/newSelection，SqlLogPanel将第一参当current，导致进入不刷、离开反刷；设置时0文件扫描提前置首扫标记，showing兜底不再补。独立分支t-devui-history-refresh已提交9e32ad43，去除旧测试人工showing补刷的假绿并补真实tabs.select回归；因另一会话T-SqlInsight-Runtime占用，定向/反向未跑，批末补验，不先勾通过。

### 第二批实际结果

运行包dev@44ee9d11，十类合流定向116/0/0/0，重新启动PID36912并自动恢复1920×1080全屏。系统级置顶仍未证明。

- UI-001中文默认窗口复验通过：998×742设置页控件正常靠左、无横向截断，R90三行隐私全文可见（frame-879d2246），代码生成下拉含五选项且末项换行不截断（frame-01c745b6）。英文/缩放矩阵待后续复验。
- UI-002中文复验通过：重启后读取上一批19:21:05历史计划，显示本地时间/快照来源（frame-4e984f16）；再执行显示“上次执行（当前会话）”（frame-a8a53746），不再显示R61。R90跨IDE重启正例与普通参数ui_alpha/1/18召回同时通过；预设栏重启默认收起（frame-376e94b9）。
- UI-003宽栏暗色复验通过：第三动作垃圾桶图标在空/有数据时可见，点击后5条日志清空（frame-93892d50），再请求ids6/7得到新2条（frame-a84ebfed），捕获未停止。
- U36开启自动保存并设独立build/ui243-history后，已清空的5条及新2条都在同一历史文件中，共7条；不操作已有历史目录。首次进入历史未自动刷新、手动刷新出现文件，登记UI-004待根因核实。历史内原始SQL搜id=6得1条；带空格id = 6为0，属于原文匹配口径；正则id=6|id=7得2条（frame-c6d4e151）。双选原生Diff打开并显示1 difference（frame-265fd990）。

- U03/U05窄栏：将日志工具窗缩至当前约300物理像素，四页仍单行，历史被收进平台MorePopup；菜单有SQL历史（frame-b48d78e2），键盘Enter切入后历史成为当前页（frame-840b1226）。清除垃圾桶仍在前三动作可见。精确230/250/460及主题缩放组合仍待补，不用此一格覆盖全矩阵。
- U30日志转SQL：真实分两行输入Preparing/Parameters，生成id=6与username='ui_test'，状态“已转换1条SQL”（frame-dd8613e9）。首轮typeText中的换行未落地，错拼成一行时正确显示未回填告警；明确属输入工具问题，不归产品缺陷。
- U38六形态首轮：聚合/分组/HAVING发现UI-005；INSERT NULL生成setName(null)（frame-0d0ab9ea）；DISTINCT与LIMIT5/FOR UPDATE单last同时保留（frame-16c16ee4）；UNION明确注释需手动处理（frame-604c22fa）。双引号另现fallback实体类名`QueryWrapper<"UiProbe">`不合法，已交同修复代理一并处理，列引号应保留、Java类型名不应携SQL引号（frame-a72d62c5）。这些仅静态GUI输出，不称生成Java已运行。

- U20/CRUD：实体User生成候选六项正确，INSERT主键AUTO未勾、fill安全模拟默认关闭；发现UI-006参数区BoxLayout/FlowLayout导致开关右移、模拟输入在横向视口外（frame-307e73ef/219fc32e）。独立t-devui-fill-layout已交97a6b6b4修复与真实350px回归，未编译/测试，待批末验证和GUI复验；不因输入尚不可见就声称R92全部通过。
- U12/U27 XML真实入口：UserMapper.xml selectUserListByXml有动态条件真/假标记，输入admin/1/18后LIKE concat和年龄实值正常，取消status后SQL移除该条件（frame-fb97e027）；全不选后清除输入，WHERE清除且参数空（frame-4848d756）。
- U42嵌入/模块作用域：源码模块拼接、StringBuffer、helper返回三形态均生成wms_inventory_embed_demo及move_tool_pallet_no/qty_base_qty（frame-0a0b1d19/f2076a37/77df39c4）；切jpa-jar-model-demo宿主生成wms_inventory_jar_demo、sku_inventory_status、item_key_lot_info_received_date，未串兄弟模型（frame-b613912f）。当前聚合工程仍有模型模块，不能冒称实体来自反编译JAR，该子项另验。

## 第三批准备

第二批源码保持44ee9d11至GUI退出；新修复已合dev：UI-004 e6f3de57；UI-005 f9fd950d/ad91a474/c39a5c4d；UI-006 02201dbc。UI-005独立真实MP3.5.7编译生成代码及getSqlSelect/getSqlSegment，两类68/0，基线生成器68项5红；不冒充Gradle门禁。UI-004/UI-006此前未跑定向，本轮合流五类后台验证，测试未终态前不标修复通过。

第二次退出再次等待process detach，已取IDE线程栈，定位为原生WaitForProcessTask等待而非插件锁。应用53644结束后IDE仍有残留运行确认，选择Disconnect才退出。另数据库驱动进程50828存在agent会话，疑似挂载范围过宽，留待独立归因；不据此断言是退出阻塞原因。

第三批五类首跑98项8失败：历史两类19项全绿；反向两类68项6失败均为编译夹具CodeSource.location在IDE PathClassLoader下null，独立CLI读数不能覆盖；FieldFill11项2失败为安全说明实际高度不足。已回交原代理续修，未放宽断言或以失败归环境而跳过。

UI-007辅助进程误挂载已证：第二批IDE日志5887–5889识别非主配置后仍排队attach50828，5919–5927实际RemoteJdbcServer输出agent retransform及CONFIG ACK。监听和补扫在授权后无身份排除，用户确认不能授权平台helper注入；独立t-devui-attach-target已派修复。前述命令行末尾MySQL Driver只是参数，真实main为com.intellij.database.remote.RemoteJdbcServer，归因已校正。未证明它导致退出卡住。

第三批续修与正反闭环：CodeSource后备须同时支持IDE自定义jar URLConnection，不只JDK JarURLConnection；主控解析实测jar:file资源并核class字节一致，提交8aeb0b50。FieldFill动态错误状态显式失效父布局缓存后11例通过。五类最终98/0/0/0；临时恢复错误回调顺序及移除高度失效，三例全红（进入刷新计数2≠3、状态高度32不足64），立即恢复并五类98/0/0/0。主控src/main与HEAD无差异，反向探针已清。第三批沙盒按8aeb0b50启动，UI007尚未纳入。

### 第三批UI已取证

第三批PID37120、主窗口全屏。UI004首次切历史自动显示既有7条文件（frame-84cbadf3），设置空扫后新文件的原始路径还需用新空目录专项复验。UI005 Query模式聚合改为单次select(status, COUNT(*) AS cnt)（frame-bfccf9eb），双引号表名生成UiProbe不带引号而列SQL引号保留（frame-8d4bd702）；AUTO实体正例仍须补。UI006默认参数区安全开关与说明左对齐完整可读（frame-66c4d714），两组fill普通/模拟输入纵排无需横滚（frame-6c636b19）；开启后日期字符串进入SQL，普通explicit优先（frame-8270532c）；非法含分号模拟恢复问号并完整显示具体字段错误（frame-10a7f8d2）。

已从GUI新窗口打开独立夹具树mybatis-plus-demo/pom.xml，保留原项目，准备四型fill、真实模型JAR和项目隔离；未从根聚合导入，避免库源码替代JAR。UI007独立六类69绿、撤拒绝14项9红、恢复69绿，尚未合入当前运行包。

- U20独立FillProbe真实mapper入口：INSERT启用后created_by=`O''Reilly`单引号加倍、operator_id=42不加引号，UPDATE专属updated_by保留?（frame-68052cbd）；数值`1 OR 1=1`被拒并显示字段提示（frame-de160f2a）。UPDATE只模拟updated_by和operator_id，INSERT专属created_by仍?，WHERE id/revision保持?（frame-29dd1662）；取消列不补回、关闭模拟恢复普通占位（frame-e91d4665）。saveOrUpdateBatch的UPSERT开启且输入模拟值仍全部?，说明明确不填（frame-c71e69a1）。本轮均未执行写SQL或用户Handler。

- U43真实JAR正例：独立宿主类搜索LegacyBooking显示Maven cn.song:mp-jar-model-lib:1.0.0及本地.m2 JAR路径（frame-73329f5f），打开触发JetBrains Decompiler条款，选择Decide later未替用户接受。由LegacyBookingCases真实调用预览生成ui_reservation_ledger、booking_code/state_code且常量BOOK,100正确（frame-8f84e19b）。已证二进制实体正向解析，后台/显式表名反查仍待验。

- U31真实分片工程已GUI导入；首跑IDE沿用JDK8导致“源17需要目标17”，不是插件缺陷。通过Project Structure选择已安装Microsoft JDK17.0.19，仅修改独立夹具工程SDK，再次IDE Run在途。该前提修正未触业务库。

- U31真框架已运行：IDE Run的ShardingH2Demo在JDK17下打印真实三条Logic/四条Actual及三组PASS（frame-9ccdd327）。增强捕获获得四条物理SQL（存量PS参数缺失告警保留）；暂停增强后both命令从真实控制台收到逻辑SQL，预览仍问号且错误提示Hibernate TRACE（frame-ccef5032）。GUI确认ShardingSphere开关已开（frame-7dd4ba1d），日志也证来源SHARDINGSPHERE，非配置问题。
- UI-008：5.5.2实际Actual后缀`::: [100, BOOK,100]`无Parameters前缀，旧解析器只认Parameters，导致参数未恢复；来源提示只区分JDBC/其他而误写Hibernate。路由actualSql已保存但预览未见附注。独立t-devui-sharding-format已派实现、需真样本反向与GUI复验，不改原始日志适应解析器。

### 第三批浅色交叉与下一批修复

- 在Appearance确认Light与125%并点OK后，原主演示窗口日志第三位清除图标可见（frame-f67bddcb）；历史首次切入列出7条/6条两个文件（frame-3c954eeb）。七条文件列表保留蓝色关键字、紫色标识符、绿色耗时与同结构徽章（frame-9c854d16）；完整预览参数1、全部操作与执行/复制/取消可见（frame-0e4c83c0）。只覆盖这几处真实画面，不能外推四主题缩放全矩阵。
- UI-009真实复现：七条历史文件打开正则开关，输入`(`，结果显示0条并称“该文件暂无SQL条目”，没有非法正则反馈（frame-178d73d2）。改为合法`id=6|id=7`立即恢复2条（frame-d5d54289），排除文件内容为空。已派独立t-devui-history-regex修复错误反馈和无匹配空态，当前运行包不变。
- UI-008已交6234a850/978ecbb3，但只读复核拦截：物理分页可能把逻辑LIMIT参数10/20改为0/30，数量一致不等于参数映射不变；无类型字符串001/true也不能猜为数值/布尔后进入restoredSql及导出。已回交采用保守问号、只读路由说明，不合入当前版本。UI-007需补BaseProcessHandler.getCommandLine跨版本签名登记，已回交。当前并发UI＋Runtime-Perf两槽占满，未追加构建。
- UI-001在Light125%、原生自动放大到1247×925设置窗口中，SQL Preview顶部与末尾全部可读，R90三行完整、Wrapper下拉不截（frame-b4f55523/5fe91628）。本轮没有人为拉大窗口；SQL Log页仍出现横向滚动条，未据此判所有内容截断。
- U39的243能力负例：MCP Tools页顶部称“11个AI工具已注册、可直接调用”（frame-e4ef69b8），点平台设置按钮却提示“需要IDEA2025.2+并启用MCP Server”（frame-b2ec93df）。导航降级正常，顶部就绪文案与实际能力矛盾，登记待修UI-011；实际11工具调用不能用243代验。
- UI-005原AUTO实体复验补齐：原聚合SQL在Lambda/AUTO两形态均回落QueryWrapper<User>，单select同时含status/COUNT(*) AS cnt，groupBy与having完整（frame-38654924/9e3c5fb6）。另外发现UI-010：先切AUTO启动旧ui_probe查询、随后替换输入user，旧异步弹层仍显示ui_probe（frame-84fa67aa），取消后旧输出残留；代码只读确认回调无请求代次/文本/形态核对，旧候选及输出可能回灌。当前新SQL再次转换正确不构成对竞态的修复。

- U10主动降级：KotlinWrapperScenarios.kt:47的ktQuery链内按Alt+Shift+Q，通知明确“暂不能可靠预览”、不生成猜测SQL，画面该链无插件gutter（frame-11171827）。构造与选择区/普通行负例仍待补。
- UI-009已提交dca7b369，UI-007平台签名登记6f756735，均待合流定向；前者未跑测试。代理报告的renderer空池疑虑已实读排除：主树初始化实际为`mutableListOf(SimpleColoredComponent())`，不是空列表，不纳入缺陷。

## 第四批合流与验证

第三批沙盒保持8aeb0b50直到正常Exit，runIde退出0，本次未出现前两轮的process detach等待。第三批累计证据已提交4f0f7a18。Kotlin普通声明行主动入口显示“未找到可预览SQL源”（frame-364d7b58）；两类清理确认分别明示删除范围与互不删除边界（frame-04643638/a744f805），均选择No，未执行持久数据删除。Light125%日志页存储披露及两个清理按钮可见（frame-4ed28edb）。搜索打开Kotlin文件时多按一次Return产生未保存首空行，已GUI移除并保存，demo工作树实测干净；该过程不是产品缺陷。

本批合流：

| 缺陷 | dev提交 | 当前验证边界 |
|---|---|---|
| UI-007辅助JVM挂载过滤 | e50e2b4c、867ff37d | 原独立69绿/撤修9红/恢复69绿；补跨版本签名未跑，真实helper负例待新包 |
| UI-008分片来源与只读路由 | abf4d745、5f2c614c、64295b4a、6a7c90b6、8bf18589 | 最终不将任何无类型Actual参数自动回填Logic；脱敏开时整附注隐藏、已开面板响应设置；全部新增测试待集中运行 |
| UI-009历史正则反馈 | 4ead7c50 | 错误配色/提示、无匹配空态、原生开关观察；定向/反向/GUI待验 |
| UI-010反向转换旧异步回灌 | 659ac9b8、b0935599 | 请求代次、输入/形态与实际写入复查；18项受控顺序测试待验 |
| UI-011 MCP就绪误报 | b02dda7f | 工具声明数、平台能力与插件开关分离；243缺能力/253与262接口签名登记；定向/GUI待验 |

合流冲突仅PlatformSignatureChecker两项登记，保留AttachTarget和McpToolset两边调用及实现；没有整取一侧。UI007携带旧验收文档的hunk未采用，以主控新账为准。当前生产冻结b0935599，十五类`:test`定向通过`.local/validate-b4.ps1`后台执行；脚本核旧IDE已退出、审查其他会话登记并限两槽，独立同名沙盒、关闭自动重载、无configuration cache。此时尚未有结果，不能把合流记为测试或GUI通过。

### 第四批定向实测进展

- 首轮233项/2失败/0错误/0跳过，生产与测试编译通过。UI010正对照误以为解析器剥双引号，期望ui_probe而实际为带引号表名；修正精确前提并增加确认前后输出哨兵（dev aac693dc）。UI011跨发行物用243 JBR21加载262 Java25字节码而假红，改每版自身JBR调用实际生产探测（dev 993262cf）。均非“环境限制跳过”。
- 脱敏与正则反向：临时摘Actual展示闸门与非法正则错误投影，25项恰5失败（历史四例、Actual秘密哨兵一例），全部为预期断言；立即恢复生产并diff确认无残留。随后的十五类恢复复跑233项仅1失败：历史24、预览29及UI010十八项全绿；唯一失败为MCP测试夹具又使用CodeSource.location而在IDE PathClassLoader下为空，已回交继续适配，不能用独立CLI成功冒充平台绿。
- UI010第一轮反向：仅令生产isCurrent恒true，18项恰17失败，唯一非过期正对照保持绿；旧弹窗、候选覆盖、批量、clear/dispose及延迟写均触达真实断言。已恢复代次闸门；第二轮只撤实际写文档前复查，单例恰1红，失败输出为旧UiProbe覆盖新结果，证明末端复查不能被上游闸门代替。两轮探针均恢复，src/main对HEAD无差异。MCP资源定位继续修成可空CodeSource＋真实class资源、目录/JAR候选字节一致性校验（dev 5d884a5e），最终十五类恢复复跑234/0/0/0（新增一项资源定位判别例），fresh XML 2026-10-17 04:27:55～04:28:55；生产冻结5d884a5e（较b0935599仅测试夹具修正），所有反向探针已恢复。真实跨发行物MCP能力和两项平台签名检查纳入本次绿，不等于三平台完整插件验证或GUI验收完成。

### 第四批真实UI复验

第四批PID23656、主窗口21169148恢复1920×1080全屏，Light125%，生产5d884a5e加文档06b89352；未完成系统置顶。UI-009原七条文件输入`(`，出现红色错误文字且列表中央同样明确非法正则（frame-25a6bf10）；关正则立即清错并显示“没有匹配的结果”（frame-e3889556）；改合法id=6|id=7再开正则得到2条（frame-97c02eb3），该原始路径复验通过。

UI-011：243页头明确“声明11个工具，不代表已注册或可调用数量”，缺平台接口提示与插件选择分行（frame-fe2e36f0）；关总开关即时更新、不谎称服务器启停（frame-e15e2dc7）；关单工具显示停用1项，Revert changes恢复全部勾选（frame-eff2e5c4/7ecbccec）。未Apply该临时改动，也未实际启动MCP服务器；243中文原误报路径复验通过，有能力平台实调仍待验。

UI-007真机正反：原生mp_demo@localhost Test Connection成功（frame-b1d5094d）；04:40:36日志明确排除pid28492，同段真实命令入口RemoteJdbcServer，未以末尾驱动参数判业务。随后IDE Run启动本地MybatisPlusDemoApplication pid17744，捕捉后n1(ids1~5)实际五条带业务源码/耗时，增强收到5条（frame-fff8c33f）。证实helper被拒而真实业务采集未被整包误伤；尚不覆盖所有未知启动器或后补扫描组合。

UI-012待根因：真实storm只读流463条后列表上限200，同结构徽章200；滚轮上移后追加两条，视口#450→#452而未抢尾。满额头删尾加会自然造成内容漂移，不能用末条可见证明FOLLOWING。清空后n1 ids1~10，增强累计482、徽章10，滚轮/导航键/显式滚底始终只到#8；隐藏Services增高和原生AltShift0切100%仍然如此（frame-940cc45f/8a8ae6f7/98a75a11/f801fb8e）。搜索id=9能显示#9（frame-6dfbb703），清搜索重建仍#8（frame-bebe8b29），因此不是原始数据丢失。已派独立t-devui-list-geometry用完整容器几何探针先复现；当前KEYWORD_WRAP不走固定行高，不能凭10/1.25=8推断重复缩放。U02本次追加行为未闭合，不判通过。

UI-008真框架复验：第四批重新IDE Run内存分片pid24004，真实三查询PASS（frame-3800a54f）；暂停增强、清空后both命令进入控制台Logic链，预览说明明确ShardingSphere无可靠类型/映射、主SQL保留两个问号，无Hibernate TRACE误提示。折叠附注展开并到末尾能看到ds_0物理表及原文`[100, BOOK,100]`（frame-f6014b10/37e470bd）；开启展示与导出脱敏、采集脱敏保持关，同条重开附注整体隐藏且说明原因（frame-acaf4859）。当前只验重新打开，已开面板即时刷新由29项预览定向覆盖，尚未GUI补；未执行带问号SQL。新增长警告在顶部被截省略、附注默认仅两行需滚动，记录可读性待收敛，不把“能看到一部分”当完整披露通过。

UI-013分片去重丢路由已实证：同批100/101真实两次Logic相同但Actual分别ds0/100与ds1/101；去重开启只显示一行×2，附注保留ds0。关闭去重、清空再both可见两条独立by-id（另有前一组待flush跨片）（frame-be362566）。代码确认dedupeKey只按restoredSql、融合不含actualSql、重复分支提前返回不走保存回调；8aeb0b50基线也存在，不是本轮新引入。已派t-devui-sharding-dedup仅让Sharding条目退出自动/手动执行级裁剪，保留每次Logic/Actual配对，其他日志族不改，不把两次执行拼成一次跨片。

U35精确屏蔽局部：主项目真实SELECT1，右键屏蔽后旧条隐藏；再请求两次，管理窗口显示“已拦截2次”（frame-daf30595）。单条切结构匹配有明确标记及关闭按钮（frame-204fdc72）；切回精确并删除本轮临时规则，缓存旧#11恢复、被拦截两次没有补放（frame-bb7084b4）。规则已恢复为空；结构不同参数、全局结构/大小写空白/双项目与重启十次仍待验。清过滤空态仍称“暂无捕获SQL”而非被筛空，属于与历史UI009同族的可发现性待核，不据此认采集失败。

UI-010原序列复验：Query旧ui_probe输出→切AUTO立即替换为user聚合→转换，候选只属于新user（frame-933cc0a0），选择真实cn.song.mybatis...User后得到新QueryWrapper<User>及完整聚合链（frame-6841613d），没有旧ui_probe弹层回灌。本轮一次时序正例，确定性旧回调覆盖由18项正向与17红/末端1红提供，不把一次未出现外推所有竞态已GUI覆盖。

## 第五批准备

UI012完整容器几何探针67a57e9f已合dev c15a63cc，只有测试，没有声称根因已复现。UI013独立只读复核未见阻断后，两提交91e15538/ba0deb91合dev cc151a87/3393dc66，尚未定向。第四批已在真实内存分片输入quit正常结束，主应用点原生Stop，再发IDE Exit；第五批脚本核PID23656退出后才取槽，避免旧沙盒与新测试共享目录。四类定向（TailFollow、DedupeIndex、Buffer、Presentation）通过`.local/validate-b5.ps1`后台启动，另一会话FinalGate-R2保持独立，本轮不启动全量、不降低分片。首次脚本在90秒旧IDE仍存活检查处安全退出，未调用Gradle；GUI发现原生MybatisPlusDemoApplication运行确认，随后选择Terminate，再次后台检查退出并验证，不能把首次前置退出计为测试失败。

第四批退出再次出现MP应用Waiting for process detach。核登记及命令行确认仅自有PID17744/父23656，JDK8线程现场取证后原生Kill结束应用；PID已消失仍有残留运行确认，选择Disconnect后runIde退出0。第五批两次前置检查因此安全停止，未启动Gradle，第三次已重新后台发起。线程复核未见ShutdownHook执行或关停锁，Tomcat仍正常服务等待，agent sender是daemon且实际JAR读超时1s，不能归因sender或已修helper；下一步需同MP/JDK8单变量禁注入且禁自动attach的A/B，尚未做。证据会话call_3uFZfaT3mUdBBBtv84GA7NPG-stdout.log，UI frame-6dd1b4ab/c1bd5024。

第五批首轮四类90/0/0/0：Presentation5、DedupeIndex15、Buffer59、TailFollow11。UI012两比例完整容器测试均绿，125%模型10/首尾bounds与preferredHeight1070、viewport和滚动最大值一致，未复现现场；不能称UI012修复。测试主动递归布局与原生真机仍有差异，已续派仅显式系统属性开启的真实滚底几何日志探针，不输出SQL/参数、不改布局，以便下轮现场定因。UI013两生产闸门临时摘除后首次被新并行登记R3前置拦截，旧XML90绿不是反向结果；核实R3独立沙盒后实际运行90项恰6红（五个去重保真、一个徽章），其余原用例不退化。立即恢复，合入属性门控现场探针bfbe5b4c后四类92/0/0/0（新增两项默认关闭/隐私诊断），src/main反向探针清除。第五批runIde仅本地init加-Dmpsv.ui012.diagnostics=true，供真实滚底前后几何取证，不自动布局救绿；当前UI012仍未修。

### UI012归因勘误：不是末条不可达

第五批PID51428，初始100%十条可到底；Zoom往返125%仍正常。重放storm满200→clear→十条后再次“底#8”，但真实几何日志action3明确buffer=model10、lastCell967+107、preferred1078、visible598+480、bar598/1078/480，诊断查询未触发重布局/renderer，几何正常。主控此前把模型index9误当sequence10，是错误前提。切“行号”排序立刻#9/#10到底（frame-862e34d2）；切回时间升序滚顶部，顺序实际为9、10、1、2、3（frame-8e859884）。因此撤销布局裁剪/丢行结论；未作布局修复。继续核日志时间相关器每键容量8与签名不符退队首是否误借时间，不凭数字巧合判根因。UI012改为时间关联/排序异常待核，原取证过程保留以免将先前误判当已证缺陷。

U02排除时间排序和满容量干扰后正反：行号模式十条未满200，滚轮上移再手动到底，追加11/12仍停#10（frame-45d63f5e）；显式滚底恢复到#12（frame-1c418c8d），再追加13/14自动到#14（frame-e89e6893）。确认滚轮暂停、手动到底不自恢复、显式恢复后追新这条路径正常；拖条/导航键独立触发和全部排序仍待补。

U02导航键独立路径：先显式恢复，再Home定位首条，追加15/16仍留#1（frame-9f63bf74/92c8972f），再显式滚底到#16。拖条尝试未实际移动滚动滑块，只选中末行，属于输入未触达，不能记拖条已验。

UI004原路径补验完成：在日志页设置新专属build/ui243-history/ui004-b5（事前不存在、无旧文件），06:35:13日志证历史扫描0文件；随后真实n1 ids21~23，异步落盘8460字节文件，未手动刷新也未提前切历史；首次切入直接显示3条文件（frame-ebcb96a1）。证明设置空扫已消费首扫后仍由进入回调刷新；不是重启读既有文件替代原场景。

## 第六批时间保真修复准备

真实history重建显示十条执行相对ms为0/2/3/4/6/8/13/16/19/21，前八条关联日志时间为214/214/215/216/217/217/218/218，后两条缺失；参数签名对应本轮1～10。文件能证错序，不能独立证被消费的候选签名。源码核实桥把无时间StdOut的IDE解析时刻当日志时间put，相关器无签名匹配又退队首；这两处保真缺口分别补真实桥及容量8判别测试。独立静态复核未见阻断，三提交合dev6ef4afcf/e0280084/f13307a6，仅时间关联，不改排序/布局/容量。首轮四类35/0/0/0（Parity14、Decouple2、Correlator15、CaptureGate4），fresh XML 2026-10-17 06:52:43～47；真实无时间桥/参数超容量均入场。临时恢复两处旧借时分支，35项恰7红（真实桥三项、相关器三项、无时间嗅探一项），失败明确为候选不应入册及跨签名借时，非配置失败。两处已恢复并diff确认，恢复四类35/0/0/0（fresh XML 2026-10-17 07:01:30）；中间一次因新并行Runtime-Encoding登记被前置拦下，不计测试红。第六批修复沙盒后台启动，尚未新包GUI。第五批沙盒仍需原生Kill后Disconnect处理同MP应用退出等待，runIde已退出0。

第六批UI012真机原路径复验：IDE35652、业务28932、Light125%；真实storm463条→清空→明确时间升序→n1 ids1~10，底部#9/#10、顶部#1/#2/#3，未再出现9/10排最前（frame-3a8109d7/4f35873c）。日志无时间StdOut候选保持空、回落执行时间，35绿/7红/恢复35绿对应两处保真修复；本原场景闭环，不外推所有带真实时间日志的跨线程顺序。

UI013新包真框架复验：第六批ShardingH2Demo再次PASS，SQL去重保持勾选，暂停增强后both的by-id两次分别为#3/#4，不再合成×2（frame-a2505757）。同一专属历史文件07:23:39两条各有独立o147-id，pd.a分别为ds0物理路由100/BOOK,100及ds1路由101/BOOK,101，主SQL仍问号、params空；证实逐次保存回调保留附注。还观察到同模板无Actual尾条，尚未归因，不能据本项通过声称分片解析所有flush边界完成。

U14/U15日志还原安全负例：真实Preparing/Parameters还原SELECT，执行选择mp_demo出现未知渲染方言确认（frame-ffc7cb5f），点No后未见native query SUBMITTED，但底部误称已提交（frame-430c895b），登记UI014独立修复，不能按状态字样认真实执行。改EXPLAIN SELECT同入口明确红字阻断未知渲染原始DML/EXPLAIN（frame-61c7c5f2）；改DELETE触发前置写确认并点No，保持已转换状态（frame-651ab311/585eeb17），没有批准写库，也未越过写确认去证明后续未知DML闸。UI014还查到runner popup final-step内同步弹模态导致焦点SEVERE，交同任务按真实回执修复，不改变安全策略。

U32引用导航五点实测：UserMapper.xml namespace→UserMapper.java22（frame-98ae8b7f）；property=username→User.java41（frame-a9c4c5c8）；include refid→XML20片段（frame-57dea2c1）；select resultMap→XML5（frame-e20e0c81）；selectUserBatchByIds id→Java121（frame-9927a6ce）。均主编辑器Ctrl+B真实落点，未修改源码，不覆盖补全/全部gutter/共存矩阵。

UI015预览快捷模板入口漏接：注解预览已选中user，右键只有Cut/Copy/Paste（frame-46060b15/5a314a84），不是选区错误。平台243自建Editor默认BasicEditorPopupMenu，模板仅挂EditorPopupMenu，原PreviewUx计划明确包含预览编辑器；已派独立修复保留基础动作并加既有模板组，不把所有主编辑器动作整包搬入。主编辑器XML为既有可用入口，后续从该入口继续COUNT/抽样。

UI015独立四类69绿、摘模板引用69项恰3红、恢复69绿，跨243/253/262 EditorEx菜单API签名已核；合dev a8748dbc/8a5480ae，当前运行包尚未包含，待下一批GUI。UI014首版静态复核指出排队覆盖/取消和缺编辑器路径可能漏终态，已回交补齐，未合入。主编辑器XML里右键未弹与全局快捷键warning未证因果：实际warning发生预览关闭恢复动作，只LOG.warn不throw，不能凭此归菜单失败。

退出A/B对照在途：关闭增强设置后停止旧agent进程28932仍需第二次原生强停，随后同IDE/同JDK8/同MybatisPlus配置重新Run，PID18824父35652，实查命令行MPSV-agent=false、idea_rt=true。执行同十条只读查询后按同一普通Stop，后台WaitForExit(90s)实测Exited=false、ObservedMs=90015（call_nQz5uTwwBD2vPfmGSLJjA5GA-stdout.log）。无MPSV agent仍复现普通Stop不退出，故MPSV agent不是必要条件，不按插件ShutdownHook死锁修；不能进一步外推IDE侧插件完全无影响。未把“关闭采集但agent仍在”冒充无agent对照。

U15主编辑器模板正例：Java注解27行选user，右键SQL快捷模板三项可见；COUNT选mp_demo，原生结果41（frame-9a780984），08:46:28明确native query SUBMITTED。随后抽样10行复用本地目标，原生Output实证`SELECT * FROM user LIMIT 10`并取回10行（frame-a13f4c1c），不能依据仍显示COUNT的旧控制台标题判断实际SQL。预览模板待UI015新包；XML注入区菜单未触达而Java可达，仍不据无菜单推断模板业务执行失败。

UI014最终owner补齐后独立56绿、摘owner比较24项恰2红、恢复56绿；只读复核确认旧链迟到abort/finish不能清新链，三个提交合dev32f36cb4/90122af6/5f44b0db。当前沙盒未含此修复，合流后定向及GUI待下一批。其修复不扩executePrepared回执覆盖口径。

## 第七批准备

第六批内存分片已quit，无agent MP对照90秒未退后由原生强停结束，确认进程结束后退出IDE；runIde退出0，本次无遗留运行确认阻挡。UI014/015合流生产5f44b0db，七类合流定向111/0/0/0（日志还原执行16/方言闸16/单飞24/错误处理16/预览编辑器31/抽样5/三代签名3），fresh XML2026-10-17 09:01:06～32，不把独立56绿与69绿相加冒充合流绿。第七批新沙盒已后台启动，本地诊断属性关闭，诊断生产代码最终清除待做。增强设置目前为对照而关闭、展示脱敏开启，后续采集复验须显式恢复增强。

第七批PID22516、生产5f44b0db。UI015原路径复验：注解gutter直开预览，选user右键保留Cut/Copy/Paste并出现SQL快捷模板（frame-43056bbb），子菜单三项启用（frame-1474e441）；Ctrl+A整SQL后同三项灰色禁用（frame-00a5fa50），未误把整SQL当表名。

UI014新包复验未通过：09:13:02弹数据源选择，09:14:57选择mp_demo直接CANCELLED，未到未知QUERY确认。原代理核实新增60秒prepare Timer把用户思考时间计入、提前过期，属于修复引入的交互回归；已回交暂停用户选择/确认阶段预算、恢复后台超时且区分超时与主动取消，同时核canceled/finalRunnable生命周期。不能把显示“已取消”当No原路径已验。

U23命名快照继续：注解selectByAnnotationId设id71另存UI-Shared，改输入72后默认/命名往返回71（frame-78b72363）；管理重命名UI-Renamed后保持原71快照（frame-8e0335b8），没有将编辑默认值覆盖命名快照。跨方法同名、删除及20上限尚未补，不将本轮局部升级整项。

UI014续修381a9ad8已合dev e47e2877：真实popup选择dispose也会canceled，新增choiceAccepted防误取消；用户选择/确认期间暂停后台预算，后台真超时单独BLOCKED。独立60绿/撤保护44项4红/恢复60绿，当前运行包仍旧，下一批需合流门禁及长等待GUI复验。临时几何诊断清理cb9811b6已合94718343，独立TailFollow11绿，最终统一门禁仍待。

第七批退出前发现无MPSV-agent业务PID61704（父22516）实际仍在运行，09:29:15启动，日志明确enhanced-disabled且未attach。对应此前Shift+F10尝试“上下文菜单”，该键在IDE默认实际为Run，故这不是后台自启或产品缺陷；已勘误“不启动业务应用”的操作记录，后续不再用Shift+F10当菜单键。该次应用在已授权只读demo范围，无新写库操作。退出确认按实际存活进程处理，未把它当死会话直接Disconnect。

## 依据与历史证据保全

- 十九轮UI11组、Runner12组及R90/R91/R92/R94/O147/V178专册。
- `20261008-1623-dev-bak对比master-新增功能验收文档.md` 与 `20261006-1031-dev分支回流237笔-验收文档.md`：总项曾勾选的预设、绑定、EXPLAIN等细分仍须本轮覆盖。
- `20261007-1244-T-SqlToJavaPromote-SQL转Java反向转换全覆盖与Beta转正-验收文档.md`：真实索引/多实体链不以Light夹具替代。
- 9月历史全量/保留上限、心跳重连、回流九任务、预热恢复等正式规程中的未闭合子项一并列入。
- 上轮临时树已按用户要求清理；原测试XML/兼容报告/安装包/事件在 `build/m19-cleanup-evidence.zip`，原分支历史在 `build/m19-cleanup-branches.bundle`。本次不得把已经清理的绝对路径写为仍在场。
