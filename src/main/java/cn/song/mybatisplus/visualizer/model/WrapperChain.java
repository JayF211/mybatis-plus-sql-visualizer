package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;
/**
 * Wrapper 调用链模型（PSI 解析产物 → converter 输入）。
 *
 * 从源码中提取出"Wrapper 类型 + 实体类型 + 有序的方法调用列表"，
 * converter 层据此构建 SqlStatement。
 *
 * @param wrapperType           Wrapper 类型
 * @param entityClass           泛型实体类名（如 DeviceInfoEntity），可能为 null（无法解析时）
 * @param calls                 按源码顺序的方法调用（wrapper.eq(...) 等）
 * @param constructorAlias      表别名构造（new MPJLambdaWrapper<>(Class, "apply_info") 第二参，清单 C4/P11）；null 表示默认
 * @param deleteConsumed        wrapper 作为实参传给删除类消费方法（service.remove(wrapper) / mapper.delete(wrapper)，
 *                              MP 惯用删除写法：删除条件用 LambdaQueryWrapper 构造）；true 时即使类型为查询类也应转 DELETE
 * @param countConsumed         wrapper 作为实参传给计数消费方法（service.count(wrapper) / selectJoinCount(wrapper)）；
 *                              true 时查询类 wrapper 转 SELECT COUNT(*) FROM ...
 * @param existsConsumed        链式终结点消费（service.lambdaQuery()...exists()，Q14-③，T-M15-01）；
 *                              true 时查询类链转 SELECT EXISTS (子查询)
 * @param updateEntityConsumed  实体更新消费（service.update(entity, wrapper) 双参，U07-②，T-M15-04）；
 *                              true 时用实体字段展开 SET 列（值占位）
 * @param pageLimit             分页消费解析产物（service.page(Page, wrapper) / selectPage / 链式 page()，
 *                              T-M29-02/03）；null 表示无分页消费或 Page 实参不可解析（不渲染 LIMIT）
 */
public final class WrapperChain {
    private final WrapperType wrapperType;
    private final String entityClass;
    private final List<WrapperCall> calls;
    private final String constructorAlias;
    private final boolean deleteConsumed;
    private final boolean countConsumed;
    private final boolean existsConsumed;
    private final boolean updateEntityConsumed;
    private final LimitClause pageLimit;

    public WrapperChain(WrapperType wrapperType, String entityClass, List<WrapperCall> calls, String constructorAlias,
                        boolean deleteConsumed, boolean countConsumed, boolean existsConsumed,
                        boolean updateEntityConsumed, LimitClause pageLimit) {
        this.wrapperType = wrapperType;
        this.entityClass = entityClass;
        this.calls = calls;
        this.constructorAlias = constructorAlias;
        this.deleteConsumed = deleteConsumed;
        this.countConsumed = countConsumed;
        this.existsConsumed = existsConsumed;
        this.updateEntityConsumed = updateEntityConsumed;
        this.pageLimit = pageLimit;
    }

    public WrapperChain(WrapperType wrapperType,
                        String entityClass,
                        List<WrapperCall> calls) {
        this(wrapperType, entityClass, calls, null, false, false, false, false, null);
    }

    public WrapperChain(WrapperType wrapperType,
                        List<WrapperCall> calls) {
        this(wrapperType, null, calls, null, false, false, false, false, null);
    }

    public WrapperType getWrapperType() {
        return wrapperType;
    }

    public String getEntityClass() {
        return entityClass;
    }

    public List<WrapperCall> getCalls() {
        return calls;
    }

    public String getConstructorAlias() {
        return constructorAlias;
    }

    public boolean isDeleteConsumed() {
        return deleteConsumed;
    }

    public boolean getDeleteConsumed() {
        return deleteConsumed;
    }

    public boolean isCountConsumed() {
        return countConsumed;
    }

    public boolean getCountConsumed() {
        return countConsumed;
    }

    public boolean isExistsConsumed() {
        return existsConsumed;
    }

    public boolean getExistsConsumed() {
        return existsConsumed;
    }

    public boolean isUpdateEntityConsumed() {
        return updateEntityConsumed;
    }

    public boolean getUpdateEntityConsumed() {
        return updateEntityConsumed;
    }

    public LimitClause getPageLimit() {
        return pageLimit;
    }

    public WrapperChain copy(WrapperType wrapperType, String entityClass, List<WrapperCall> calls, String constructorAlias,
                             boolean deleteConsumed, boolean countConsumed, boolean existsConsumed,
                             boolean updateEntityConsumed, LimitClause pageLimit) {
        return new WrapperChain(wrapperType, entityClass, calls, constructorAlias, deleteConsumed, countConsumed,
                existsConsumed, updateEntityConsumed, pageLimit);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WrapperChain)) {
            return false;
        }
        WrapperChain that = (WrapperChain) o;
        return deleteConsumed == that.deleteConsumed
                && countConsumed == that.countConsumed
                && existsConsumed == that.existsConsumed
                && updateEntityConsumed == that.updateEntityConsumed
                && Objects.equals(wrapperType, that.wrapperType)
                && Objects.equals(entityClass, that.entityClass)
                && Objects.equals(calls, that.calls)
                && Objects.equals(constructorAlias, that.constructorAlias)
                && Objects.equals(pageLimit, that.pageLimit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(wrapperType, entityClass, calls, constructorAlias, deleteConsumed, countConsumed,
                existsConsumed, updateEntityConsumed, pageLimit);
    }

    @Override
    public String toString() {
        return "WrapperChain(wrapperType=" + wrapperType + ", entityClass=" + entityClass + ", calls=" + calls
                + ", constructorAlias=" + constructorAlias + ", deleteConsumed=" + deleteConsumed + ", countConsumed="
                + countConsumed + ", existsConsumed=" + existsConsumed + ", updateEntityConsumed=" + updateEntityConsumed
                + ", pageLimit=" + pageLimit + ")";
    }
}
