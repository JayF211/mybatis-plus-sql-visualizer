// T-SyncK2JMaster184 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SqlTemplateGroup.kt 移植
package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.actionSystem.DefaultActionGroup;

/**
 * SQL 快捷模板组（T-PreviewUx-Pack #3 的菜单组，2026-09-23 真机走查修复）。
 *
 * <p><b>为什么需要这个类</b>：{@code <group>} 的 {@code text}/{@code description} 属性<b>不走 bundle 解析</b>。
 * 实测（runIde 沙盒右键菜单）：只写 {@code text="action.cn.song.mybatisplus.visualizer.SqlTemplateGroup.text"}
 * 时菜单项显示<b>原始键</b>；补上 {@code bundle="messages.MpSqlVisualizerBundle"} 后<b>仍然</b>显示原始键
 * （已核对重建后的插件 jar 里该属性确实在位，故不是构建未生效）——而同文件里 {@code <action>} 的
 * {@code text} + {@code bundle} 正常解析。平台文档对「本地化组」给的正规做法是给组一个 {@code class}，
 * 由类在 presentation 里取 bundle 文案。</p>
 *
 * <p><b>为什么继承 {@link DefaultActionGroup}</b>：组内三个动作仍由 plugin.xml 以 {@code <action>} 子元素声明，
 * 平台会把它们注入本实例；{@code DefaultActionGroup} 自带 add/getChildren 与 popup 语义，故不必自己实现子项收集。</p>
 */
public class SqlTemplateGroup extends DefaultActionGroup {

    public SqlTemplateGroup() {
        // Kotlin init 块：templatePresentation.text / description 由 class 取 bundle（组文案不走
        // plugin.xml 的 text 属性解析——见类 KDoc 的走查定论）
        getTemplatePresentation().setText(
                MpSqlVisualizerBundle.message("action.cn.song.mybatisplus.visualizer.SqlTemplateGroup.text"));
        getTemplatePresentation().setDescription(
                MpSqlVisualizerBundle.message("action.cn.song.mybatisplus.visualizer.SqlTemplateGroup.description"));
    }
}
