---
name: t-vstack-plan
description: T-VStack 竖向排列两笔交付（master 6dfdf72 首交付 + db140aa
  扩面，2026-09-10）——INSERT 配对竖排 + 非语句横向组展开后处理；并行会话覆盖 build.gradle.kts 实录与计数口径修正
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7004b492-8b23-4ed0-87cd-cf9afd1a7f33
---

**三笔交付（2026-09-10，master）**：预览面板【竖向排列】按钮 = INSERT 专用竖排（`6dfdf72`）+ 全语句横向组展开（`db140aa`，用户裁定扩面）+ IN 展开阈值（`9ef6b20`，用户复议短清单负收益）。

**InThreshold 要点**：`expandInList` 加 `IN_EXPAND_MIN_ELEMENTS=5`——元素 <5 保持 `IN (1, 2, 3)` 同行（占位符 `IN (?, ?, ?)` 是本插件 inList 特性主力形态，`?` 对应靠参数面板顺序对照，竖排零增量纯花行高）；≥5 长清单才逐行（横向溢出痛点保留）。ORDER BY/GROUP BY/FROM 字段行有语义信息不加阈值。

**最终语义**（formatVertical）：
- INSERT → 专用路径：列清单/VALUES 一元素一行，VALUES 每值行尾按最宽前缀（含行尾逗号）对齐 `-- 列名` 配对标注（合法 SQL 路线否决截图直排）
- 非 INSERT → `format()` + `expandHorizontalGroups()` 后处理：IN 括号列表（`(` 自占一行逐元素，对齐 INSERT 组形态）、ORDER BY/GROUP BY 顶层逗号切字段（ASC/DESC 紧跟）、FROM 逗号联表一表一行；**IN (SELECT/WITH…) 子查询不展开、LIMIT 分页参数不碰（T-M29.2）**
- 实现要点：对 format **产物做纯文本后处理**（不侵入 format 主路径）；`line/trimmed` 双参（原行保前缀缩进、trimmed 供定位）——首版单参丢 AND 缩进被双 IN 用例逮住；字符串感知 matchParen/splitTopLevel

**并行实录（复用价值）**：
- 共享 build.gradle.kts 的临时沙盒行会被并行会话**整文件覆盖**（我的 sandbox-vstack-expand 行被 T-ExecSilentNoop 的 sandbox-execsilentnoop 覆盖）——临时改动用完立即跑完立即还原的意义所在；对方的在途行不碰
- 后续 gradle 撞两环境坑：共享 build 目录编译写入竞态（worker ClassNotFoundException 中断）、对方沙盒「用户映射区域」锁（prepareTestSandbox FAILED）——登记表预告坑的复现，非语义失败
- **计数口径修正**：历史「全量 3212」是跨任务累积目录计数；单次 `./gradlew test` 权威 ~2555（196 类）——记录回归数字以单次跑为准

**测试**：testVertical* 13 + testVerticalExpand* 10（含阈值回调后）+ 反转 3 + 面板 wiring；SqlFormatterTest 52/52、SqlPreviewPanelTest 46/46、全量 3230/0/6。runIde 真机留人工。

**K2J 陈旧输出劫持第三犯（新形态）**：build/classes/java/main 残留 783 旧类（含扩面前的 SqlFormatter.class，时间戳为并行 IDE 会话 Gradle 同步写入），排前于 kotlin/main 新类——**源码编译进 kotlin/main 的类是新的、jar/测试运行时吃 java/main 旧类**（javap 字节码反汇编证伪「实现错误」假设后才定位到）。清目录复绿。判别捷径：`python 搜 class 常量池 b'常量名' in data` 对比 java/main 与 kotlin/main 同名类。

**IN 阈值议题全流程（已闭环）**：扩面交付后用户质疑「IN 的参数单独一行是否合适」→ 分析判定一刀切不当（长清单几十 id 溢出弹窗该展开；短清单尤其本插件自产的 `IN (?, ?, ?)` 占位符形态竖排无增量信息纯花行高——INSERT 配对有列名增量，短 IN 没有，`?` 对应靠左侧参数面板不靠读 SQL；SQL 面板纵向空间比横向紧张；业界 DataGrip/DBeaver 亦按行宽或阈值折行）→ **用户裁定实施阈值方案**（`9ef6b20`，见上文 InThreshold 要点）。

**后续衍生缺陷（新计划）**：用户真机发现竖排/关键字换行形态在左侧输入参数后消失——根因是预览编辑器为模型派生视图（`renderSql→showSql` 全量重建覆盖），三个格式化按钮中只有 `format()` 挂在管线里。已立项 [[t-previewdisplaymode-plan]]（Plan doc 20260910-1349，未实施）。

计划：docs/开发计划/20260910-0832（首交付）+ 20260910-0939（扩面，含实施记录）。

关联 [[project-keyword-wrap-format]] [[parallel-session-index-commit-split]] [[pitfall-regression-false-failure-flakes]]。
