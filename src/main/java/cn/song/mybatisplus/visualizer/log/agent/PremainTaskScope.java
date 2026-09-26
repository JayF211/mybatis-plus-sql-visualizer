// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/PremainTaskScope.kt 移植
package cn.song.mybatisplus.visualizer.log.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * T-GradleTaskInjectScope（暂占 O-73）：premain 注入范围闸门——按「任务语义」判定外部系统
 * （Gradle/Maven）运行配置要不要注入 {@code -javaagent}（路线 A，用户拍板）。
 *
 * <p>缺陷（计划文档 §一）：注入经 {@code PremainRunConfigurationExtension} 写进<b>所有</b> Java 运行配置的
 * JavaParameters，Gradle 集成再把参数经 init script 传播到 Gradle fork 出的 JavaForkOptions 任务
 * JVM——{@code runIde}、{@code test} 等 IDE/测试任务的 fork JVM 也被挂上 agent。本判定把范围收窄为
 * 「真实应用运行任务才注入」。
 *
 * <p>纯函数、<b>零平台依赖</b>（同 {@link PremainInjection} 的设计理由：表驱动 headless 全覆盖；且测试源码
 * 零 {@code com.intellij} 引用才进 testFast 快桶，见 buildSrc mpv-testing 划桶规则）。平台探针
 * （配置是否 external-system、读 taskNames）在 {@code PremainRunConfigurationExtension} 薄壳里，
 * 探针异常按既有行为注入（零突变）。
 *
 * <p>语义方向（计划文档 §二）：<b>宁 skip 勿错注入</b>——skip 的代价是该次运行回落 attach/桥链
 * （既有零劣化降级路径），错注入的代价是 agent 污染不相干 JVM。故多任务组合任一命中 skip 族
 * 即 skip，大小写不敏感（Gradle 任务名虽大小写敏感，IDE 生成的清单是规范形态，宽松匹配只会
 * 多 skip 不会漏 skip）。
 *
 * <p><b>为什么不收窄 {@code isApplicableFor}</b>：在那里收窄（如只认 external-system）挡不住「external-system
 * 配置跑测试任务」这个真形态，反而会把非 Java 运行器链路上的判定时序搞乱；且本判定需要
 * taskNames，只有 updateJavaParameters 时机上配置状态是齐的。
 */
public final class PremainTaskScope {

    private PremainTaskScope() {
    }

    /** 外部系统配置的任务列表命中测试族（Gradle test/check/verification 族、Maven surefire 等）。 */
    public static final String REASON_TEST_TASK = "external-test-task";

    /** 外部系统配置的任务列表命中 IDE/构建验证族（runIde、prepareSandbox、verifyPlugin* 等）。 */
    public static final String REASON_IDE_TASK = "external-ide-task";

    /**
     * 测试族（命中即 skip）：fork 出的是测试 JVM，agent 挂上去只产生污染。
     *
     * <p>逐条依据（闭集，每条可单独反向验证；扩展须先在此登记理由）：
     * <ul>
     * <li>{@code test}：Gradle Java 插件测试生命周期任务（含各子模块 {@code :m:test}，归一化后同形）；</li>
     * <li>{@code check}：Gradle 校验生命周期，聚合 test；</li>
     * <li>{@code verification}：Gradle 任务组名（「verification」组即全部校验任务），按名防呆收录；</li>
     * <li>{@code verify}：Maven 生命周期 phase，聚合 failsafe 集成测试；</li>
     * <li>{@code build}：Gradle 全量生命周期 = assemble + check（传递 fork 测试 JVM）；</li>
     * <li>{@code surefire} / {@code failsafe}：Maven 测试插件 goal 前缀（{@code surefire:test} 归一化后为 {@code test}
     *     已命中，裸 goal 名防呆收录）；</li>
     * <li>{@code integration-test} / {@code integrationtest}：Maven failsafe goal（{@code failsafe:integration-test}）
     *     与 Gradle 常见自定义集成测试任务（camelCase 形态）；</li>
     * <li>{@code connectedcheck} / {@code connectedandroidtest}：Android instrumented 测试族。</li>
     * </ul>
     */
    private static final Set<String> TEST_TASKS = Set.of(
        "test", "check", "verification", "verify", "build",
        "surefire", "failsafe", "integration-test", "integrationtest",
        "connectedcheck", "connectedandroidtest");

