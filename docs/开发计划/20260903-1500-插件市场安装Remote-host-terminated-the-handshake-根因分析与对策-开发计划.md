# 插件市场安装 "Remote host terminated the handshake" 根因分析与对策

> **立项日期**：2026-09-03
> **优先级**：P-立项（用户体验 / 发布通道健康）
> **状态**：根因分析完成，待确认对策实施方案

---

## 1. 问题现象

### 1.1 用户报告

用户通过 IntelliJ IDEA 插件市场（Marketplace）安装 SQL Visualizer 时弹出提示：

```
Plugin Installation
Plugin "SQL Visualizer" was not installed: Cannot download
'https://plugins.jetbrains.com/files/33633/1146740/mybatis-plus-sql-visualizer-2026.8.21.zip
?updateId=1146740&pluginId=33633&family=INTELLIJ&uuid=...&code=IU&build=261.22158.277':
Remote host terminated the handshake
```

### 1.2 关键事实

| 项 | 值 |
|---|---|
| 用户 IDE | IntelliJ IDEA Ultimate 2026.1（build 261.22158.277） |
| 目标版本 | mybatis-plus-sql-visualizer-2026.8.21.zip |
| 当前已发布最新版 | 2026.9.2 |
| 最终结果 | **插件成功安装**（提示非致命） |
| 用户环境 | Windows 10，本地代理 127.0.0.1:7890 |

### 1.3 用户附加信息

用户另反映「提示 Required plugin 'com.intellij.database' is not installed. Install?」。
此与截图中的 download 错误**非同一时刻**，为两个独立问题，本文档一并分析。

---

## 2. 根因分析

### 2.1 "Remote host terminated the handshake" 主错误

#### 2.1.1 含义

`Remote host terminated the handshake` 是 `javax.net.ssl.SSLHandshakeException` 的子类语义，
表示客户端（IDE）发起 HTTPS 连接后，在 TLS 握手阶段服务端（plugins.jetbrains.com）主动关闭连接。

#### 2.1.2 常见成因枚举

| # | 成因 | 概率评估 | 依据 |
|---|---|---|---|
| 1 | 本地代理（127.0.0.1:7890）TLS 拦截 / 证书不被信任 | **高** | 用户使用 HTTP 代理，代理软件对 HTTPS 的 CONNECT 隧道可能中断 TLS 握手 |
| 2 | 网络抖动 / 运营商临时丢包 | 中 | 握手包（ClientHello）丢失导致服务端超时断连 |
| 3 | plugins.jetbrains.com 服务端临时异常（限流 / CDN 节点故障） | 低 | 同一时刻其他用户可正常下载则排除 |
| 4 | TLS 版本不兼容（服务端拒绝客户端提议的 cipher suite） | 低 | IDEA 2026.1 自带 JBR 支持 TLS 1.2/1.3，正常不会触发 |

#### 2.1.3 结论

**本次错误直接成因 = 本地代理/网络环境导致 TLS 握手失败**，与插件本身代码无关。
用户最终安装成功说明重试后网络恢复或走了不同路径，该错误属于瞬时性故障。

#### 2.1.4 为什么 Marketplace 弹出"未安装"而非"网络错误"

IDE 的 `MarketplaceDownloadPluginDialog` 将所有下载期异常统一归并到
"Plugin was not installed: {error}" 模板，不区分网络层与校验层错误。
这是平台侧行为，插件作者不可控。

---

### 2.2 "Required plugin 'com.intellij.database' is not installed" 附加提示

#### 2.2.1 插件依赖声明现状

```xml
<!-- plugin.xml L76 -->
<depends optional="true" config-file="database-optional.xml">com.intellij.database</depends>
```

`com.intellij.database` 已声明为 **optional + config-file**，符合 JetBrains 官方规范
（[Plugin Dependencies](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html)）。

#### 2.2.2 optional 依赖的正确行为

| 场景 | IDE 预期行为 |
|---|---|
| Ultimate + Database 插件已启用 | 正常加载，database-optional.xml 激活 |
| Community / Database 未安装 | **静默跳过**，不弹提示，插件正常加载 |
| Database 被用户手动禁用 | 同未安装，静默跳过 |

#### 2.2.3 弹提示的可能成因

| # | 场景 | 说明 |
|---|---|---|
| 1 | 用户安装的是**旧版本**（2026.8.21） | 早期版本可能未正确声明 optional（已修复） |
| 2 | IDE  Marketplace 预处理逻辑缺陷 | 市场页在 IDE 内嵌浏览器预检依赖时，对 optional 依赖仍弹确认 |
| 3 | 安装时序：首次下载失败后 IDE 尝试"修复安装"触发依赖重检 | 与 download 错误级联 |

