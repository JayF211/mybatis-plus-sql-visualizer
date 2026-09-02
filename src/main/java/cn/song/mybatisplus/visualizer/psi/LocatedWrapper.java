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
 */
public final class LocatedWrapper {

    private final WrapperChain chain;
    private final TableInfo table;
    private final PsiFile entityFile;

    /** T-M19.4：解析时的实体文件修改戳——缓存命中时校验（实体文件变更即失效重算）。 */
    private final long entityFileStamp;

    public LocatedWrapper(WrapperChain chain, TableInfo table, PsiFile entityFile) {
        this.chain = chain;
        this.table = table;
        this.entityFile = entityFile;
        this.entityFileStamp = entityFile != null ? entityFile.getModificationStamp() : -1L;
    }

    public LocatedWrapper(WrapperChain chain, TableInfo table) {
        this(chain, table, null);
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
            && java.util.Objects.equals(chain, other.chain)
            && java.util.Objects.equals(table, other.table)
            && java.util.Objects.equals(entityFile, other.entityFile);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(chain, table, entityFile, entityFileStamp);
    }

    @Override
    public String toString() {
        return "LocatedWrapper(chain=" + chain + ", table=" + table + ", entityFile=" + entityFile
            + ", entityFileStamp=" + entityFileStamp + ")";
    }
}
