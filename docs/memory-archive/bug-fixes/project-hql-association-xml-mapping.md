---
name: project-hql-association-xml-mapping
description: HQL 关联字段路径解析 + Hibernate XML 映射支持（feature/f45 分支），含 entity resolution 三级降级链
metadata:
  node_type: memory
  type: project
  originSessionId: sess_911e7169-91a5-4c49-a147-49f42312791e
---

## HQL 关联字段路径解析 + Hibernate XML 映射支持

**分支**：feature/f45-maintainPage-xml-preview
**状态**：代码已实施，master 已合并（无冲突），测试全部通过
**日期**：2026-08-26（初始）/ 2026-08-26（合并后更新）

### 实施内容

**Phase 1：关联字段路径解析（HqlConverter 增强）**
- `HqlConverter.kt` 新增 `mapAssociationPath()` 方法
- `alias.field.id` → 外键列名（如 `rti.ruleTable.id` → `rti.RULE_TABLE_ID`）
- `alias.field.property` → 注释提示需要 JOIN
- 须在 `mapPropertyToColumn` 之前调用

**Phase 2：Hibernate XML 映射支持**
- `HbmXmlMapping.kt` — XML 映射数据模型（ClassMapping, PropertyMapping, AssociationMapping）
- `HbmXmlEntityResolver.kt` — 解析 `.hbm.xml` 文件（DOM 方式），按实体类名索引
- 查找策略：FilenameIndex 搜索 `*.hbm.xml` → 解析 `<class>/<id>/<property>/<many-to-one>/<one-to-one>`

**Phase 3：实体解析降级链（SqlPreviewService）**
```
1. JpaEntityResolver（JPA 注解）  ← 现有
2. HbmXmlEntityResolver（.hbm.xml）  ← 新增
3. 命名策略猜测  ← 现有（fallback）
```
方法：`resolveHqlTable()` + `convertHbmMappingToTableInfo()`

### 文件变更

| 操作 | 文件 |
|------|------|
| 修改 | `converter/jpa/HqlConverter.kt` — mapAssociationPath |
| 修改 | `ui/SqlPreviewService.kt` — 三级降级 + HBM 转换 |
| 修改 | `ui/SqlPreviewPanel.kt` — 编译修复（generatePlantUml→buildDiagramModel） |
| 新增 | `psi/jpa/HbmXmlMapping.kt` |
| 新增 | `psi/jpa/HbmXmlEntityResolver.kt` |
| 新增 | `test/.../HqlConverterAssociationTest.kt`（8 测试） |
| 新增 | `test/.../HbmXmlEntityResolverTest.kt`（4 测试） |

### 测试结果

- HQL 转换器测试：全部通过（含新增 8 个关联路径测试）
- HBM XML 解析器测试：全部通过（4 个）
- JOIN 图测试（JoinDiagramGeneratorTest）：通过（主分支新测试也兼容）
- JPA 实体解析器测试：预先存在失败（JpaEntityResolverTest/JpaEntityResolverJoinTest），与本次修改无关

### 合并记录

**2026-08-26 合并 master → feature/f45**
- 主分支新增 3 个提交（F-1.1 JOIN 图 UI 优化、TitledBorder 修复、文档）
- 合并无冲突（ort 策略自动处理）
- 主分支删除了 F-45 文件（PagesXmlPreviewService/Parser/GutterLineMarkerProvider 及测试），feature 分支保留
- 编译通过 + 全部 HQL/HBM/JOIN 测试通过，无回归

### 用户原始问题

用户项目使用 Hibernate XML 映射（.hbm.xml），非 JPA 注解。HQL：
```java
"select rti.gradeType from RuleTableItem rti WHERE rti.ruleTable.id = :ruleTableId AND rti.type = :type ORDER BY rti.lineNO"
```
期望：表名 `THORN_RULE_TABLE_ITEM`，列名 `RULE_TABLE_ID`/`LINE_NO`/`GRADE_TYPE`

**Why:** 旧版 Hibernate 项目（vTradex Forest 框架）大量使用 .hbm.xml 映射，仅支持 JPA 注解会导致表名/列名全靠猜测
**How to apply:** 后续遇到 HQL 识别不准确问题，优先检查是否有 .hbm.xml 映射文件；关联字段路径解析已内置
