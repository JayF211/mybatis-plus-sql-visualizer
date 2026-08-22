# HQL 日志未捕获修复 — Multiline SQL 解析器改造

**日期**：2026-08-22
**优先级**：P0（用户实测 HQL 日志 0 捕获）
**影响范围**：`HibernateLogParser.kt`（核心）+ `SqlLogPanel.kt` / `SqlListHtmlRenderer.kt` / `LogSqlModels.kt`（面板适配）+ 测试体系

---

## 一、问题描述

用户实测 Hibernate/HQL 控制台 SQL 日志未被插件捕获。

**日志样例**（Spring Boot `org.hibernate.SQL` DEBUG 输出 + `Hibernate:` 格式各一份）：

```
2026-08-22 00:34:11.251 DEBUG 37792 --- [nio-8083-exec-5] org.hibernate.SQL                        :
    /* SELECT
        u
    FROM
        UserEntity u
    WHERE
        u.username = :username */ select
            userentity0_.id as id1_2_,
            userentity0_.age as age2_2_,
            ...
        where
            userentity0_.username=?
Hibernate:
    /* SELECT
        u
    FROM
        UserEntity u
    WHERE
        u.username = :username */ select
            ...
        where
            userentity0_.username=?
2026-08-22 00:34:11.252 TRACE 37792 --- [nio-8083-exec-5] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [1]
```

## 二、根因分析

`HibernateLogParser` 按**单行 SQL** 设计，遇到 multiline 结构完全失效。五个缺陷：

| # | 缺陷 | 代码位置 | 说明 |
|---|------|----------|------|
| 1 | `Hibernate:` 独占一行不识别 | `extractAfterKeyword` `.ifEmpty { null }` | 前缀后文本为空 → return null，不触发 SQL 开启。MyBatisLogParser 有 `prefixMatched` 等待态，Hibernate 没有 |
| 2 | `/* ... */` 跨行无法匹配 | `COMMENT_PREFIX_PATTERN = """^\s*/\*.*?\*/\s*(.*)$"""` | 正则要求 `/*` 和 `*/` 在同一行，HQL 注释跨 6 行 |
| 3 | 正则要求 `/*` 在行首 | 同上 `^\s*/\*` | 即使注释闭合在同一行，`*/ select` 在行尾时 `/*` 不在行首也无法匹配 |
| 4 | SQL 续行无累积 | `feed()` 无 multiline buffer | 注释后的实际 SQL 跨 10+ 行，每行独立判断为噪声 |
| 5 | 参数行被忽略 | `feed()` 分支 2 | `pendingSql == null`（SQL 从未开启），`binding parameter` 直接 return |

**结论**：整个 SQL 块（HQL 注释 + 实际 SQL + 参数）全部当噪声忽略，0 条捕获。

---

## 三、修复方案

### 3.1 核心改造：HibernateLogParser 状态机

新增三态：`IDLE` → `COLLECTING` → emit。

```kotlin
private var collecting = false          // 是否正在累积 multiline SQL
private var inComment = false           // 是否处于 /* ... */ 注释块中
private val multilineBuffer = StringBuilder()  // SQL 累积缓冲
```

`feed()` 主干逻辑转换：

| 当前状态 | 输入 | 动作 | 下一状态 |
|----------|------|------|----------|
| IDLE | `Hibernate:` 带 SQL 同行 | 开启累积，写入 SQL | COLLECTING |
| IDLE | `Hibernate:` 独占一行 | 开启累积（空 buffer），等续行 | COLLECTING |
| IDLE | `/*` 开头且同行有 `*/` | 提取注释后 SQL | COLLECTING |
| IDLE | `/*` 开头但无 `*/` | 开启累积，标记 inComment | COLLECTING |
| IDLE | bare SQL 行 | 开启累积，写入 SQL | COLLECTING |
| IDLE | `binding parameter` | 忽略 | IDLE |
| COLLECTING | `*/` 出现（inComment） | 退出注释，取 `*/` 后文本 | COLLECTING |
| COLLECTING | inComment，无 `*/` | 跳过（注释内容） | COLLECTING |
| COLLECTING | 续行 | 追加到 buffer | COLLECTING |
| COLLECTING | 新 `Hibernate:` / `/*` 开头 | 闭合当前，开启新条目 | COLLECTING |
| COLLECTING | `binding parameter` | 闭合当前条目 + 追加参数 | IDLE |

**关键改造点**：
1. `extractAfterKeyword` 在 `Hibernate:` 独占一行时返回空字符串（非 null），作为等待态信号
2. 新增 `hasUnclosedComment(line)` 检测跨行注释
3. 续行用空格拼接（`line.trim()`），保持词法完整
4. 注释块从最终 SQL 中剥离（只保留实际 SQL）
5. `maxParseLength` 在 buffer 累积时检查

### 3.2 面板适配（SqlLogPanel + SqlListHtmlRenderer + LogSqlModels）

