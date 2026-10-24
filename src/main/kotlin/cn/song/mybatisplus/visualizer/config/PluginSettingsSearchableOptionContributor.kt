package cn.song.mybatisplus.visualizer.config

import com.intellij.ide.ui.search.SearchableOptionContributor
import com.intellij.ide.ui.search.SearchableOptionProcessor

/**
 * T-SettingsSearchA：把插件设置项喂进 IDE 自带设置搜索索引（Settings 对话框左上搜索框）。
 *
 * ## 为什么需要运行时 Contributor
 *
 * 平台设置搜索读两类来源：插件 jar 内的 `search/searchableOptions.xml`（构建期由
 * `buildSearchableOptions` fork IDE 遍历 UI 生成），以及运行期 `search.optionContributor`
 * EP 收集（`SearchableOptionsRegistrarImpl.doInitialize` 逐个调 `contribute(processor)`，
 * 243 字节码实证）。本仓 `build.gradle.kts` 关着 `buildSearchableOptions`（Windows 中文
 * 环境跑该任务报 `"Locale must be default"`，注释有实证），故走 EP 这条路——不动构建，
 * 也就不会碰到 locale 失败。
 *
 * ## 为什么不需要改 9 个 Configurable
 *
 * 查询侧 `filterById` 对每个候选判 `instanceof SearchableConfigurable` 再比 `getId()`；
 * 本仓设置页全用 `instance=` 注册，平台把实例包进 `ConfigurableWrapper`，而该类**本身
 * 实现 `SearchableConfigurable`**，`getId()` 优先返回 `ConfigurableEP.id`（243 字节码实证：
 * `myEp.id != null ? myEp.id : ...`）。故 plugin.xml 里已声明的 id 天然满足门二，无需给
 * `SectionConfigurable` 等类补 `getId()`。
 *
 * ## 参数语义（对照 243 字节码 LocalVariableTable）
 *
 * `addOptions(text, path, hit, configurableId, configurableDisplayName, applyStemming)`：
 *  - `text`＝被索引文本（用户搜的词来自这里）；
 *  - `path`＝设置路径字符串，可为 null（平台自带的 `PluginSearchableOptionContributor`
 *    就传 null）；本实现传 null，命中后由 configurableId 定位页面；
 *  - `hit`＝命中时展示给用户的说明文本；
 *  - `configurableId`＝EP id，平台用它把命中映射回设置树节点（`filterById` 比对 `getId()`）；
 *  - `configurableDisplayName`＝搜索结果里显示的所属页面名；
 *  - `applyStemming`＝是否做词干化（false＝保留原词，中文无词干化需求）。
 *
 * 数据源是 [PluginSettingsFolderConfigurable.ideSearchEntries]——与插件设置页内搜索
 * 同一份清单，两套搜索天然同步。
 */
class PluginSettingsSearchableOptionContributor : SearchableOptionContributor() {

    override fun processOptions(processor: SearchableOptionProcessor) {
        for (entry in PluginSettingsFolderConfigurable.ideSearchEntries()) {
            processor.addOptions(
                entry.label,
                null,
                entry.tooltip,
                entry.configurableId,
                entry.pageDisplayName,
                false,
            )
        }
    }
}
