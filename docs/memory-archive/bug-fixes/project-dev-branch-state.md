---
name: project-dev-branch-state
description: dev 分支当前状态——dev worktree 布局、基线 3547 绿、自动监听四轮修复已 cherry-pick 同步进 master 并随 v2026.9.8 发行（dev 独有 T-ListMode/T-ErrReport 未回合）、并行沙盒 container 隔离法
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5c08288e-8b92-4fc2-a839-3fb497a9ae7a
---

**dev 分支布局**：dev 检出在独立 worktree `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev`，主工作区（master）`git checkout dev` 会报 "already used by worktree"——操作 dev 一律 `git -C <dev worktree>`。

**「自动开始监听」四轮修复全史（dc0c277 + e6e01ee + 0c3dc69 + 9388cdb，09-07~09-08，全量 3547/0/6）**：勾选「项目启动时自动开始监听」后项目运行不自动捕捉，四轮递进：
1. **第一轮（dc0c277）**：根因=「关→开」边沿联动挂在 `PluginSettingsConfigurable.apply()`——该类未注册进 plugin.xml 是死类（设置页重构为 Folder+Section 架构）；边沿联动迁 `SectionConfigurable.apply()` 基类、删死类、4 测试类改测试专用 AllSectionsConfigurable。
2. **第二轮（e6e01ee）**：用户复跑仍报未生效——第一轮已生效（armed×2），但用户面板手动停止两次，`stopCapture()` 在 autoStart=true 时持久化 `logAutoCaptureSuppressed=true` + 拆除全部检测链，Run x3 时三自动入口（LogStartupCapture 单次/设置边需再变更/onConsoleAppeared 的 suppressed 守卫）全部失效。修复=`SqlLogManager.onProjectProcessStarted()`（进程级回调：autoStart=true 无条件清 suppressed + startCapture 幂等）+ `RunConfigurationProcessListener`（订阅 `ExecutionManager.EXECUTION_TOPIC` processStarted；**不接 processTerminated**——对所有 RunProfile 触发防误停，项目停止仍归 onConsoleRemoved）。
3. **第三轮（0c3dc69）**：22:53 会话日志 `IllegalArgumentException: argument type mismatch`——`connection.javaClass.methods.first{name==subscribe}` 拿到泛型擦除桥方法序不定。修正=**MessageBusConnection/MessageBus 在 util.jar 是全平台稳定接口，编译期直调** `project.messageBus.connect(manager)` + `connection.subscribe(topic, listener)` 零反射；仅 execution 域（可能被 IPGP 裁剪）保留 Class.forName+platformClassLoader，且 ExecutionListener/EXECUTION_TOPIC 两个解析独立 try 单独降级。
4. **第四轮（9388cdb，被动模式）**：用户反馈「设置里开启后还没启动项目就自动开启了监听」——边沿联动按第九轮历史设计立即 armed，但进程级入口接管后 armed 冗余且违背用户语义「开启设置 ≠ 监听已开启」。修复=`SqlLogManager.processListenerActive`（订阅结果，internal set）+ `onAutoStartSettingEnabled()`（清 suppressed 后分流：active=被动不预 armed 不挂轮询；false=降级进 armed，RCM 轮询唯一检测通道）+ SectionConfigurable 边沿改调分流 + LogStartupCapture.execute 被动早退（active 时项目打开完全不动作）。**边界**：设置开启前已在跑的应用不立即捕捉（重启/再 Run 即捕获）。

**验证全绿**：最终状态 = 设置开启被动等 Run → Run 触发清 suppressed+捕捉 → 停止项目自动停 → 再 Run 自动恢复；真机日志链 `已订阅 EXECUTION_TOPIC` → `onProjectProcessStarted: 项目启动清除 suppressed` → armed/`已开始捕捉 SQL`。诊断方法论：沙盒 idea.log 排查自动捕捉看 LogStartupCapture/SectionConfigurable.apply/startCapture/onConsoleAppeared/onProjectProcessStarted 五类日志；面板 inner-class 按钮（无 action-id）点击在 ActionsLocalSummary 无痕，勿凭摘要排除手动操作。

**并行测试沙盒 container 隔离法（09-08 实战）**：并行会话在共享 dev worktree 各改 `build.gradle.kts:99 sandboxContainer.set(layout.buildDirectory.dir("<自己的沙盒名>"))`——跑 gradle 前若发现任务失败于 `prepareTestSandbox` 的 `ERROR_MAPPED_FILE`（jsqlparser 等 jar 被用户映射区域锁定），即撞了对方沙盒：先 `parallel_tasks.py check` 看谁注册了什么沙盒名，临时把自己的名字 sed 进 sandboxContainer 行，跑完**原样还原对方的行**（对方的 build.gradle.kts 修改保持未提交状态）；提交时按文件路径显式 `git add` 只提自己的文件（共享树里对方有未提交源码改动如 HistorySqlLogPanel.kt，勿动勿提）。

**20260907-1630 四问题批次（1aa63f0~f089954）**：全量绿 + verifyPlugin 243/262 Compatible。① hbm 命名查询链路贯通（HbmXmlEntityResolver 解析 `<query>`/`<sql-query>` + HbmNamedQueryGutterLineMarkerProvider 第 10 个 provider + previewHbmNamedQuery + previewHql/previewCustomDaoHql hbm fallback）；② execute 双图标家族守卫；③ panel.explain zh 改「执行计划」；④ 生成测试（M37.4）整体下线。⚠️ 新 provider 调 SqlPreviewService 须 getService 实例风格（类名静态引用 Unresolved 且换缓存不消）。

**设置页反馈红线（363591b + 6443089）**：设置页内用户操作反馈一律行内 label，勿 Messages 模态（parent=rootPanel 叠在 Settings 对话框之下）；后台回调 `invokeLater` 须 `ModalityState.any()`（默认 modality 在模态 Settings 弹窗存续期挂起——真机实证）。

**其他**：onChosen 返回须可空 `PopupStep<*>?`（FINAL_CHOICE 是 null 哨兵）+ 弹窗动作挂 doFinalStep；SlowOperations SEVERE「Plugin to blame: SQL Visualizer」（EDT 上 DataSourceSelector.createTempPsiFile→PSI findDirectory，243 沙盒实证）**另案未处理**。dev 领先 master 未 push，是否回合 master 由用户决定。**2026-09-10 复核**（merge-base --is-ancestor + git grep 分支树实证）：dev 头 e64987e（T-PerfBatch + consoleFilterProvider EP 双轨两份计划立项）；dev 独有未回合 = T-ErrReport（feedback/ErrorReportComposer，a2de464）、T-ListMode（bbcc300 三显示模式）、T-TwoLine（aae6768 元信息行），三项已挂文档站路线图「进行中」区（见 [[project-website]]）。

相关：[[runide-demo-verify-workflow]]、[[project-kotlin-to-java-port-sync]]、[[project-test-conventions]]