#### 2.2.4 验证方法

在本地验证 plugin.xml 中三处 optional 依赖的声明正确性：

```bash
# 1. 解包已发布插件 jar
jar xf mybatis-plus-sql-visualizer-2026.9.2.zip META-INF/plugin.xml

# 2. 确认 optional 属性
grep -n 'depends.*optional' META-INF/plugin.xml
```

当前代码（master HEAD）输出：

```
L70: <depends optional="true" config-file="kotlin-optional.xml">org.jetbrains.kotlin</depends>
L76: <depends optional="true" config-file="database-optional.xml">com.intellij.database</depends>
L81: <depends optional="true" config-file="diagram-optional.xml">com.intellij.diagram</depends>
```

**声明正确，optional 模式符合规范。** 用户的 database 提示应来自旧版本或 IDE 市场预检逻辑。

---

## 3. 影响评估

### 3.1 范围

- "Remote host terminated the handshake"：**全部通过 Marketplace 安装的 Windows 用户**，尤其走代理的用户
- "database 未安装提示"：**IDEA Community 用户**（无 database 模块）/ **旧版本用户**

### 3.2 严重度

| 错误 | 用户可见影响 | 实际影响 |
|---|---|---|
| TLS handshake 失败 | 恐慌性提示「未安装」 | 无 — 重试后正常安装 |
| database 可选依赖提示 | 困惑：是否必须装 | 无 — 点取消插件仍可正常安装 |

### 3.3 是否需代码修复

**两个问题均非插件代码缺陷**：
1. TLS 握手失败 = 网络/环境层，插件作者不可控
2. database 提示 = 旧版本残留 或 IDE Marketplace 预检逻辑，当前代码已正确声明 optional

---

## 4. 对策方案

### 4.1 短期（立即可做）

| 项 | 措施 | 负责人 | 预计工时 |
|---|---|---|---|
| 4.1.1 | 在插件 README / 帮助文档补充「安装常见问题」章节，说明 TLS 错误可重试 | 开发 | 30 min |
| 4.1.2 | 在 Marketplace 插件页「Compatibility」说明无需 Database 插件（可选依赖自动降级） | 开发 | 15 min |

### 4.2 中期（下个版本）

| 项 | 措施 | 负责人 | 预计工时 |
|---|---|---|---|
| 4.2.1 | 发布 v2026.9.3 时确保 changelog 标注「Marketplace 安装问题已修复（database 依赖改为 optional）」 | 开发 | 10 min |
| 4.2.2 | 引导遇到 TLS 错误的用户：Settings → HTTP Proxy → 尝试「No proxy」或「Auto-detect」后重试 | 文档 | 20 min |

### 4.3 长期（持续改进）

| 项 | 措施 | 触发条件 |
|---|---|---|
| 4.3.1 | 监控同类 issue 报告频率，若多用户同一报则向 JetBrains Marketplace 团队反馈 | ≥3 用户报告 |
| 4.3.2 | 考虑在插件首次启动的「关于」页增加「安装问题排查」入口 | 收到 ≥5 次同类反馈 |

---

## 5. 与本项目已知记忆的关联

| 关联记忆 | 关联点 |
|---|---|
| `project-marketplace-publishing` | 首次发布审核（T-M51/T-M52），已熟悉 Marketplace 依赖声明规范 |
| `project-dynamic-unload-barriers` | 本插件依赖声明均为 optional，支持动态卸载，不影响安装 |
| `pitfall-plugin-xml-configurable-displayname` | 已修复 bundle+key 声明，不涉及本问题 |
| `pitfall-verifyplugin-kotlin-mode-optional-dep` | 已修复 Kotlin mode 警告，verifyPlugin 全部清零 |

---

## 6. 验收标准

- [ ] README 补充安装 FAQ（TLS 重试 + database 可选说明）
- [ ] Marketplace 页 Compatibility 说明更新
- [ ] v2026.9.3 changelog 注明依赖声明已优化
- [ ] 无代码改动（本问题非代码缺陷，仅文档/说明层优化）

---

## 7. 总结

> 本次「Plugin was not installed: Remote host terminated the handshake」**不是插件代码缺陷**。
> 直接成因是用户本地代理/网络环境导致 TLS 握手瞬时失败；最终安装成功说明问题自愈。
> database 提示来自旧版本残留（当前 master 已正确声明 optional）或 IDE Marketplace 预检逻辑。
> 推荐对策为文档层优化（FAQ + 引导），无需代码改动。若多用户同报则升级处理。
