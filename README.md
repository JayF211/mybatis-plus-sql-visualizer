# MyBatis-Plus SQL Visualizer

**MyBatis-Plus SQL Visualizer** —— 一个 IntelliJ IDEA 插件，将 MyBatis-Plus（含 MyBatis-Plus-Join）的
Wrapper 构造链**实时转换为可读、可复制、参数已替换的 SQL**，解决调试困难、参数不可见、联表验证繁琐等痛点。

## ✨ 功能特性

- **Wrapper 全覆盖**：`LambdaQueryWrapper` / `LambdaUpdateWrapper` / `LambdaDeleteWrapper` /
  `MPJLambdaWrapper` / `MPJQueryWrapper`
- **参数替换**：`?` 占位符替换为实际值（基本类型、对象、集合；同文件变量赋值自动追踪）；**LIKE 自动拼百分号**（`like` → `'%值%'`）；**字段类型推断引号**（String 列输入数字恒加引号 `'123'`，数值列恒原样）
- **动态条件**：布尔重载 `.eq(true, "name", val)` 在预览左栏提供**复选框开关**（纯手动勾选：勾选拼接、取消移除；字面量 `false` 默认不勾选）
- **SQL 格式化**：关键字大写、子句换行缩进，支持格式化/单行两种视图
- **多种触发方式**：右键菜单 / 快捷键（默认 `Ctrl+Alt+S`）/ 光标悬停 / 代码左侧 gutter 图标，一键复制
- **多数据库方言**：MySQL / PostgreSQL / Oracle（分页子句差异化渲染）
- **CRUD 全覆盖**：SELECT / UPDATE / DELETE / 联表 JOIN 预览，批量操作展开（F-15）
- **安全警示**：UPDATE / DELETE 无 WHERE 条件时红色提示全表操作风险

## 🚀 使用说明

### 触发方式

| 方式 | 操作 |
|------|------|
| 右键菜单 | 光标置于 Wrapper 上 → 右键 → `Convert to SQL` |
| 快捷键 | 默认 `Ctrl+Alt+S`（macOS `Cmd+Option+S`），可在 Keymap 中自定义 |
| 光标悬停 | 光标悬停在 Wrapper 调用上，自动弹出 SQL 提示（可配置关闭） |
| 代码左侧图标 | Wrapper 锚点行左侧 gutter 显示 SQL 预览图标（可配置关闭），点击弹窗预览 |

### 示例

```java
// 查询
LambdaQueryWrapper<DeviceInfoEntity> wrapper = new LambdaQueryWrapper<DeviceInfoEntity>()
    .eq(DeviceInfoEntity::getId, 1001L)
    .isNotNull(DeviceInfoEntity::getStationId);

// 生成的 SQL
SELECT id, station_id, share_scheme_id
FROM device_info_entity
WHERE id = 1001
    AND share_scheme_id IS NOT NULL
```

```java
// 更新（无 WHERE 时会警示全表更新）
LambdaUpdateWrapper<DeviceInfoEntity> update = new LambdaUpdateWrapper<DeviceInfoEntity>()
    .set(DeviceInfoEntity::getStationId, 101L)
    .eq(DeviceInfoEntity::getId, 1001L);
```

### 配置

`Settings → Tools → MyBatis-Plus SQL Visualizer`：数据库方言、参数替换、格式化、
自动预览、悬停延迟、**界面语言**（默认简体中文，可切 English / 跟随系统）、
**快捷键**（展示当前快捷键 + 一键跳转 Keymap 修改）。

### SQL 预览弹窗

弹窗默认尺寸按屏幕比例自适应（宽 62% / 高 65%，下限 900×600），可手动拉伸，
**尺寸自动记忆**（下次打开保持）；左侧动态条件复选框 + 参数输入，右侧 SQL 可编辑。

## 📁 文档

| 文档 | 说明 |
|------|------|
| [项目介绍](docs/项目介绍.md) | 产品背景、痛点分析、使用说明 |
| [功能概览](docs/需求文档/20260812-1058-MP-SQL可视化-需求文档.md) | 完整功能清单与规格（`F-xx`） |
| [开发计划](docs/开发计划/20260812-1058-MP-SQL可视化-开发计划.md) | 分阶段开发计划与验收标准（`T-xx`） |

## 🔧 开发环境

- JDK 17（`jvmToolchain(17)`）
- IntelliJ IDEA 2025.3.5（平台依赖）
- Gradle 9.x（Wrapper 内置）

## 🛠 常用命令

| 命令 | 说明 |
|------|------|
| `./gradlew build` | 编译 + 测试 + 打包插件 |
| `./gradlew test` | 运行单元测试 |
| `./gradlew runIde` | 启动沙箱 IDEA 调试插件 |
| `./gradlew verifyPlugin` | 校验插件描述与兼容性 |
| `./gradlew buildPlugin` | 生成发布 zip（`build/distributions/`） |

## 📦 发布

详见 [开发计划](docs/开发计划/20260812-1058-MP-SQL可视化-开发计划.md) 的 M6 里程碑。

## 变更记录

见 [CHANGELOG.md](CHANGELOG.md)。
