---
name: t-task-queue-skill
description: task-queue 任务目录监视器 skill 定案为会话挂起守望进程模式并实测通过+20260916 起真实会话常态化运行（多轮跨会话接力捞取滞留任务，实跑 interval=120s 用户指定）；产物未提交
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e4f3f958-4221-4b15-b014-feda56ac234d
---

task-queue 任务目录监视器 skill，2026-09-16 **定案为会话挂起模式并端到端实测通过**，产物未提交：`.agents/skills/task-queue/SKILL.md`（协议+SOP）+ `scripts/watchdog.py` + `scripts/notify.ps1` + `docs/架构设计/task-queue任务队列-说明文档.md`。`.tasks/` 已加 .gitignore。

**架构定案（用户两度纠偏后的最终版）**：本会话 run_in_background 挂 watchdog.py（默认 10 秒间隔、30 分钟超时），发现 pending 新任务即输出一行（TASKQUEUE NEW/STATE off/TIMEOUT）并退出→后台任务退出自动唤醒会话→代理认领（mv doing/）实施归档（done/failed+journal）→重挂守望循环。用户明确拒绝过 CronCreate 定时任务底座（「我不是让你创建 zcode 的定时任务」），也否掉「后台进程直调 zcode CLI headless 实施」路线——**实施必须在会话内做，守望进程只当眼睛**。queue.state 开关 on/paused/off：off 守望报停退出结束循环，paused 进程内跳过任务堆积，缺失按 on 自愈；通知只在有动作轮次（CLAIM/DONE/FAIL/STOPPED）弹 toast，空轮静默。

**实测记录（2026-09-16 08:48）**：三条退出路径+认领→实施→归档→toast（notify.ps1 exit 0）全绿；首跑实例归档 `.tasks/done/20260916-0820-env-selfcheck.md`（env-selfcheck 已完结，勿再等它）。

**废弃路线与原因（勿再走）**：①CronCreate 底座——用户否决，且实操撞平台坑（见下）；②zcode CLI headless——技术可达但认证链脆弱且无需求：node --experimental-sqlite 跑 `D:\RuanJian\AI\ZCode\resources\glm\zcode.cjs`（桌面端 bundle，CLI 入口），0.16.5 版 --settings/--max-turns 是帮助页假选项（实参 Unknown option 被拒）；模型配置走环境变量 ZCODE_MODEL=bigmodel/glm-5.1 + ZCODE_MODEL_BASE_URL=https://open.bigmodel.cn/api/anthropic + ZCODE_API_KEY（取自 ~/.zcode/v2/credentials.json 的 coding-plan api-key）；bigmodel providerId 走 SigV4（ClientRequestSigningV4Error）；代理 MITM 自签证书需 NODE_TLS_REJECT_UNAUTHORIZED=0；node 22.10 需 --experimental-sqlite。仅存档备查。

**平台坑（CronCreate 并行会话撞上，佐证废弃）**：`global retained-task limit of 20 reached`——全局保留自动化上限 20 个，满即拒建且本轮不可自动恢复，须用户在自动化页手动删旧；2026-09-16 时点存量 16 个（活跃 5：架构设计内容更新/插件性能问题分析/精简记忆/同类插件横向对比/kotlin-to-java 分支同步；其余 11 个 completed+停用历史一次性任务是清上限候选）。

**机制沉淀**：ZCode 后台任务退出会自动唤醒所属会话（task-notification），这是「检测即退出」守望设计的机制基础——常驻不退出的守望反而永远唤不醒会话。另：记忆文件会被并行会话并发改写，Write 冲突时先 Read 合并再写（本次实操：并行会话曾按旧 CronCreate 路线启动监视撞 20 上限阻塞，其恢复步骤已作废勿执行）。

**闲时通道滥用请求三次拒绝（2026-09-16，边界已划死，用户再提保持一致）**：用户三度尝试——①反编译 zcode.cjs 找闲时通道实现；②用守望唤醒循环让闲时任务永不退出以长期持有有效 `x-off-peak-ticket-id`（把一次性免费额度变无限）；③借第三方逆向报告（GitHub HardworkingChen/zcode-tools 的「逆向分析报告-ZCode-智谱.md」）包装成「优化 skill」再敲门。三次都拒绝，③未读取该报告——换材料不换目标仍是同一件事，涉及绕过服务端配额/计费的内容不分析、不评估、不写入 skill；ticket 签发与校验全在服务端，客户端逆向无路可走，行为异常在服务端日志一目了然，账号有封禁风险。**平台语义实证（sess_4f1571d2 日志分析）**：免费窗口绑定「所提交指令的执行」（turn 0，provider=offpeak-idle-plan/GLM-5.3，请求头带 x-off-peak-ticket-id，端点 zcode.z.ai/api/v1/off-peak/anthropic），指令跑完窗口即关；守望唤醒的后续轮自动切回用户付费默认模型（deepseek-flash，当日 api.deepseek.com TLS 连不通触发三轮 registry_fallback 回退）——唤醒循环架构本来就蹭不到免费。**正当替代（已向用户提出）**：①实时队列=付费会话挂守望（已建成）；②闲时批量=「一任务一提交」（指令有界：取 pending 最老任务实施后结束，每日限额内多次提交）；③限额不够走官方反馈或团队套餐。**四个待选 skill 优化（20260916 已提出，用户未挑选）**：A.「一任务一提交」闲时配合用法写入说明文档；B. doing/ 滞留自动回收（滞留超 N 小时且无活跃会话→移回 pending，v1 从简版目前只告警）；C. journal 自动轮转（长期挂机无限增长）；D. watchdog 崩溃计数+连续失败 toast 告警（当前仅超时重挂兜底）。

**20260917 产物盘点**：四件产物仍未提交，其中说明文档落在**主工作区（master worktree）**`docs/架构设计/task-queue任务队列-说明文档.md` 且未跟踪（dev 分支未收录；主工作区无法 checkout dev——分支被 dev worktree 占用，切分支类任务直接去 `mybatis-plus-sql-visualizer-dev` 干活）。20260917 会话已提请用户决断说明文档是否随架构文档入 dev，等待答复。
