---
name: project-pages-xml-preview
description: F-45 已完成——低代码框架 XML（<pages>/<maintainPage>/<datasource> CDATA HQL）预览支持
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0e7ab5e6-b61d-4733-ac15-9ffc890f8ee2
---

# maintainPage XML HQL 预览（F-45，已完成 2026-08-26）

支持 VTradex Forest 框架的 XML 配置文件中的 HQL 预览。该格式与 MyBatis mapper XML 完全不同。

## 关键发现

- **`<datasource>` 内容为 HQL**（Hibernate Query Language），非原生 SQL
- **无动态参数** — HQL 参数绑定由框架运行时处理，XML 中不出现占位符
- vTradex Forest 是唯智信息私有框架，无公开文档/源码

## XML 结构

```xml
<pages>
    <maintainPage id="maintainLangLibPage" title="..." autoQuery="true"
                  entityClass="com.vtradex.forest.model.lang.TfLangLib">
        <datasource><![CDATA[
        select ... from Entity ... where ... order by ...
]]></datasource>
        <columns>
            <column id="t.id" title="t.id" visible="false" sortable="true" dataType="long"/>
        </columns>
    </maintainPage>
</pages>
```

## 实现（commit 0556f9e + c9653b1）

| 文件 | 职责 |
|------|------|
| `xml/PagesXmlParser.kt` | DOM 解析 `<pages>` → `PagesStatement` 清单（id/title/autoQuery/entityClass/sql/columns） |
| `ui/PagesXmlPreviewService.kt` | 纯 HQL 路径 → `PreviewResult`（不走 MyBatis 动态 SQL 渲染） |
| `ui/gutter/PagesXmlGutterLineMarkerProvider.kt` | `<maintainPage>` 行 gutter 图标 |
| `actions/ConvertToSqlAction.kt` | XML 分支按根元素名分派（`pages` vs `mapper`） |
| `model/PreviewResult.kt` | 新增 `pagesColumns: List<PagesColumn>?` 字段 |
| `ui/SqlPreviewPanel.kt` | 列信息 tab（DefaultTableModel 展示 id/title/dataType/visible） |

- **测试**：25 个新增测试（PagesXmlParserTest 12 + PagesXmlPreviewServiceTest 14），2531 全量全绿
- **分支**：`feature/f45-maintainPage-xml-preview`（worktree 实施）
- **未合并到 master**

## 关键设计

- **不走 XmlMapperParser 路径**：新格式无动态 SQL，独立解析器
- **复用 PreviewResult 统一展示**：SQL 高亮/格式化/复制全部复用
- **实体类推断**：`entityClass` → EntityResolver → SqlCompletionContext（补全表/列来源）
- **gutter 判定**：`tag.name=="maintainPage" && parentTag.name=="pages"`

## 后续可选

- HQL → SQL 转换预览（复用 HqlConverter）
- HQL 实体字段补全
- 更多列属性展示（sortable/format）

关联：[[xml-engine]] [[project-architecture]] [[project-extension-points]]