# MP SQL Visualizer

**MyBatis-Plus SQL Visualizer** —— 一个 IntelliJ IDEA 插件，将 MyBatis-Plus（含 MyBatis-Plus-Join）的
Wrapper 构造链**实时转换为可读、可复制、参数已替换的 SQL**，解决调试困难、参数不可见、联表验证繁琐等痛点。

## ✨ 功能特性

- **Wrapper 全覆盖**：`LambdaQueryWrapper` / `LambdaUpdateWrapper` / `LambdaDeleteWrapper` /
  `MPJLambdaWrapper` / `MPJQueryWrapper`
- **参数替换**：`?` 占位符替换为实际值（基本类型、对象、集合；同文件变量赋值自动追踪）
- **SQL 格式化**：关键字大写、子句换行缩进，支持格式化/单行两种视图
- **多种触发方式**：右键菜单 / 快捷键（默认 `Ctrl+Alt+S`）/ 光标悬停 / 工具窗口，一键复制
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
| 工具窗口 | 打开右侧 `MP SQL Visualizer` 工具窗口查看最近结果 |

### 示例

```java
// 查询
LambdaQueryWrapper<CarDeviceInfoEntity> wrapper = new LambdaQueryWrapper<CarDeviceInfoEntity>()
    .eq(CarDeviceInfoEntity::getId, 1001L)
    .isNotNull(CarDeviceInfoEntity::getShareSchemeId);

// 生成的 SQL
SELECT id, station_id, share_scheme_id
FROM car_device_info_entity
WHERE id = 1001
    AND share_scheme_id IS NOT NULL
```

```java
// 更新（无 WHERE 时会警示全表更新）
LambdaUpdateWrapper<CarDeviceInfoEntity> update = new LambdaUpdateWrapper<CarDeviceInfoEntity>()
    .set(CarDeviceInfoEntity::getStationId, 101L)
    .eq(CarDeviceInfoEntity::getId, 1001L);
```

### 配置

`Settings → Tools → MP SQL Visualizer`：数据库方言、参数替换、格式化、显示方式、
自动预览、悬停延迟。

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
