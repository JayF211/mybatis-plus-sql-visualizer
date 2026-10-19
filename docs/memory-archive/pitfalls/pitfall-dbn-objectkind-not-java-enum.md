---
name: pitfall-dbn-objectkind-not-java-enum
description: DBN 的 ObjectKind 是 type-safe enum 手写类而非 java.lang.Enum，反射读
  enumConstants 恒 null 导致 schema 链静默早退（数据源列/注释全程空转，日志还谎报 "not found in N data
  sources"）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1b521f27-846f-4575-bc54-e17f1cff48f7
---

**DBN（Database Tools）`com.intellij.database.model.ObjectKind` 不是 Java enum**——它是 `Object` 子类 implements `Comparable`、常量是 public static final 字段、成员存 `ourKinds: Map<String,ObjectKind>` 的 type-safe enum 手写类（262.10968.63 字节码实证：`java/lang/Enum` 0 次、无 `$VALUES`/`valueOf`/`ordinal`）。

因此 `objectKindClass.enumConstants` **恒为 null**。`DatabaseSchemaResolver.collectFromDataSource` 里的
`val columnKind = objectKindClass.enumConstants?.firstOrNull{...} ?: return TableMeta(null, emptyMap())`
让整条数据源 schema 链路从未执行过一次，且这条早退零日志，上层只打印 `table=xxx not found in N data sources`——**"代码没查"被报成"表不存在"**。

**Why:** 该 bug 潜伏至今的机制值得记牢：反射访问第三方 API 时对类型形态的假设（"它应该是枚举"）没有真机证明；早退分支无日志；上层回落日志把早退说成业务结论。真机日志里 23 次调用 `scan:` 行 0 次、`hit=true` 0 次、全部 "not found"，而同名表在 information_schema 里真实存在（`mpj_demo.car_device_info`/`station_info`）——**同名 API 的非反射调用一直正常**（`DbTableSpecAdapter:40` / `ExecConsoleContext:40` 直接 `ObjectKind.COLUMN`），这本身就是"字段访问对、enumConstants 错"的反证。

**How to apply:** ① 反射取 DBN 常量一律 `kindClass.getField("COLUMN").get(null)`，别碰 `enumConstants`；② 判"某功能是否真的跑过"看日志里该路径的**成功分支**日志（本 case 的 `scan:` 行）而不是失败分支，失败分支可能来自早退；③ 与 DBX 内部噪音区分：`#c.i.d.m.m.BasicMetaModel - To many roots from database` 其实来自 `SqlImportUtil`（借 BasicMetaModel 的 logger，indy 拼接 `"To many roots from " + kind`），2024.3/2026.2 都有，属平台噪音，别当根因；④ 缺证据时的分辨手法：日志里"该函数末尾必打的 INFO 从未出现"+"无对应 WARN"，即可判定中途无日志早退，再逐一验早退分支的取值（本次即按此锁死 `enumConstants`）。**已修（2026-09-17，dev 分支 `t-dbschemacompletion` 9c6cccd，testShards 4829/0/0/6 绿，未合 master）**：改按静态字段取 + 早退必留 bail 日志；**注意同源第二处缺陷**——查不了（模型未加载）的扫描原会被当成"确认无此表"写入负缓存，数据源晚连接时把涉及表钉住 30s，修好 ObjectKind 也会表现为"还是不补全"，故 `TableMeta` 增 `scanBailed` 区分「查不了」与「没有」；另 `refreshCompletionContext` 只刷自研编辑器/PSI，**不刷原生控制台编辑器 userData**（控制台是 Ultimate 下实际输入面且优先级最高），改补全上下文类功能必须同步它。**跨版本取证与永久守卫（2026-09-17）**：发行版自带 JBR 跑反射检查器，本机 243（IU-243.28141.41）与 262（IU-262.10968.63）结论一致——`ObjectKind.COLUMN enumConstants=null isEnum=false value=column`。**243 同样 null ⇒ 不是 262 回归，是代码假设一错到底**（解释了它为何能潜伏数周）。已把该入口登记进跨版本守卫（`PlatformSignatureChecker` 第 6 项 + `PlatformSignatureCrossVersionTest` 三族实测通过），并踩到一个类路径坑：Database Tools 在 `plugins/DatabaseTools/lib`（不在 `lib` 下），且其类依赖 `lib` 的 `DynamicBundle`——只把 DBN jar 放进 URLClassLoader 会 `NoClassDefFoundError: com/intellij/DynamicBundle`，必须连 `lib/*` 一起收。另：git bash 里手动跑发行版 java 时，`MSYS2_ARG_CONV_EXCL="*"` 会连**路径转换**一起禁用，POSIX 路径进 java.exe 变成不存在的目录（静默全 MISS），要传 Windows 路径就 `cygpath -w`。任务与方案见 `docs/开发计划/20260917-1651-T-DbSchemaCompletion-数据源字段补全修复与落地-开发计划.md`，相关 [[pitfall-log-first-not-code-reasoning]]、[[idea-262-platform-jar-verify]]。
