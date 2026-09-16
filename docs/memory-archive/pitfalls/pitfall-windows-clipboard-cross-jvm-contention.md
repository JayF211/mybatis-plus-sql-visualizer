---
name: pitfall-windows-clipboard-cross-jvm-contention
description: Windows 剪贴板全机唯一，并发测试 JVM 互抢报 cannot open system clipboard；对策=碰剪贴板的测试类固定同片
metadata:
  node_type: memory
  type: pitfall
  updated: 2026-09-15
---

Windows 剪贴板是**全机唯一资源**（`sun.awt.windows.WClipboard.openClipboard` 内部互斥）。当测试被拆到多个并发 JVM 执行时，两个进程同时碰剪贴板就会有一方抛：

```
java.lang.IllegalStateException: cannot open system clipboard
    at java.desktop/sun.awt.windows.WClipboard.openClipboard(Native Method)
    at java.desktop/sun.awt.datatransfer.ClipboardTransferable.<init>(ClipboardTransferable.java:77)
```

**Why**：测试代码里对 `Toolkit.getDefaultToolkit().systemClipboard` 的「快照—断言—恢复」或 `CopyPasteManager.copyText` 都会真开系统剪贴板；AWT 的模式测试面板写剪贴板是**同步**的，两个 JVM 正是可能在同一毫秒进入 `openClipboard` 竞争，输方抛 IllegalStateException。串行跑永远不冲突，只有并行才暴露——属于「假失败」家族（见 `docs/memory-archive/pitfalls/` 与 memory「回归假失败清单」中的「剪贴板 flake」），并行后被放大成稳定复现的随机失败。

**How to apply**：
- 首选对策是**调度层**而不是改测试：把碰剪贴板的测试类固定到同一分片（片内串行，天然无争用）。本仓库做法=配置期扫测试源码，含 `CopyPasteManager` 或 `systemClipboard` 的类全部塞进第 1 片：
  ```kotlin
  val affinity = testFileEntries
      .filter { (_, _, text) -> text.contains("CopyPasteManager") || text.contains("systemClipboard") }
      ...
  ```
- 若确实无法亲和（比如分属不同任务），退而求其次：测试里先探测剪贴板可用性，不可用则 `Assume.assumeNoException` / 跳过断言，不要写死失败。
- 同一类「全机唯一资源」还有：本地固定端口、约定路径的临时文件/锁文件、系统输入法状态、全局注册表项。并行测试设计时逐个过一遍，能亲和就亲和。
- 排查信号：串行全绿、并行随机 1~2 例红，且失败信息是原生 AWT/系统调用异常 → 先怀疑全机唯一资源。

**实测案例（2026-09-15）**：T-TestPerf2 冷启动第一跑（两片并发），shard2 的 `SqlPreviewPanelTest.testReformatShortcutFormatsSql` 在 `setUp` 快照剪贴板时抛 `cannot open system clipboard`，而同代码串行跑从无此问题。实施亲和分区后连续三轮（2 片 / 3 片 / 3 片 + 堆实验）零复发。碰剪贴板的测试类共 5 个：SqlPreviewPanelTest、SqlPreviewPanelImportTest、XmlPreviewPanelTest、CopyFeedbackUiTest、SqlLogListCopyTest。

**参考**：[[t-testperf2]]、[[pitfall-regression-false-failure-flakes]]、`docs/经验总结/20260915-0900-插件测试套件提速-经验总结.md` §四.5