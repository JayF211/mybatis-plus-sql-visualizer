---
name: t-histparam-plan
description: T-HistParam 已交付——历史页参数识别三层丢失链 + 字面量 SQL 参数恢复（2026-09-11 实施，全量 3284/0/6 绿，未提交）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1d002463-c35f-40bf-bc67-5767d2c2b20d
---

T-HistParam 已交付两轮（2026-09-11，cef6081 + f5983b8；全量 3292/0/0/6 绿，runIde 真机留人工）：SQL 历史 tab 预览「无未解析参数」根治 + 字面量内联 SQL 参数恢复。二轮补数值谓词识别——用户追加反馈 `WHERE (t.id = 1)` 仍无参数行，一期只认单引号字符串漏掉数值。

落地五件套：SqlLogParamCodec（Base64(JSON{r,p}) 载荷）+ SqlLogAutoSaver 写 `-- pd: ` 行（params 非空或 rawSql 有 `?` 才写）+ SqlLogFileReader 读 v2（解码失败降级正文注释行）+ LogEntryPreviewRecognizer.toPreviewResult(formatSql, literalRecovery) 统一识别通道（双面板 openPreview 共用；历史页 formatSql=false 现状）+ LiteralParamRecovery 字面量恢复。设置 literalParamRecovery 默认开，PluginSettingsPersistenceTest 计数 23→24。

字面量识别口径（LiteralParamRecovery 单遍扫描，状态机与 placeholderPositions 同形态）：单引号字符串全量识别；数值限定谓词语境——前一 token 是比较运算符（`= -1` 负号保留比较上下文、负号留原文）、IN/VALUES 列表括号内、BETWEEN 双界。**LIMIT/OFFSET/投影常量/函数实参不识别是刻意设计**——MP 分页查询 LIMIT 恒为字面量，全量数值识别给每条分页查询加噪音参数行；多行 VALUES 第二行起不识别（列表闭合不重臂）；非纯数字 token（`0x1F`/`1E5` 后随词字符）不识别。

三个实施期新知（计划文档变更记录已同步）：
- **backfillParamsBySource（SqlPreviewPanel.kt:2404）按 `?` 字符朴素替换**——改写后 SQL 残留非参数 `?`（注释内）回填必错位，恢复前须加 naive `?` 计数守卫；占位符计数（placeholderPositions）与回填口径不一致是既有隐患，字面量恢复绕不开。
- **逐字符往返熔断拦不住反斜杠盲区**——`'don\'t'` 错切出 `don\` 后引号渲染恰能重组原文、verify 恒真，但畸形值进参数模型改值即坏 SQL；正解是识别器跳过尾反斜杠片段，熔断只作纵深防御。
- **pd 行会被旧头部判据误判**——countInFile/parseContent 的「`-- ` 起始无 source:」分支把 pd 行当 F-43 头部，块劈开计数翻倍，两处都须显式排除。

判别指纹仍有效：预览「零占位符+零警告」=字面量捕获。P-05 历史召回只填空值不覆盖预填值，故「初始显示逐字符一致」成立。相关 [[t-previewdisplaymode-delivery]]。
