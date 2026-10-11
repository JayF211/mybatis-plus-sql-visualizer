package cn.song.mybatisplus.visualizer.xml;

/**
 * 条件求值结果三态（R30 探镜）：面板条件行按本轮渲染标注。
 *
 * <p>TRUE / FALSE = 该 {@code <if>} 本轮被走到且判定；UNEVALUATED = 未被走到（假 if 体内层 /
 * choose 短路后的其余 when）——探针只读判定结果，不改变勾选与渲染行为。
 */
public enum ConditionOutcome { TRUE, FALSE, UNEVALUATED }
