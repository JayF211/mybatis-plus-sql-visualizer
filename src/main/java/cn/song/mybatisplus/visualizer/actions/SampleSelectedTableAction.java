// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SelectedTableSqlTemplateActions.kt 移植
// T-SyncK2JMaster482 R5 增量：LIMIT 值设置页可配（PluginSettings.sampleLimit，默认 10，钳制 1..10000）
package cn.song.mybatisplus.visualizer.actions;

/** 抽样 N 行（R5：LIMIT 值设置页可配，默认 10；范围钳制 1..10000 防 0/负数与误输入全表）：{@code SELECT * FROM <选中表> LIMIT N}。 */
public class SampleSelectedTableAction extends SelectedTableSqlTemplateAction {

    public static final String ACTION_ID = "cn.song.mybatisplus.visualizer.SampleSelectedTable";

    public SampleSelectedTableAction() {
        super(
                "action.cn.song.mybatisplus.visualizer.SampleSelectedTable.text",
                selected -> {
                    int limit;
                    try {
                        limit = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
                                .getState().getSampleLimit();
                    } catch (Throwable t) {
                        limit = 10; // runCatching{...}.getOrDefault(10)
                    }
                    limit = Math.max(1, Math.min(10000, limit)); // coerceIn(1, 10000)
                    return "SELECT * FROM " + selected + " LIMIT " + limit;
                });
    }
}
