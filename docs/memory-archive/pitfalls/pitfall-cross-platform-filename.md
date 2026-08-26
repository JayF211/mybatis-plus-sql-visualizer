---
name: pitfall-cross-platform-filename
description: 跨平台文件名生成陷阱——SimpleDateFormat 需指定 Locale.ENGLISH，Windows 文件名不允许冒号
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e386e7f2-eac0-4476-bc8f-1442ea2f7dda
---

生成跨平台安全的文件名时需注意两处：

1. **SimpleDateFormat 必须指定 Locale.ENGLISH**：中文环境下默认 locale 会产生 AM/PM 等非 ASCII 字符，导致文件名含中文。`new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)` 确保 ASCII 输出。

2. **Windows 文件名不允许 `:`**：即使模板用户输入 `$HH:mm:ss$`，Windows 会拒绝创建含冒号的文件。需自动替换：`formatted.replace(':', '-')`。macOS 也兼容（`:` 在 Finder 中显示为 `/`）。

**Why**: F-43 自动保存历史 SQL 时发现，用户自定义文件名模板可能包含时间格式，中文环境 + Windows 会导致文件创建失败（静默吞异常，用户丢失数据）。

**How to apply**: 所有生成文件名的场景：SimpleDateFormat 加 Locale.ENGLISH，最终结果 `.replace(':', '-')`。测试时用正则验证格式而非硬编码日期（时区差异会导致断言失败）。