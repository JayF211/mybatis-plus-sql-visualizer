package cn.song.mybatisplus.visualizer.psi.jpa;

/**
 * 查询承载类别（T-J12 / T-J21）。
 *
 * <p>区分 HibernateTemplate 家族不同首参形态：SqlPreviewService 按类别派发
 * （NAMED_QUERY → NamedQueryLocator.findByQueryName 按名回查真实 JPQL；QBC / ENTITY_OP
 * 无 SQL 字符串，由后续里程碑接线），本定位器只备好类别数据。
 */
public enum DaoQueryKind {
    /** HQL/SQL 字符串承载（默认）：hql 字段即真实查询文本。 */
    HQL,

    /**
     * 命名查询承载：首参是查询名字符串而非 HQL（如
     * {@code hibernateTemplate.findByNamedQuery("UserEntity.findByStatus")}），hql 字段存查询名。
     * SqlPreviewService 接线后续做：按名回查 NamedQueryLocator.findByQueryName 解析真实 JPQL。
     */
    NAMED_QUERY,

    /**
     * QBC 形态（{@code executeFind}，DetachedCriteria 实参，无 SQL 字符串）——
     * 占位类别，本定位器不认领（locate 返回 null），后续里程碑处理；不得误抛。
     * 注：{@code findByCriteria} 同属 QBC 形态，但按 T-J21 名单归入 ENTITY_OP。
     */
    QBC,

    /**
     * HibernateTemplate 实体操作（save/update/delete/get/load 等，无 SQL 字符串）——T-J21 名单。
     * SqlPreviewService 批量派发接线由 T-J1 里程碑做；本定位器不认领（locate 返回 null），
     * 这些方法名也不会绕过现有 HQL 内容启发（仅登记类别）。
     */
    ENTITY_OP,
}