| 文件 | 问题 | 修复 |
|------|------|------|
| `SqlLogPanel.kt` | HTML 渲染中 `\n` 显示为空格 | 列表 cell renderer 的 SQL 部分做 `\n` → `<br>` 替换 |
| `SqlListHtmlRenderer.kt` | `escapeHtml()` 不处理换行 | `escapeHtml()` 增加 `\n` → `<br>` |
| `LogSqlModels.kt` | `summary = restoredSql.take(160)` 可能切在行中 | summary 截断时优先在第一行末尾或合理断点截断 |

### 3.3 日志转 SQL 面板（LogToSqlPanel）

**无需代码改动**：LogToSqlPanel 已使用 multiline editor 作为输入/输出，且通过 `text.lineSequence().forEach { parser.feed(it) }` 喂入解析器。一旦 `HibernateLogParser` 支持 multiline，该面板自动兼容 multiline 日志粘贴。仅需补充测试验证。

---

## 四、并行开发任务拆分

### 🔴 铁律：并行开发规范

1. **任何 `./gradlew` 命令前先 `python parallel_tasks.py check`**
2. **有活跃任务 → 登记独立沙盒**（`register` → 跑完 `done` 注销）
3. **无活跃任务 → 正常跑，但每次跑前仍 check**
4. **共享文件改动由主控协调**（本任务两 Agent 文件集不相交，无冲突）

### 任务 A：HibernateLogParser 核心改造 + 单元测试

**文件**：
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/HibernateLogParser.kt`（核心改造）
- `src/test/kotlin/cn/song/mybatisplus/visualizer/log/HibernateLogParserTest.kt`（+9 测试）

**测试用例**：

| 测试 | 输入 | 预期 |
|------|------|------|
| `多行 HQL 注释 + 多行 SQL（用户日志完整复刻）` | 6 行注释 + 10 行 SQL + 参数行 | 1 条，SQL 完整，1 参数 |
| `Hibernate: 独占一行 + 单行 SQL` | `Hibernate:` → `select id from user` | 1 条 |
| `Hibernate: 独占一行 + 多行 SQL` | `Hibernate:` → 3 行 SQL 续行 | 1 条，SQL 完整 |
| `同行注释 + 多行 SQL` | `/* load User */ select ...` → 续行 | 1 条 |
| `注释块含 SQL 关键字不误判` | `/* SELECT u FROM u */ select ...` | 注释内容不计入 SQL |
| `多条多行语句连续` | 两条 multiline SQL 背靠背 | 2 条 |
| `单行 SQL 仍正常（回归）` | `Hibernate: select id from user` | 1 条 |
| `纯 SQL 多行无前缀` | bare SQL 跨 3 行 | 1 条（recognizeBareSql=true） |
| `混合注释+bare SQL 续行` | multiline 注释后接 bare SQL 续行 | 1 条 |

### 任务 B：面板适配 + 集成测试

**文件**：
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt`（HTML 换行）
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlListHtmlRenderer.kt`（escapeHtml 换行）
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogSqlModels.kt`（summary 截断优化）
- `src/test/kotlin/cn/song/mybatisplus/visualizer/log/DualParserIntegrationTest.kt`（+1 集成测试）
- `src/test/kotlin/cn/song/mybatisplus/visualizer/log/LogToSqlPanelTest.kt`（+1 测试）

**面板适配要点**：
1. SqlLogPanel 列表 cell renderer：`SqlListHtmlRenderer.render()` 返回的 HTML 中 `\n` → `<br>`
2. SqlListHtmlRenderer.escapeHtml()：`escapeHtml()` 增加 `\n` → `<br>`（与 escapeHtml 同级处理）
3. LogSqlModels.summary：截断到第一行末尾或 160 字符处取较小值，避免 mid-line 截断

**集成测试**：
- DualParserIntegrationTest：模拟用户日志完整 multiline 喂入，Hibernate 侧正确产出、MyBatis 侧不受影响
- LogToSqlPanelTest：粘贴完整 multiline Hibernate 日志 → 正确还原 SQL

### 文件冲突分析

| 文件 | Agent A | Agent B |
|------|---------|---------|
| HibernateLogParser.kt | ✅ 读写 | ❌ |
| HibernateLogParserTest.kt | ✅ 读写 | ❌ |
| SqlLogPanel.kt | ❌ | ✅ 读写 |
| SqlListHtmlRenderer.kt | ❌ | ✅ 读写 |
| LogSqlModels.kt | ❌ | ✅ 读写 |
| DualParserIntegrationTest.kt | ❌ | ✅ 读写 |
| LogToSqlPanelTest.kt | ❌ | ✅ 读写 |

**文件集完全不相交，无冲突，可安全并行。**

---

## 五、验证与合并

1. 两 Agent 各自完成 → 跑定向测试（`--tests` 过滤）
2. 主控全量测试：`./gradlew clean test`（先 `parallel_tasks.py check`）
3. 全绿后提交，单 commit 或两 Agent 分 commit 均可

---

## 六、已知限制

- `org.hibernate.SQL` logger 格式（带时间戳/logger 名前缀的行）暂不单独识别，依赖 `Hibernate:` 格式捕获 + 去重窗口去重
- 若需支持，后续可新增 `org.hibernate.SQL` 作为第二 SQL 前缀（设置项扩展）
