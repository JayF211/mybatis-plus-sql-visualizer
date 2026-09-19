// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SelectedTableSqlTemplateActions.kt 移植
package cn.song.mybatisplus.visualizer.actions;

/** 抽样 10 行：{@code SELECT * FROM <选中表> LIMIT 10}。 */
public class SampleSelectedTableAction extends SelectedTableSqlTemplateAction {

    public static final String ACTION_ID = "cn.song.mybatisplus.visualizer.SampleSelectedTable";

    public SampleSelectedTableAction() {
        super(
                "action.cn.song.mybatisplus.visualizer.SampleSelectedTable.text",
                selected -> "SELECT * FROM " + selected + " LIMIT 10");
    }
}
