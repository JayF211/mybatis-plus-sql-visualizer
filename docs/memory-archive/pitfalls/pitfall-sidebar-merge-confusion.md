---
name: pitfall-sidebar-merge-confusion
description: F-44 实施时搞反了侧边栏去除对象——应保留主侧边栏（log），去除独立历史侧边栏（history）
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_e386e7f2-eac0-4476-bc8f-1442ea2f7dda
---

F-44 实施时将"去除独立侧边栏"误解为去除主侧边栏（`cn.song.mybatisplus.visualizer.log`），实际上：

- `cn.song.mybatisplus.visualizer.log` = 主侧边栏（3 tabs：SQL 日志/日志转SQL/SQL→Java）——**应保留**
- `cn.song.mybatisplus.visualizer.history` = 独立的 SQL 历史记录侧边栏——**应去除**（合并到主侧边栏新增 tab）

**Why**: 用户说"将现有的侧边栏按钮【SQL日志】去除，合并到插件的主侧边栏中"——这里"侧边栏按钮"指的是独立工具窗口列表中的重复按钮，而"主侧边栏"指的是插件的主要入口（log）。两个不同的"侧边栏"概念混淆。

**How to apply**: 当用户提到"去除侧边栏"时，必须确认去除的是 plugin.xml 中哪个 toolWindow id。正确做法：保留 log（主侧边栏）注册，注释 history（独立侧边栏）注册。