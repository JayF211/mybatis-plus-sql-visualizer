---
name: pitfall-settings-tree-order-locale
description: 设置树同级排序=Weighted.COMPARATOR（groupWeight 降序优先→displayName 自然比较兜底），缺
  weight 时中英文子页顺序随语言漂移；groupWeight 是唯一语言无关钉序杠杆
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4de7bcc3-9659-46b2-a492-aa43e4f25bb5
---

设置对话框左侧树同级项排序机制（243 源码 + 262 字节码 + 平台自身 XML 三重核实，2026-09-15）：
`com.intellij.openapi.options.ex.Weighted.COMPARATOR`——先按 `ConfigurableEP.groupWeight` **降序**，再按 `StringUtil.naturalCompare(displayName)` 兜底（ConfigurableWrapper.myWeight 直接取 ep.groupWeight）。plugin.xml 的声明序只在比较器全 0 时靠稳定排序保留。

**Why**：子页 EP 全部不设 groupWeight 时，兜底名称比较随插件语言走——中文按码点序（SQL日志<SQL预览<关于<界面与操作<表名映射），英文声明序只是显示名解析路径差异下的隐式侥幸，中英文顺序必然漂移（2026-09-15 真机截图实证，码点序 5/5 吻合）。

**How to apply**：同级子页显式声明递减 groupWeight（本项目 ui=50/preview=40/tableMapping=30/sqlLog=20/about=10，新增子页按区间插值）；守卫=PluginXmlStructureGuardTest「设置子页面groupWeight按声明序严格递减」（提交 d186d5c）。诊断路线：平台类查本机 IDEA lib 各 jar（javap 按 .class 文件路径反编译最稳），243 全源码 jar 在 gradle caches ideaIU-*-sources.jar。
