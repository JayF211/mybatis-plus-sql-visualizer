package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import com.intellij.psi.PsiFile;

/**
 * 定位结果：调用链 + 实体表信息（原 Kotlin data class）。
 *
 * @param chain      Wrapper 调用链
 * @param table      实体解析的表信息（实体类缺失时为 null）
 * @param entityFile 实体类所在文件（@TableName/@TableField 注解来源；文本兜底无实体类时为 null）
 * @param entityResolutionFailed O-8（缺陷 B）：字符串服务链实体解析失败标记——三通道（类型实参/调用返回类型/服务泛型实参）
 *                               与链内方法引用推断全失败（table=null）。preview 侧据此返回显式降级警示而非静默 null；
 *                               仅服务链形态置位（{@code WrapperLocator.buildChainFromFactory}），工厂/Kotlin/构造形态维持 null 语义。
 */
public final class LocatedWrapper {

    private final WrapperChain chain;
    private final TableInfo table;
    private final PsiFile entityFile;
    private final boolean entityResolutionFailed;

    /** T-M19.4：解析时的实体文件修改戳——缓存命中时校验（实体文件变更即失效重算）。 */
    private final long entityFileStamp;

    public LocatedWrapper(WrapperChain chain, TableInfo table, PsiFile entityFile, boolean entityResolutionFailed) {
        this.chain = chain;
        this.table = table;
        this.entityFile = entityFile;
        this.entityResolutionFailed = entityResolutionFailed;
        this.entityFileStamp = entityFile != null ? entityFile.getModificationStamp() : -1L;
    }

    /** Kotlin 默认参数等价重载：{@code entityResolutionFailed} 缺省 false。 */
    public LocatedWrapper(WrapperChain chain, TableInfo table, PsiFile entityFile) {
        this(chain, table, entityFile, false);
    }

    public LocatedWrapper(WrapperChain chain, TableInfo table) {
        this(chain, table, null, false);
    }

    public WrapperChain getChain() {
        return chain;
    }

    public TableInfo getTable() {
        return table;
    }

    public PsiFile getEntityFile() {
        return entityFile;
    }

    public boolean entityResolutionFailed() {
        return entityResolutionFailed;
    }

    public long getEntityFileStamp() {
        return entityFileStamp;
    }

    /**
     * T-M19.4（用户实测：实体文件新增/修改 @TableName 后预览不生效——缓存按<b>锚点文件</b>戳失效、
     * 实体文件变化不触发）：实体文件是否仍与解析时一致；无实体类（文本兜底）视为有效。
     */
    public boolean entityFileValid() {
        return entityFile == null || entityFile.getModificationStamp() == entityFileStamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocatedWrapper)) return false;
        LocatedWrapper other = (LocatedWrapper) o;
        return entityFileStamp == other.entityFileStamp
            && entityResolutionFailed == other.entityResolutionFailed
            && java.util.Objects.equals(chain, other.chain)
            && java.util.Objects.equals(table, other.table)
            && java.util.Objects.equals(entityFile, other.entityFile);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(chain, table, entityFile, entityResolutionFailed, entityFileStamp);
    }

    @Override
    public String toString() {
        return "LocatedWrapper(chain=" + chain + ", table=" + table + ", entityFile=" + entityFile
            + ", entityResolutionFailed=" + entityResolutionFailed + ", entityFileStamp=" + entityFileStamp + ")";
    }
}
