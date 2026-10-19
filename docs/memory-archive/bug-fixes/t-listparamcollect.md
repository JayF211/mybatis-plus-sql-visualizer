---
name: t-listparamcollect
description: T-ListParamCollect 已实施交付（JdbcTemplate 命名参数集合值方括号不再误入 SQL）——方案、边界、踩坑
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2792d388-d5c9-49dc-bb14-7c5cda4b53d7
---

2026-09-15 用户真机反馈已实施交付并**已合入 master（merge 09cc6c6，2026-09-15，分支已删）**：Spring NamedParameterJdbcTemplate 单行日志 `sql = … IN (:param3) , parameters = {param3=[6b31…]}` 此前被还原成 `IN ('[6b31…]')`——方括号进了字符串字面量，集合查询恒空。全量 testShards **4123/0/0/6 绿**（基线 4103 + 20 净增，分支上两跑 + 合入后 master 编译零差异）。

根因：`JdbcTemplateLogParser.renderUntyped`（`log/JdbcTemplateLogParser.kt:220-224`）只有「数值裸 / 其余整体引号」两分支，全文件无 `IN` 位置识别与 Java List `toString()` 形态（`[a]`）处理；集合判定此前只活在预览面板（`ParamHint.inList` 拆逗号），日志还原链路从未接上。

方案（已落地，实施时照此走）：
- 新增纯函数 `log/ListParamRenderer.kt`：`inClauseParamNames(sql)`（独立 `IN` 关键字词边界 + 字符串/注释感知括号区间收集 `:name`）+ `splitListElements`（`[a]` 剥括号按逗号拆元素）+ `renderParam(value, inClause, render)`；**只在 IN 位置**剥括号，非 IN 位置行为逐字节不变（形如 `[a]` 的普通字符串仍原样加引号），空集合 `[]` 渲染 `NULL`，null 交回调（解析器回调产出 `NULL`）；
- 解析器 `replaceNamedParams` 先算 `inClauseNames` 再逐参数渲染；预览识别 `LogEntryPreviewRecognizer` 回退分支补 `namedInitialValues`（参数名 → 日志值，集合值转元素清单），**refs 与 params 数不齐时整体弃用**（防错位配到别的参数名）。
- 边界（如实声明）：本格式不存在「一个命名参数对多值」形态（多元素集合运行时展开成 `IN (:p1, :p2, …)`），只修「方括号不落进 SQL」，**不声称支持集合传参**；元素内含逗号/嵌套集合不做转义猜测；**零新增 i18n 键、不加「已按元素还原请核对」提示**（克制口径，与回退分支零 `?` 无警告一致）。

**Why:** 该链路的渲染口径分三处（解析器 `renderUntyped`、`LogSqlRestorer` 的 `?` 路径、`ParameterReplacer` 的 `:name` 路径），且预览弹窗展示的是解析器写死的 `restoredSql`（named 条目的 params 是伪参数、`?` 计数为 0，识别失败恒走回退分支）——**只改解析器不够**，「列表对了、双击弹窗还是错的」。

**How to apply:** 合 master 时按提交序列（fix → test → docs）；验收断言显式检查产物**不含 `[` / `]`**。相关：[[t-competitive-round4-plans]]、[[project-test-conventions]]。

**坑实录**：探针实验曾把 `build/tmp_probe.class`（从构建产物抄出）与真构建产物混淆，造成「源码改了字节码不变」的自证循环——临时探针文件勿放在 build 根目录并随手清理；真正的一次假阴性是 `isInKeyword` 里 `val next = i + 2` 这类折行中间变量与调用侧偏移语义不符（调用侧曾写 `i + 3` 跳过 `IN` 两字符），重写为显式 `if (i + 1 >= sql.length) return false` + `sql[i + 1]` 后稳定。首跑全量 1 例 `CopyFeedbackUiTest` 剪贴板假失败（Windows 剪贴板全机互斥），二跑零复发。