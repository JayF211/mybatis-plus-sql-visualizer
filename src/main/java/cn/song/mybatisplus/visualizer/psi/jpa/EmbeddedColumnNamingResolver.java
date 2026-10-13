package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.config.PluginSettings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import org.jetbrains.annotations.Nullable;

/**
 * 嵌入列命名约定的解析入口（T-EmbeddedColPrefix 方案 A+B）。
 *
 * <p>优先级：<b>显式设置 &gt; 工程配置探测 &gt; 默认 {@link EmbeddedColumnNaming#NONE}</b>。</p>
 *
 * <p>默认（AUTO + 探测不中）取 NONE 而非 COMPONENT_PATH，是为了让 JPA 默认工程与既有用例逐字不回归；
 * 探测命中（工程里确实声明了组件路径命名策略）才改口径。设置项的存在则保证探测判不出的场景
 * （自定义 NamingStrategy 类、非标准配置键）用户仍能显式纠正。</p>
 */
public final class EmbeddedColumnNamingResolver {

    private EmbeddedColumnNamingResolver() {
    }

    /**
     * 解析当前应使用的嵌入列命名约定。
     *
     * @param project 宿主工程（用于配置探测）；为 null 时跳过探测，只认显式设置
     * @param anchor  正在解析的实体文件——探测范围收敛到它<b>所在模块</b>（命名策略是每个持久化单元
     *                的事，多模块工程里各模块配置互不影响）；null 时按整个工程扫
     */
    public static EmbeddedColumnNaming resolve(@Nullable Project project, @Nullable VirtualFile anchor) {
        String configured;
        try {
            configured = PluginSettings.getInstance().getState().getEmbeddedColumnNaming();
        } catch (Throwable ignored) {
            configured = null;
        }
        if (PluginSettings.EMBEDDED_NAMING_NONE.equals(configured)) {
            return EmbeddedColumnNaming.NONE;
        }
        if (PluginSettings.EMBEDDED_NAMING_COMPONENT_PATH.equals(configured)) {
            return EmbeddedColumnNaming.COMPONENT_PATH;
        }
        // AUTO（或取值不认识）：探测工程配置，判不出按 NONE
        if (project != null && HibernateNamingConfigProbe.projectUsesComponentPath(project, anchor)) {
            return EmbeddedColumnNaming.COMPONENT_PATH;
        }
        return EmbeddedColumnNaming.NONE;
    }

    /** {@link #resolve(Project, VirtualFile)} 的无锚文件形态（Kotlin 默认参数 {@code anchor = null}）。 */
    public static EmbeddedColumnNaming resolve(@Nullable Project project) {
        return resolve(project, null);
    }
}
