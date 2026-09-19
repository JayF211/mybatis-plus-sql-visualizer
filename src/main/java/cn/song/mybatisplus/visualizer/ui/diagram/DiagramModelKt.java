// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/diagram/DiagramModel.kt 移植
package cn.song.mybatisplus.visualizer.ui.diagram;

/**
 * DiagramModel.kt 顶层声明载体（顶层 const 落点）。
 */
public final class DiagramModelKt {

    private DiagramModelKt() {
    }

    /**
     * 列数上限：节点列数超过此值只画主键列（避免图过大撑爆画布）。
     *
     * <p>T-DgmFullCols 起由生成器（实体列裁剪）与富化器（数据源独有列追加）共用一个口径——
     * 两处各自维护会让"实体 40 列 + 库 20 列"这类组合绕过上限。
     */
    public static final int DIAGRAM_MAX_COLUMNS = 50;
}
