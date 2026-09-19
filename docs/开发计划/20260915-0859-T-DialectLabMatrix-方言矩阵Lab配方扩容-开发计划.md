# T-DialectLabMatrix 方言矩阵 Lab 配方扩容 — 开发计划

- 日期：2026-09-15 08:59
- 任务编号：T-DialectLabMatrix（第三轮建议 **O-10**，滚动挂账，P2）
- 来源：第三/四轮竞品调研报告——借 DBX Database Lab 的 24 配方结构（钉版本/健康检查/脚本化校验，经 19k star 社区检验）扩本插件方言真实环境矩阵，重点达梦/金仓
- 性质：测试基建（不触产品代码）
- 实施分支：基于 dev 开任务分支 t-dialect-lab
- 验证基线：dev 全量 `./gradlew testShards` 4246/0/0/6 绿

---

## 一、背景与依据

国产方言（达梦/金仓）是本插件的核心差异位之一（能力矩阵中唯一「真机验证」项），但方言矩阵的真实环境验证基建只覆盖五容器：`src/test/resources/docker-compose-dialects.yml`（mysql:8.0.36 / postgres:16.2 / mariadb:11.2 / mssql:2022-CU12 / oracle-free:23.5-slim，钉版本 + 端口避让段），文件注释明确「DM（达梦）/ KingBase（人大金仓）无官方公开镜像——真实环境验证需指向用户自有实例」。消费方 `DialectDockerVerificationTest` 走 `-Dmpvs.dialect.jdbcUrl/user/password/name` 外部实例模式。也就是说：**五家开源库可自动化，两家国产库靠运气**——恰巧后者是卖点所在。

DBX 的 Database Lab 用「配方结构 + 脚本化校验（不起库即可 CI 校验配方一致性）」把 24 个方言环境做成可维护资产。本任务把这套结构搬过来，并把达梦/金仓从「注释里的一句话」变成「有明确取得路径、有验证命令、有诚实标注」的正式配方。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 配方文件 | 五容器服务，钉版本，端口 13306/15432/13307/11433/11521 避让段，凭据仅本地验证环境 | `src/test/resources/docker-compose-dialects.yml` |
| 消费方 | DialectDockerVerificationTest：外部实例模式，`-Dmpvs.dialect.*` 四属性驱动 | `src/test/.../sql/verify/DialectDockerVerificationTest.kt` |
| 文档级验证 | DialectDocGoldenTest（官方文档级，无库验证）在位 | 同目录 |
| 国产库现状 | 仅注释声明无公开镜像；无取得路径、无 load 指引、无验证命令样例 | yml 头注释 |
| 配方一致性校验 | 无（配方漂移只能靠人眼） | — |

## 三、方案

**T-DL-01（镜像可得性调研，决定后续分支）**：逐项核实达梦 DM8 与金仓 KingbaseES 的容器取得路径——官网 tar 包下载（达梦提供 docker 版 tar、金仓提供镜像包，均非 Docker Hub 公开 pull，需要实名/许可）；社区镜像的可用性与许可证风险（记录但不依赖）。产出结论写入本计划 §八：路径 A=镜像可自动化取得（tar load 脚本化）；路径 B=只能手工环境（指引文档化）。**两条路径都交付，只是形态不同。**

**T-DL-02（配方结构升级，借 DBX Lab 三件套）**：
- yml 拆分注释头为规范段：每服务补 `healthcheck`（DBX 配方标配，`pg_isready`/`mysqladmin ping` 同款）；
- 新增 `database-lab/` 目录承载国产库资产：`README.md`（取得路径、版本、端口、凭据、验证命令全量指引）、路径 A 时附 `load-dm8.ps1` / `load-kingbase.ps1`（docker load + compose 片段说明）；
- compose 文件主体保持五容器（能 pull 的自动化），国产库以 `docker-compose.dialects-cn.example.yml` 示例片段提供（路径 A 可直接 up，路径 B 作为手工环境对接模板）。

**T-DL-03（配方一致性脚本化校验，不起库）**：新增纯文本守卫测试 `DockerComposeLabConsistencyTest`——解析 yml 断言：镜像全部钉版本（无 :latest）、端口互不冲突且在避让段、必填环境变量齐全（各库密码/库名）、healthcheck 存在；`database-lab/README.md` 中的端口/凭据与 yml 示例片段一致（防文档漂移）。照抄 DBX「不起库即可 CI 校验配方」的思路，普通 JUnit 可跑。

**T-DL-04（验证命令样例）**：`database-lab/README.md` 给达梦/金仓两条完整的 DialectDockerVerificationTest 命令样例（`-Dmpvs.dialect.name="DM"` / `"KingBase"`，JDBC URL 按两库驱动格式），与五容器既有用法同构。

**T-DL-05（文档同步）**：CHANGELOG [Unreleased] Test 段、变更记录总册行、第四轮报告 §8.4 O-10 状态回填。

## 四、验收标准

