// T-SyncK2JMaster184 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogManagerLookup.kt 移植
// （master 该文件为纯顶层函数，按仓内惯例落 SqlLogManagerLookupKt 门面类，同 LiteralParamRecoveryKt 形态）
package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.project.Project;

/**
 * 取项目级 {@link SqlLogManager}，<b>在发起服务创建之前</b>先判 {@link Project#isDisposed()}。
 *
 * <h2>为什么不能只靠 {@code runCatching}（T-ST-02b2 定论，勿「优化」回去）</h2>
 *
 * <p>平台服务容器<b>一次创建失败即把该服务在该项目实例上永久毒化</b>：{@code LazyInstanceHolder} 的异常表是
 * {@code catch Throwable}（无 {@code CancellationException} 过滤），catch 后无条件缓存 {@code CannotInitialize}，
 * 此后每次取用永久重抛同一异常。因此 {@code runCatching { project.getService(...) }} 只吞掉<b>这一次</b>的结果，
 * <b>阻止不了中毒</b>——它仍然发起了那次注定失败的创建。而项目处于「关闭中 / 测试框架
 * temporarily-disposed 窗口」时，{@code getService} 会抛 {@code AlreadyDisposedException}
 * （服务构造期订阅 {@code project.messageBus} → {@code MessageBusImpl.checkOwnerDisposed} 判 {@code owner.isDisposed}）。
 *
 * <h2>实测后果（2026-09-22，本轮修复的 flake 根因）</h2>
 *
 * <p>{@code testShards} 同片内<b>任一</b>调用点在窗口内发起失败创建后，同片后续<b>所有</b>取用点都拿到
 * {@code Container is already disposed} 而成片变红（现场：{@code AgentManagerIntakeGateTest} 15 例 + 邻居 5 例，
 * 隔离跑全绿）。light 测试项目片内跨类复用同一 {@code ProjectImpl}，故中毒会跨测试类传播。
 *
 * <p>@return null = 项目正在关闭或处于 temporarily-disposed 窗口——<b>调用方必须容忍</b>（跳过本次动作，
 *         不得因此报错或改语义）。服务本身创建失败（非 disposed 路径）同样返回 null。
 *
 * <p>同款守卫先例：{@code SectionConfigurable}（T-ST-02/T-ST-02b2，含字节码级论证）。
 * 取用点覆盖由 {@code SqlLogManagerLookupGuardTest} 扫描守卫锁定（新增直接取用即转红）。
 */
public final class SqlLogManagerLookupKt {

    private SqlLogManagerLookupKt() {
    }

    /**
     * {@code sqlLogManagerOrNull} 的语义与返回约定见类注释；@return null = 项目正在关闭或处于
     * temporarily-disposed 窗口（调用方必须容忍），服务创建失败同样返回 null。
     */
    public static SqlLogManager sqlLogManagerOrNull(Project project) {
        if (project.isDisposed()) {
            return null;
        }
        try {
            return project.getService(SqlLogManager.class);
        } catch (Throwable t) {
            return null; // runCatching { ... }.getOrNull() 等价
        }
    }

    /**
     * <b>只读</b>取用：仅返回<b>已经创建</b>的 {@link SqlLogManager}，<b>绝不发起创建</b>——读路径一律用这个。
     *
     * <h2>为什么读路径必须用它（T-ST-02b2 第二层，2026-09-22 实证）</h2>
     *
     * <p>{@link #sqlLogManagerOrNull(Project)} 的 {@code isDisposed} 判定<b>不足以</b>挡住测试框架的
     * temporarily-disposed 窗口：窗口内 {@code project.isDisposed} 为 <b>false</b>（本仓诊断行实测组合就是
     * {@code project.isDisposed=false :: Container is already disposed}），而 {@code getService} 仍会因
     * {@code messageBus} owner 已 disposed 而失败 → 平台缓存 {@code CannotInitialize} → <b>该服务在本 JVM 内永久毒化</b>。
     * 既然「判状态」判不准，就在<b>结构上不发起创建</b>：{@code getServiceIfCreated} 只读已建实例、不触碰初始化路径，
     * 故<b>无论项目处于何种窗口都不会毒化</b>。
     *
     * <p>语义：返回 null ＝ 该服务尚未创建。对读路径而言这天然等价于「功能未启用 / 无监听 / 无条目」——
     * 因为服务是本插件自己按需创建的，未创建即意味着相关功能从未被使用过。
     *
     * <p>需要<b>写</b>共享状态（如设置联动触发自动捕捉、启动期自动捕捉）的调用点仍用
     * {@link #sqlLogManagerOrNull(Project)}，它们必须真的创建服务；那些点位的 disposed 守卫保留
     * （真实 IDE 关闭路径 {@code isDisposed} 为真，可挡住）。
     */
    public static SqlLogManager sqlLogManagerIfCreated(Project project) {
        if (project.isDisposed()) {
            return null;
        }
        try {
            return project.getServiceIfCreated(SqlLogManager.class);
        } catch (Throwable t) {
            return null; // runCatching { ... }.getOrNull() 等价
        }
    }
}
