package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.project.Project

/**
 * 取项目级 [SqlLogManager]，**在发起服务创建之前**先判 [Project.isDisposed]。
 *
 * ## 为什么不能只靠 `runCatching`（T-ST-02b2 定论，勿「优化」回去）
 *
 * 平台服务容器**一次创建失败即把该服务在该项目实例上永久毒化**：`LazyInstanceHolder` 的异常表是
 * `catch Throwable`（无 `CancellationException` 过滤），catch 后无条件缓存 `CannotInitialize`，
 * 此后每次取用永久重抛同一异常。因此 `runCatching { project.getService(...) }` 只吞掉**这一次**的结果，
 * **阻止不了中毒**——它仍然发起了那次注定失败的创建。而项目处于「关闭中 / 测试框架
 * temporarily-disposed 窗口」时，`getService` 会抛 `AlreadyDisposedException`
 * （服务构造期订阅 `project.messageBus` → `MessageBusImpl.checkOwnerDisposed` 判 `owner.isDisposed`）。
 *
 * ## 实测后果（2026-09-22，本轮修复的 flake 根因）
 *
 * `testShards` 同片内**任一**调用点在窗口内发起失败创建后，同片后续**所有**取用点都拿到
 * `Container is already disposed` 而成片变红（现场：`AgentManagerIntakeGateTest` 15 例 + 邻居 5 例，
 * 隔离跑全绿）。light 测试项目片内跨类复用同一 `ProjectImpl`，故中毒会跨测试类传播。
 *
 * @return null = 项目正在关闭或处于 temporarily-disposed 窗口——**调用方必须容忍**（跳过本次动作，
 *         不得因此报错或改语义）。服务本身创建失败（非 disposed 路径）同样返回 null。
 *
 * 同款守卫先例：`SectionConfigurable`（T-ST-02/T-ST-02b2，含字节码级论证）。
 * 取用点覆盖由 `SqlLogManagerLookupGuardTest` 扫描守卫锁定（新增直接取用即转红）。
 */
internal fun sqlLogManagerOrNull(project: Project): SqlLogManager? {
    if (project.isDisposed) return null
    return runCatching { project.getService(SqlLogManager::class.java) }.getOrNull()
}
