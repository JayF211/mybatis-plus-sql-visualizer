# 踩坑：测试引用错误字段名（智能体未编译验证的典型症状）

> 来源：MyBatis-Plus SQL Visualizer 开发会话（M22 验证）

## 实例

- 编译错：`Unresolved reference 'paramGroups'`（SqlPreviewServiceTest.kt:4784）。
- 根因：智能体凭记忆写断言，把 **PreviewResult 的 `paramTable`** 写成 **BatchPreview 的 `paramGroups`**（两个模型都有"参数说明"字段但名字不同）——典型的"没跑过编译就交付"症状。

## 防御

1. 智能体实现任务时 prompt 明确「写完自查编译（import 完整性、方法签名与模型字段匹配）」，并在 prompt 中给出关键模型字段名（如 SetClause/PreviewResult/BatchPreview 的字段）。
2. 主控验证阶段第一个动作就是编译（`compileTestKotlin` 失败即返回给智能体或自查修复）。
3. 同款坑位：`ConditionToggle.valueSource vs valueSources`、`SqlValue.Literal.raw vs unquoted`、`SelectStatement.tableAlias` 等易混字段。
