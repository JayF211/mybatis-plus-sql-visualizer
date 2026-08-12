# MyBatis-Plus SQL Visualizer

**MyBatis-Plus SQL Visualizer** —— 一个 IntelliJ IDEA 插件，将 MyBatis-Plus（含 MyBatis-Plus-Join）的
Wrapper 构造链**实时转换为可读、可复制、参数已替换的 SQL**，解决调试困难、参数不可见、联表验证繁琐等痛点。

## ✨ 功能特性

- **Wrapper 全覆盖**：`LambdaQueryWrapper` / `LambdaUpdateWrapper` / `LambdaDeleteWrapper` /
  `MPJLambdaWrapper` / `MPJQueryWrapper`
- **参数替换**：`?` 占位符替换为实际值（基本类型、对象、集合；同文件变量赋值自动追踪）；**LIKE 自动拼百分号**（`like` → `'%值%'`）；**字段类型推断引号**（String 列输入数字恒加引号 `'123'`，数值列恒原样）
- **动态条件**：布尔重载 `.eq(true, "name", val)` 在预览左栏提供**复选框开关**（纯手动勾选：勾选拼接、取消移除；字面量 `false` 默认不勾选）
- **SQL 格式化**：关键字大写、子句换行缩进，支持格式化/单行两种视图
- **多种触发方式**：右键菜单 / 快捷键（默认 `Alt+Shift+Q`）/ 代码左侧 gutter 图标，一键复制
- **多数据库方言**：MySQL / PostgreSQL / Oracle（分页子句差异化渲染）
- **CRUD 全覆盖**：SELECT / UPDATE / DELETE / 联表 JOIN 预览，批量操作展开（F-15）
- **安全警示**：UPDATE / DELETE 无 WHERE 条件时红色提示全表操作风险

## 🚀 使用说明

### 触发方式

| 方式 | 操作 |
|------|------|
| 右键菜单 | 光标置于 Wrapper 上 → 右键 → `Convert to SQL` |
| 快捷键 | 默认 `Alt+Shift+Q`（macOS `Option+Shift+Q`），可在 Keymap 或插件设置页修改 |
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

`Settings → Tools → MyBatis-Plus SQL Visualizer`：

| 配置项 | 默认值 | 作用 | 生效时机 |
|--------|--------|------|---------|
| 数据库方言 | MySQL | 分页子句渲染（MySQL `LIMIT` / PostgreSQL `LIMIT...OFFSET` / Oracle `FETCH FIRST`） | 下次预览 |
| 替换 ? 占位符 | 开 | 关闭后**可静态求值的变量**也渲染为 `?`（左栏输入框手动填值）；代码字面量恒内联 | 下次预览 |
| 格式化输出 | 开 | 多行缩进（关 = 单行） | 下次预览 |
| 代码左侧显示预览 SQL 图标 | 开 | gutter 图标入口（关 = 不显示） | 立即 |
| 界面语言 | 简体中文 | 插件 UI 语言（English / 跟随系统可选） | 立即 |
| 快捷键 | `Alt+Shift+Q` | 内嵌录制，保存写入 IDE Keymap（冲突可「强制使用」抢占） | 立即 |

> 设置变更**无需重启 IDE**：重新触发预览（快捷键 / 右键 / gutter）即按新配置生效；
> 弹窗内「单行/多行」按钮为临时视图切换，不写回「格式化输出」配置。

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
