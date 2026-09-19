# T-UpdateZipBounds 更新包解压有界化加固 — 开发计划

- 日期：2026-09-17 01:54
- 任务编号：T-UpdateZipBounds（第六轮新建议 **O-29**，P3；立项口径据实修正：原表述「脚本包导入有界解压」——本插件无脚本包导入面，dev 实测唯一解压面是**插件自更新包**（update/DynamicInstallSupport 与 PluginUpdateRunner），落点修正为「自更新 zip 解压防御加固」）
- 来源：第六轮报告 §7 DBX 吸收清单 **#32**——DBX `b0d30e6`：SQL ZIP 四常量有界解压（10k 分包/1MiB manifest/4GiB 总量/1GiB 单包）+ `entry.take(declared+1)` 防 zip 头谎报（zip 读取器不按声明大小截断解压输出）+ manifest.json 分包清单
- 性质：更新通道防御性加固（zip bomb/头谎报是解压面的经典攻击面，即使来源是自家发行通道也应防御）
- 实施分支：合流后自 master 拉任务分支 t-update-zip-bounds（前置项 T-MergeDev2Master）
- 验证基线：合流后 master 全量 testShards 绿

---

## 一、背景与依据

本插件从 Gitee 抢先体验通道下载内测 zip 并本地解压安装（DynamicInstallSupport/PluginUpdateRunner）。解压面的两个经典风险与「来源可信」正交：①zip bomb（高压缩比层层嵌套撑爆磁盘/内存）；②条目头声明大小与实际不符（谎报小头解出巨量字节——JVM 内置 zip 读取器不按声明大小截断输出）。DBX `b0d30e6` 给了最小防御形态：总量/单包/条目数三重常量上限 + 逐条目 `take(declared+1)` 截断读取 + 超限快速失败。本插件更新包结构固定（插件 jar+说明物），常量可按实际包体收紧，改动面小、纯防御。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 解压面 | 自更新包下载后本地解压安装，ZipInputStream/ZipFile 使用在位 | `update/DynamicInstallSupport.kt`、`update/PluginUpdateRunner.kt` |
| 有界性 | 未知——是否校验条目数/解压总量/头声明一致性需实施时首查 | 同上 |
| 包体形态 | 结构固定（插件 zip：jar + 元数据），体积量级 MB 级 | 发版管线产物 |
| 消费时序 | 下载（网络边界已有既有校验）→ 解压 → 安装 | 同上 |

## 三、方案

**T-UZ-01（现状核实）**：通读两条解压路径，登记现状（已有哪些校验、缺哪些），确定加固点与不破坏既有安装流程的插入位。

**T-UZ-02（有界常量+截断读取）**：解压处加三重上限常量（条目数/单条目解压字节/累计解压字节，按实际包体量级 ×10 余量定值并注明依据）+ 逐条目按「头声明值 +1 字节」截断读取（读取字节数超过声明即判定头谎报，快速失败）+ 累计超限快速失败；失败路径清理半解压产物并走既有更新失败反馈（可读错误，不留脏目录）。

**T-UZ-03（表驱动测试）**：正常包通过；头谎报（声明小实际大）拦截；条目数超限拦截；累计超限拦截；失败清理断言（无残留目录）。测试用程序化构造 zip，不依赖真实发版产物。

**T-UZ-04（文档回填）**：CHANGELOG [Unreleased] Changed（更新包解压防御加固）、变更记录行、第六轮报告 O-29 状态回填；脚本包导入不存在面在报告中已定案，此处一并备注防后续误立项。

## 四、验收标准

1. 四类表驱动（正常/头谎报/条目超限/累计超限）全绿，失败路径无残留。
2. 既有真实内测包（当前最新 beta 产物）解压安装行为不变（兼容性断言）。
3. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

随下次内测发版走一遍 Gitee 通道下载→安装全链（真实包过新校验）；伪造超限包验证拒绝与提示可读。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 常量过紧误杀未来合法包 | 发版包体积随功能增长 | 常量取当前量级 ×10 并集中在单一定义点，超限错误信息含实际值便于调参 |
| 下载中断与解压失败的路径交织 | 半下载半解压态清理 | 只加固解压段；失败清理走既有更新失败反馈，不新造状态机 |
| 平台 zip 实现差异 | JDK ZipInputStream 与 ZipFile 行为差异 | 以实施时实际使用的 API 为准写截断逻辑，两条路径都覆盖或收敛为一条 |

## 七、实施顺序

```
T-UZ-01（现状核实）→ T-UZ-02（常量+截断+清理）→ T-UZ-03（表驱动测试）→ T-UZ-04（文档回填）
```

## 八、实施记录（实施后回填）

**已实施（2026-09-17，dev 分支随「2.2 五计划 + T-PerfSweep4 + 尾巴项」批次）**：

- **T-UZ-01（现状核实）**：插件侧无全量解压——真解压由平台执行（动态装载 `installAndLoadDynamicPlugin` / 重启安装）；自有读面两处=PluginUpdateRunner.isValidZip（仅可打开判定）与 DynamicInstallSupport.validateArtifact（plugin.xml 读取，readBytes 无界 + 内层扫描已有 MAX_INNER_ENTRIES=2000）。**落点修正**：按计划预案把「解压处加固」落为「平台解压器之前的有界守卫」——异常包（zip bomb/谎报头）在进入平台解压前拦截。
- **T-UZ-02**：新增 `update/UpdateZipGuard`——中央目录三重上限（MAX_ENTRIES=64 / 单条目 64MB / 累计 128MB；实测 beta.4 包 5 条目/7.3MB/最大 3.4MB ×10 余量，超限信息含实际值）+ 逐条目有界读取：读超「声明+1」与读取异常（size/CRC 不一致）**双路**判头谎报（谎报暴露形态因 JDK 实现而异——本机 JBR17 实测为读超声明命中，注释载明）+ 声明缺失（-1）按单条目上限截断读。接线 PluginUpdateRunner.downloadAndInstall（isValidZip 之后、install 之前，拒绝走既有 InstallResult.Failed 可读反馈不触安装）；validateArtifact 的 plugin.xml 读取同步有界（MAX_DESCRIPTOR_XML_BYTES=1MB，KB 级 ×100 余量）。失败路径无残留（临时文件既有 finally 清理）。
- **T-UZ-03**：`UpdateZipGuardTest` 6 例——正常包（真实布局 lib/plugin.jar+README）通过 / 头谎报拦截（手工构造 zip 字节：CEN usize 声明 16B 实际 64KB deflate，raw deflate nowrap——默认 Deflater 是 zlib 包装流 zip 内非法，踩坑留档）/ 条目数 65 超限 / 单条目声明超限 / 三条目 45MB×3 累计超限（中央目录阶段拒，不读流）/ 编排层接线（守卫拒绝不触 install、失败信息含拒绝原因）。程序化构造全部形态，不依赖真实发版产物。
- **T-UZ-04**：CHANGELOG [Unreleased] Changed、变更记录行、第六轮报告 §8 状态回填（随批次统一落）；脚本包导入不存在面已在立项时定案，防误立项备注维持。
- 门禁：UpdateZipGuardTest + PluginUpdateRunnerTest（既有 12 例含守卫插入后全绿）+ DynamicInstallSupportTest 定向绿；真实内测包兼容断言=normalPackagePasses 布局同构 + 既有 happy path（单条目 fixture）过闸；全量随批次终门禁。真机走查（真实 Gitee 包过闸）留人工。
