---
name: project-reverse-placeholder-bugfix
description: SQL→Java 反向 `?` 占位符识别缺陷（round-trip 断点）——已交付（2026-08-16），实施细节并入 project-reverse-sql-java-improvements
metadata:
  node_type: memory
  type: project
  originSessionId: sess_51eafb4b-6feb-4624-a097-590ca8ab7f68
---

**已交付（2026-08-16）**：`parseValue` 对 `?` 从抛 KEY_UNSUPPORTED 改为 `SqlValue.Placeholder`，WHERE/SET/INSERT/LIMIT 全链路支持（eq/ne/gt/ge/lt/le/in/between/like + set + 实体 setter + last），`LIKE ?` 由硬报错放宽为 LikePosition.NONE。完整实施见 [[project-reverse-sql-java-improvements]]（含后续 AUTO 实体反查/手动选择/调用形态三轮）。

**历史根因（2026-08-16 用户 bug 反馈）**：Java→SQL 正向预览（`.eq(AccountRoleRelEntity::getRoleId, dto.getRoleId())` → `WHERE role_id = ?`）后复制 SQL 做反向转换，`role_id = ?` 未识别为 eq，整条降级 `.apply("role_id = ?")`。正向把参数建模 `SqlValue.Placeholder` 渲染为 `?`；反向 `parseValue` 显式抛 KEY_UNSUPPORTED（T-M36 P0 范围裁剪，类头 KDoc「明确不支持」列表含 `?`，旧测试断言降级）。**关键证据**：JavaCodeGenerator.javaValue 早有 `Placeholder → "\"?\""` 渲染分支——核心修复只动 parser，生成器零改动。

相关：[[project-t36-deliveries]]
