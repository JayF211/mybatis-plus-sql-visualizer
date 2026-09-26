# Marketplace verifier 在线口径复现（market-probe）——用法与背景

来源：20260926 T-McpDynamicToolset 审核拒绝复现轮（243 读数与 Marketplace 页面逐字一致）。

## 背景

`./gradlew verifyMarketplace` 覆盖 243/253/262 三目标（五目标中的 251/263 与「任意发行版」
需要本目录的手跑口径）。在线（无 `-offline`、无豁免）才会解析可选插件依赖——这是
Marketplace 审核判 compatility problems 的同口径。

## 复现步骤

1. **拿上传原包**：Marketplace 审核针对的是上传 zip——从 Gitee 发行附件下载并校验字节大小：
   ```bash
   curl -s -x http://127.0.0.1:7890 -L -o build/market-probe/uploaded-<ver>.zip \
     "https://gitee.com/<owner>/<repo>/releases/download/<tag>/<file>.zip"
   ```
2. **拿目标 IDE 发行版**（缓存没有的版本）：
   ```bash
   # data services 查最新（release/eap 分开查）：
   curl -s -x http://127.0.0.1:7890 "https://data.services.jetbrains.com/products/releases?code=IIU&latest=true&type=eap"
   # 直链下载（约 1.4~1.5GB）；EAP zip 顶层无版本目录前缀，解压后 bin/idea.bat 直接可见
   curl -s -x http://127.0.0.1:7890 -L -o <dst>.zip "https://download.jetbrains.com/idea/idea-<build>.win.zip"
   unzip -q <dst>.zip -d <dst-dir>
   ```
3. **手跑 verifier**（verifier-cli 从 gradle 缓存取；JBR java 需 ≥ 目标 class 版本）：
   ```bash
   JBR=$(ls -d F:/gradle-home/caches/9.7.0/transforms/*/transformed/ideaIU-2024.3.7.1-win/jbr | head -1)
   VJAR=$(find F:/gradle-home/caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli \
     -name "verifier-cli-*-all.jar" | head -1)
   "$JBR/bin/java.exe" -Dplugin.verifier.home.dir="$(pwd)/build/verify-home-<tag>" \
     -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 \
     -jar "$VJAR" check-plugin "<插件zip>" \
     -verification-reports-dir "$(pwd)/build/market-probe/reports-<tag>" \
     -runtime-dir "$JBR" -verification-reports-formats plain,html "<IDE发行目录>"
   ```
   - 首跑每目标约 6~11min（在线下载插件依赖数百 MB~GB 级，落到 verifier home 缓存）。
4. **读数**：`<reports>/<IU-*>/plugins/<pluginId>/<ver>/verification-verdict.txt`（Compatible 与否）
   ＋ `compatibility-problems.txt`（问题块全文，与 Marketplace 版本页逐字对齐）。

## 坑位速查

- **JBR 无 javap**：JBR 发行版不带 javap；JDK8 的 javap 读不了 v61+ class——用
  `~/.jdks/openjdk-24.0.1/bin/javap` 等完整 JDK。
- **旧 EAP 报数会随 build 演进消失**：20260923 时 263.5153 报 1 条，263.5701.42 复测
  Compatible——复现 Marketplace 读数必须用**当前审核线的 build**。
- **SpillingKt 形态陷阱**：K2（2.1+）对「try-catch/finally 包裹挂起调用」的 suspend 函数
  无条件生成 `SpillingKt.nullOutSpilledVariable` 引用（243 stdlib 2.0.21 缺该类）——
  catch 内容无关；源码层唯一解=intrinsics 透传＋continuation 包装（见
  McpToolsetSupport.audit 注释与 T-McpDynamicToolset 计划 §三）。
