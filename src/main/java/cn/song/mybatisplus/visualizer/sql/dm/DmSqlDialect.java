package cn.song.mybatisplus.visualizer.sql.dm;

import com.intellij.database.Dbms;
import com.intellij.psi.tree.IElementType;
import com.intellij.sql.dialects.base.SqlLanguageDialectBase;
import com.intellij.sql.dialects.base.TokensHelper;
import com.intellij.sql.dialects.sql92.Sql92Dialect;

import java.util.Set;

/**
 * T-DmDialect（O-136 真机发现，20261014）：达梦 SQL 语言方言——平台注册用最小实现。
 *
 * <p>背景：262 平台的 SQL 方言注册表（database-plugin plugin.xml {@code <sql.dialect dbms=…>} 35 项，
 * AZURE~VERTICA）<b>无 DM</b>，{@code SqlDialectMappings.getAvailableValues} 不含达梦 → O-136 的
 * 「按数据源方言出 Runner」对达梦数据源断在方言解析层（真机日志
 * {@code dialect pre-mapped to MySQL … ds=LocalDataSource}，urlFallbackId 候选 [DM, Dameng] 均不命中）。
 * 本类注册后语言 id {@code DM} 进入平台可用清单，候选即命中，路由闭合。</p>
 *
 * <p>形态照抄平台最小样板 GenericDialect（javap 反编译，20261014，逐字节对齐其最小覆写集）：</p>
 * <ul>
 * <li>构造传语言 id {@code DM}（Generic 传 {@code GenericSQL} 同位）；</li>
 * <li>{@link #createTokensHelper()} 委托 {@link Sql92Dialect}（Generic 同款委托，零自维护关键字/令牌）；</li>
 * <li>{@link #getDbms()} 返回 {@link Dbms#UNKNOWN}（Generic 同款——平台无达梦数据库方言，诚实未知）；</li>
 * <li>{@link #supportsErrorHighlighting()} = false（无 DM 专属 parser，不开错误高亮防假报错，Generic 同款）。</li>
 * </ul>
 *
 * <p>已知边界（D2「完整实现」候选，本轮不做）：无 DM 专属 parser/错误检查，{@code DM} 文件的高亮
 * 视觉与 Generic 相近；关键字集未按达梦扩展。</p>
 */
public final class DmSqlDialect extends SqlLanguageDialectBase {

    // sql.dialect 使用 DbmsExtension.InstanceBean，读取 public static INSTANCE，不调用构造器。
    public static final DmSqlDialect INSTANCE = new DmSqlDialect();

    /**
     * 语言/方言 id：亦即 {@code UrlSubprotocolDialects} 达梦候选链
     * （PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["DM（达梦）"] = [DM, Dameng]）的命中值。
     */
    public static final String ID = "DM";

    /** 设置页（SQL Dialects）/选择器里的显示名。 */
    public static final String DISPLAY_NAME = "DM";

    private DmSqlDialect() {
        super(ID);
    }

    @Override
    public TokensHelper createTokensHelper() {
        return Sql92Dialect.INSTANCE.getTokensHelper();
    }

    @Override
    public boolean isOperatorSupported(IElementType op) {
        return Sql92Dialect.INSTANCE.isOperatorSupported(op);
    }

    @Override
    public Set<String> getSystemVariables() {
        return Sql92Dialect.INSTANCE.getSystemVariables();
    }

    @Override
    public Dbms getDbms() {
        return Dbms.UNKNOWN;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public boolean supportsErrorHighlighting() {
        return false;
    }
}