1. yml 五容器加 healthcheck 后 compose config 校验通过，DialectDockerVerificationTest 既有用法零回归。
2. 一致性守卫测试绿，且能抓住人为注入的漂移（latest 镜像/端口冲突）——自证有效。
3. 达梦/金仓取得路径结论落 §八；路径 A 时 load 脚本与示例片段在位，路径 B 时 README 指引完整可照做。
4. **方法论红线**：真实验证未跑通前，任何报告/文档只允许写「环境受限未验证」，禁止拿受限环境读数冒充「验证通过」。
5. 全量 test 绿（基线 4246/0/0/6 + 新增守卫用例数），跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 有达梦/金仓实例（或按 README load 出容器）时：各跑一条 DialectDockerVerificationTest 命令，记录方言矩阵抽样结果到验收文档。
2. 五容器 `docker compose up -d` 全套健康，跑一轮既有外部实例验证。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 国产库镜像取得受阻 | 许可/实名/下载渠道限制导致路径 A 走不通 | T-DL-01 先行调研；路径 B 保底（手工指引 + 外部实例模式本就支持） |
| healthcheck 命令方言差异 | mssql/oracle 的探活命令与 mysql 系不同 | 各服务用官方镜像自带工具，逐服务核对文档 |
| 守卫测试过脆 | 对 yml 格式敏感导致无意义红 | 用 yaml 解析而非正则；断言只盯「钉版本/端口/必填 env」三硬约束 |
| 社区镜像许可证风险 | 直接引用社区 DM/KingBase 镜像有分发合规问题 | 记录调研结论但配方只指向官方渠道 |

## 七、实施顺序

```
T-DL-01（镜像调研，定 A/B 路径）→ T-DL-02（配方+database-lab 资产）
→ T-DL-03（一致性守卫）→ T-DL-04（命令样例）→ T-DL-05（文档同步）
→ 真机走查 §五（有环境时）
```

## 八、实施记录（实施后回填；含 T-DL-01 镜像可得性结论）

**2026-09-15 实施完毕（守卫测试 9/9 绿 + docker compose config 双文件校验通过）**

- **T-DL-01 镜像可得性结论（2026-09-15 官网检索）：路径 A 半自动成立**——两库官方均以 **tar 包**分发镜像、均无 Docker Hub 官方 pull：
  - DM8：eco.dameng.com/download/ 提供 Docker 镜像 tar（直链形态 download.dameng.com/eco/dm8/dm8_20250206_x86_rh6_rq_single.tar），实名注册；社区问答确认「找不到时联系达梦商务」；
  - KingbaseES：kingbase.com.cn/download.html 提供 Docker 镜像 tar 精简包（kdb_x86_64_V009R001C002B0014.tar.gz 形态，按 CPU 架构选包）+ 官方 Docker 部署手册（help.kingbase.com.cn），企业版需 License（官网免费申请开发授权）；
  - 社区第三方镜像（Docker Hub huzhihui/kingbase 等）有许可证与分发合规风险——调研记录但不依赖，配方只指向官方渠道（计划 §六 风险 4 约束遵守）。
  - 「半自动」定名：tar 取得必须人工（实名/许可不可脚本化），load/up/验证全部脚本化——比计划的 A/B 二分更精确，两形态资产均交付。
- **T-DL-02**：主配方五服务补 healthcheck（mysqladmin ping / pg_isready / mariadb healthcheck.sh / mssql-tools18 sqlcmd / gvenzl sqlplus SELECT 1，各用官方镜像自带工具）；`database-lab/` 新增 README.md（取得路径/端口/凭据/验证命令/状态台账）+ docker-compose.dialects-cn.example.yml（DM8 15236 / KingBase 15433，镜像名占位待 load 替换）+ load-dm8.ps1 / load-kingbase.ps1（docker load + 后续步骤提示，UTF-8 BOM）。
- **T-DL-03**：`DockerComposeLabConsistencyTest` 9 例全绿——四硬约束（钉版本/端口避让 ≥10000 互不冲突/必填 env/healthcheck）+ README 端口引用一致 + CN 示例端口不与主配方冲突 + 漂移自证三例（latest 镜像/端口冲突/低段端口注入即抓，首跑即抓出本测试自己的期望串前缀笔误——守卫有效性顺带自证）。YAML 解析用 snakeyaml 2.2（新 testImplementation，gradle catalog 登记），非正则。`docker compose config` 主配方与 CN 示例双文件语法校验通过（本机 Docker 29.6.2）。
- **T-DL-04**：README 给 DM（达梦）/KingBase（人大金仓）两条完整外部实例验证命令（方言名与 DialectRegistry 一致），并诚实标注 JDBC 驱动不在 Maven Central 的环境前置（No suitable driver 属环境缺失非用例失败）。
- **方法论红线遵守**：本机未取得两库 tar/实例，真实容器验证维持「环境受限未验证」口径（README 状态台账单列），未拿任何受限读数冒充验证。
