package cn.song.mybatisplus.visualizer.ui.diagram;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * F-xx：JOIN 关系图结构化中间模型。
 *
 * 替代 PlantUML 文本作为 JoinDiagramGenerator 的输出与 DiagramRenderer 的输入之间的桥梁。
 * 纯数据类，零外部依赖。EntityNode / ColumnEntry / RelationshipLine 为本包顶层类。
 */
public final class DiagramModel {

    @NotNull
    public final List<EntityNode> entities;
    @NotNull
    public final List<RelationshipLine> relationships;

    public DiagramModel(@NotNull List<EntityNode> entities, @NotNull List<RelationshipLine> relationships) {
        this.entities = entities;
        this.relationships = relationships;
    }

    @NotNull public List<EntityNode> getEntities() { return entities; }
    @NotNull public List<RelationshipLine> getRelationships() { return relationships; }

    /** 无实体时返回 true（调用方可据此决定是否展示标签页）。 */
    public boolean isEmpty() {
        return entities.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiagramModel)) return false;
        DiagramModel that = (DiagramModel) o;
        return entities.equals(that.entities) && relationships.equals(that.relationships);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entities, relationships);
    }

    @Override
    public String toString() {
        return "DiagramModel{entities=" + entities + ", relationships=" + relationships + '}';
    }
}
