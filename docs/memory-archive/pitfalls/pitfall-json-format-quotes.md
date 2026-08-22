---
name: pitfall-json-format-quotes
description: JSON 格式化时参数值必须统一加引号，不能做数值/布尔类型推断，否则数值引号丢失
metadata:
  node_type: memory
  type: pitfall
  originSessionId: sess_1de4ef77-ee04-4b58-bdb1-60884269f820
---

**JSON 格式化时参数值必须统一加引号**（2026-08-20，P-05.1 格式化按钮实证）

### 问题

格式化 JSON 时如果对数值/布尔值不加引号（标准 JSON 语义），参数导入场景下会导致引号丢失：

```json
// 输入
{"username": "song", "password": 123456}

// 错误格式化（数值不加引号）
{"username": "song", "password": 123456}  // → 反向解析时 password 变成 Integer 而非 String
```

### 根因

参数导入场景中，所有值最终都要绑定为 SQL 参数（本质都是 String）。如果格式化器做了数值/布尔类型推断，原本就是字符串的数值（如 `"123456"`）会丢失引号，导致后续 `ParamImportParser` 解析类型不一致。

### 修复

格式化时所有值统一加引号，不做类型推断：

```kotlin
// 错误
if (value.matches(Regex("-?\\d+(\\.\\d+)?")) || value == "true" || value == "false")
    sb.append(value)      // 数值/布尔不加引号
else
    sb.append("\"").append(escaped).append("\"")

// 正确：所有值统一加引号
val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
sb.append("  \"").append(key).append("\": \"").append(escaped).append("\"")
```

**Why:** 参数导入场景的值本质都是字符串，JSON 类型推断会导致引号丢失和类型不一致。
**How to apply:** 任何参数序列化/格式化场景，统一用字符串表示值，不做数值/布尔/null 的类型推断。