    /** 测试族前缀：{@code test*} 变体（Android {@code testDebugUnitTest}/{@code testReleaseUnitTest}、{@code testClasses} 等）。 */
    private static final String TEST_TASK_PREFIX = "test";

    /**
     * IDE/构建验证族（命中即 skip）：IntelliJ Platform Gradle Plugin 的 IDE 沙盒/构建验证任务，
     * fork 的是 IDE 沙盒或构建工具 JVM——agent 属于用户的应用，不属于插件开发工具链。
     *
     * <p>逐条依据：{@code runIde}/{@code runIde2}（新旧两代 gradle-intellij-plugin 的 IDE 沙盒启动）、
     * {@code buildSearchableOptions}（fork 沙盒 IDE 构建搜索索引）、{@code prepareSandbox}/{@code prepareUiSandbox}
     * （IPGP 2.x 沙盒装配）、前缀 {@code verifyplugin*}（verifyPlugin 及 2.x 拆分的
     * verifyPluginStructure/verifyPluginConfiguration 等）。
     */
    private static final Set<String> IDE_TASKS = Set.of(
        "runide", "runide2", "buildsearchableoptions", "preparesandbox", "prepareuisandbox");

    /** IDE/构建验证族前缀：{@code verifyPlugin*} 家族（含 IPGP 2.x 拆分出的各验证子任务）。 */
    private static final String IDE_TASK_PREFIX = "verifyplugin";

    /** 范围判定结果（Kotlin sealed interface；同文件嵌套实现隐式许可，形态对齐 {@link PremainInjection#Decision}）。 */
    public sealed interface Decision {

        /** 注入：非 external-system 配置（行为零突变面），或任务列表无 skip 族命中。Kotlin {@code object} → 单例。 */
        final class Inject implements Decision {

            public static final Inject INSTANCE = new Inject();

            private Inject() {
            }
        }

        /** 跳过：{@link #reason} 取本对象常量（{@link PremainTaskScope#REASON_TEST_TASK} / {@link PremainTaskScope#REASON_IDE_TASK}），仅用于日志与测试断言。 */
        record Skip(String reason) implements Decision {
        }
    }

    /**
     * 范围判定（纯函数）。{@code isExternalSystemConfiguration} = 配置是否 Gradle/Maven 这类外部系统
     * 运行配置（探针在调用方薄壳）；非外部系统配置<b>恒 {@link Inject}</b>——Spring Boot 等普通
     * Java 应用运行配置行为与今日逐字节一致（路线 A 红线）。
     *
     * <p>任务名归一化：trim + 小写 + 取最后一个 {@code :} 后段再 trim——{@code :app:test}→{@code test}、
     * {@code surefire:test}→{@code test}、{@code :plugins:runIde}→{@code runide}（末段 trim 兜底 {@code : m: TEST }
     * 这类人写带空格形态；规范任务名无空格，该分支只为判定核纯函数态下边界可证）。任一任务命中
     * skip 族即 skip（{@code clean bootRun} 保留注入：{@code clean} 不在两族；{@code test bootRun} 组合按 skip
     * 处理——组合运行同样 fork 测试 JVM，宁可回落 attach）。
     */
    public static Decision decide(boolean isExternalSystemConfiguration, List<String> taskNames) {
        if (!isExternalSystemConfiguration) {
            return Decision.Inject.INSTANCE;
        }
        List<String> normalized = new ArrayList<>();
        for (String taskName : taskNames) {
            if (taskName == null) {
                continue;
            }
            String s = taskName.trim().toLowerCase(Locale.ROOT);
            int colon = s.lastIndexOf(':');
            s = (colon >= 0 ? s.substring(colon + 1) : s).trim();
            if (!s.isEmpty()) {
                normalized.add(s);
            }
        }
        // 判定顺序即族优先序（测试族在前）：两族同现（病态组合）时原因码取测试族，行为同为 skip。
        // 【反向验证实验 A 摘除点】测试族分支（摘除后测试族行必红）
        for (String s : normalized) {
            if (TEST_TASKS.contains(s) || s.startsWith(TEST_TASK_PREFIX)) {
                return new Decision.Skip(REASON_TEST_TASK);
            }
        }
        // 【反向验证实验 B 摘除点】IDE/构建验证族分支（摘除后 IDE 族行必红）
        for (String s : normalized) {
            if (IDE_TASKS.contains(s) || s.startsWith(IDE_TASK_PREFIX)) {
                return new Decision.Skip(REASON_IDE_TASK);
            }
        }
        return Decision.Inject.INSTANCE;
    }
}
