---
name: pitfall-shard-class-discovery-filename-vs-class
description: 分片类发现不能按「文件名=FQCN」推导，Kotlin 文件名与类名可以不一致（实测漏 3 用例）
metadata:
  node_type: memory
  type: pitfall
  updated: 2026-09-15
---

按下发测试文件的**文件名**推导全限定类名（`文件名.replace(".kt", "")` 拼包名）来生成测试任务过滤清单，会漏掉文件名与类名不一致的类。Kotlin/Java 都允许一个文件里声明与文件名不同的类（Kotlin 无文件类约束；Java 仅 public 类须同名，非 public 类可以不同）。

**Why**：分片/分桶任务用 `filter { includeTestsMatching(fqcn) }` 指定要跑的类——名字推导错了，过滤清单里就没这个类，该类的用例**静默不跑**：任务成功、报告绿，只有聚合计数和串行对不上。

**How to apply**：
- 类发现改为**读文件内容提取类名**，而不是拼文件名：
  ```kotlin
  Regex("""(?m)^(?:public\s+|internal\s+|open\s+|abstract\s+|final\s+|data\s+|sealed\s+|annotation\s+)*class\s+(\w+)\b""")
      .findAll(text).map { it.groupValues[1] }.filter { it.endsWith("Test") }
  ```
  （列 0 的顶层声明；无匹配时回退文件名，兼容嵌套类套件等形态。）
- **判绿必须比对计数**：分片/分桶任务的用例总数要与串行权威口径逐个类、逐个用例对上。本仓库聚合报告专门打印 `SHARD TOTAL: tests=... failures=... errors=... skipped=...`，就是为了让这种静默漏跑一眼可见。
- 类名与文件名错位本身在 Kotlin 里合法但会坑所有基于文件名的工具（分片、看板、覆盖率报告），发现后建议直接改名对齐（改文件名不改类名是安全的，无需动测试逻辑）。

**实测案例（2026-09-15）**：T-TestPerf2 实施中分片并集 4080 比串行 4083 少 3，逐类 diff 才定位到 `src/test/kotlin/cn/song/mybatisplus/visualizer/generator/CrudPreviewFlowAndTypesTest.kt` 文件里装的其实是 `class DbTypeQuoteHintTest`（3 个用例）。该错位早于本次提速（文件一直被串行 test 正常收集，只有按文件名推导的分片逻辑漏它），改为内容提取类名后 parity 达成。全库扫描仅此一处。

**参考**：[[t-testperf2]]、`docs/经验总结/20260915-0900-插件测试套件提速-经验总结.md` §五.2