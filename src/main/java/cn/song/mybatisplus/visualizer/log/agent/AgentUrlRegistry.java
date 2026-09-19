package cn.song.mybatisplus.visualizer.log.agent;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * T-PS5-04：推断用 agent 连接 URL 的<b>轻量持有者</b>（应用级单例，零线程零监听）。
 *
 * <p>为什么独立存在：方言智能推断（{@code cn.song.mybatisplus.visualizer.sql.DialectRegistry.effective}）
 * 与设置页推断状态行（{@code PluginSettingsPanel.refreshInferredLabel}）读 agentUrl 时原走
 * {@code AgentManager.getInstance(project)}——首次调用即<b>实例化项目级服务</b>，连带其 SETTINGS 监听
 * 常驻（默认开智能推断的项目「预览一次即驻留」，即便增强捕获从未使用）。本持有者只存
 * 「项目 → 最近一次 agent 连接 URL」映射，读它不再触发任何服务实例化。
 *
 * <p>写入点（唯一写方 {@link AgentManager}）：会话建立（attach 成功 / premain 回连）、URL 首次捕获、
 * 会话摘除/关闭/项目销毁——均以 {@code AgentManager.lastConnectionUrl()} 的结果回写，<b>语义与解耦前
 * 逐字一致</b>（活跃会话优先，全离线回落最近有 URL 的会话）。
 *
 * <p>键为项目<b>弱引用</b>（{@link WeakHashMap}）：多项目同开时各项目方言推断仍按各自会话 URL，不互相覆盖；
 * 项目关闭后条目随 GC 回收，不引入跨项目残留（与 AgentManager 项目级服务同生命周期口径）。
 *
 * <p>master 的 {@code object AgentUrlRegistry} 的 Java 承载（全 static + {@code Collections.synchronizedMap}）。</p>
 */
public final class AgentUrlRegistry {

    /** 项目 → 最近一次 agent 连接 URL（弱键，防项目关闭后残留）。 */
    private static final Map<Project, String> URLS =
            Collections.synchronizedMap(new WeakHashMap<Project, String>());

    private AgentUrlRegistry() {
    }

    /** 写/清（url 为 null 即移除该项目条目）。由 {@link AgentManager} 在会话生命周期点调用。 */
    public static void set(@Nullable Project project, @Nullable String url) {
        if (project == null) {
            return;
        }
        if (url == null || url.isBlank()) {
            URLS.remove(project);
        } else {
            URLS.put(project, url);
        }
    }

    /** 读最近一次 agent 连接 URL（无会话/未捕获/项目为 null → null，推断层回落数据源 URL）。 */
    @Nullable
    public static String lastConnectionUrl(@Nullable Project project) {
        return project == null ? null : URLS.get(project);
    }

    /** 测试缝：清空全部条目（用例隔离；生产链路无调用点——清理走 {@link #set} 传 null）。 */
    public static void clearAllForTest() {
        URLS.clear();
    }
}
