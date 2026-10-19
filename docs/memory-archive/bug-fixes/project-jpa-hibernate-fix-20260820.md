---
name: project-jpa-hibernate-fix-20260820
description: JPA/Hibernate 识别缺陷三波并行修复已完成并合并到 master
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a4fb5d93-5d10-469d-a2f7-b9844892efec
---

**JPA/Hibernate 识别缺陷修复**（2026-08-20）：用户 demo 项目（Spring Boot 2.6.3, `javax.persistence`）实测发现大量识别失败。经全量代码审查发现 3 个 P0 阻断缺陷 + 7 个能力缺口，三波并行修复：

- **波次1 (P0)**: 修复 gutter 锚点 + 新增 JPQL/CriteriaQuery gutter + ConvertToSqlAction JPA 分支 + SqlPreviewService.previewJpql/previewCriteriaQuery
- **波次2 (P1)**: HQL 支持(HqlQueryLocator+HqlConverter) + JPQL 增强 + CriteriaQuery GROUP BY/HAVING/聚合/JOIN ON
- **波次3 (P2)**: @NamedQuery 定位 + void 返回类型兜底 + JpaDemoIntegrationTest(23测试覆盖全部18个demo方法)

**结果**: 23 文件改动(+2973/-29)，1751 测试全绿。已 merge 到 master（commit `a90dda6`）。

**关键教训**: 多智能体并行开发时，智能体越权修改其他波次的文件是高频问题（波次1和波次3都越权修改了波次2的职责文件）。主控必须检查工作树 diff 并还原越权改动。开发计划详见 `docs/开发计划/20260820-2045-JPA-Hibernate识别缺陷修复-开发计划.md`。

**文件清单**: 新增 HqlQueryLocator.kt, HqlConverter.kt, NamedQueryLocator.kt, CriteriaQueryGutterLineMarkerProvider.kt, JpqlGutterLineMarkerProvider.kt, CriteriaQueryConverterHavingTest.kt, HqlConverterTest.kt, JpaDemoIntegrationTest.kt, HqlQueryLocatorTest.kt, SqlPreviewServiceJpaTest.kt, CriteriaQueryGutterLineMarkerProviderTest.kt, JpqlGutterLineMarkerProviderTest.kt。
