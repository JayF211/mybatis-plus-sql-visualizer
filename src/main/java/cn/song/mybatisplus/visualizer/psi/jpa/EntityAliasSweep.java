package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiClass;
import kotlin.jvm.functions.Function1;
import kotlin.sequences.Sequence;

import java.util.Iterator;

/**
 * 实体别名反查的<b>有界扫描</b>（T-GutterDupFreeze S3，20261008 用户真机冻结事故）。
 *
 * <p>事故形态：{@code userMapper.selectList(<wrapper 链>)} 被误认成自定义 DAO HQL 后，预览链拿链源码里的
 * {@code from t_order} 去反查实体类——不存在的名字必然扫完整个项目（每个类短名一次 StubIndex 查询，
 * 索引未就绪期每次都是随机磁盘读），实测 70 秒；该扫描跑在 {@code ReadAction.compute}（不可取消）里，
 * 读锁全程不放 → EDT 等读许可被冻结、平台 {@code SuvorovProgress} 覆盖层接管事件（空白窗关不掉）。</p>
 *
 * <p>本对象把该扫描收口为「有上限 + 可取消 + 命中即止」，并把判定逻辑与 PSI 取数解耦，便于用纯用例
 * 钉住「超限即停」这条判别行为（守卫类改动须可反向验证）。</p>
 *
 * <p>上限口径：正常工程的类短名数远小于上限，只有病态/超大工程或误判输入才会触顶；触顶按<b>未命中</b>
 * 降级并回调记账。<b>触顶不是「确认不存在」</b>——调用方据此<b>不写负缓存</b>（见
 * {@link cn.song.mybatisplus.visualizer.ui.SqlPreviewService} 的调用点），避免大工程里的合法别名被
 * 一次性截断后永久记成未命中。</p>
 */
public final class EntityAliasSweep {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final EntityAliasSweep INSTANCE = new EntityAliasSweep();

    private EntityAliasSweep() {
    }

    /**
     * 单次反查允许遍历的类短名数上限。
     *
     * <p>取 5000：常规工程（含多模块 demo）的全量短名在千级，功能上等于不设限；真正起作用的是
     * 病态/超大工程下把「冷索引逐名随机读」的最坏代价钉住（配合取消检查，平台需要写锁时可中断）。</p>
     */
    public static final int SCAN_LIMIT = 5000;

    /** 取消检查间隔（每 N 个短名查一次）：{@code checkCanceled} 自身有开销，不必逐个查。 */
    private static final int CANCEL_CHECK_INTERVAL = 64;

    /** Kotlin 默认参数等价重载：{@code limit} 缺省 {@link #SCAN_LIMIT}，{@code onTruncated} 缺省空回调。 */
    public static PsiClass findAliasOwner(String aliasName, Sequence<String> names,
                                          Function1<String, PsiClass[]> lookup) {
        return findAliasOwner(aliasName, names, lookup, SCAN_LIMIT, scanned -> kotlin.Unit.INSTANCE);
    }

    /** Kotlin 默认参数等价重载：{@code onTruncated} 缺省空回调。 */
    public static PsiClass findAliasOwner(String aliasName, Sequence<String> names,
                                          Function1<String, PsiClass[]> lookup, int limit) {
        return findAliasOwner(aliasName, names, lookup, limit, scanned -> kotlin.Unit.INSTANCE);
    }

    /**
     * 扫描 {@code names}（类短名序列），返回首个实体别名等于 {@code aliasName}（忽略大小写）的实体类。
     *
     * @param aliasName   目标实体别名（{@code @Entity(name = ...)} 的 name）
     * @param names       待遍历的类短名（生产侧＝{@code PsiShortNamesCache.getAllClassNames()}）
     * @param lookup      按短名取候选类（生产侧＝{@code PsiShortNamesCache.getClassesByName(shortName, scope)}，
     *                    平台签名返回 {@code PsiClass[]}）
     * @param limit       遍历上限，默认 {@link #SCAN_LIMIT}
     * @param onTruncated 触顶回调（入参＝已遍历短名数），生产侧记账用
     * @return 命中的实体类；未命中或触顶（降级为未命中）时 null
     */
    public static PsiClass findAliasOwner(String aliasName, Sequence<String> names,
                                          Function1<String, PsiClass[]> lookup, int limit,
                                          Function1<Integer, kotlin.Unit> onTruncated) {
        int scanned = 0;
        Iterator<String> it = names.iterator();
        while (it.hasNext()) {
            String shortName = it.next();
            if (scanned >= limit) {
                onTruncated.invoke(scanned);
                return null;
            }
            if (scanned % CANCEL_CHECK_INTERVAL == 0) ProgressManager.checkCanceled();
            scanned++;
            for (PsiClass clazz : lookup.invoke(shortName)) {
                // 与旧实现同口径：接口/枚举/注解类型不可能是 @Entity 实体
                if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotationType()) continue;
                String alias = JpaMetadataReader.entityNameOf(clazz);
                if (alias == null) continue;
                if (alias.equalsIgnoreCase(aliasName)) return clazz;
            }
        }
        return null;
    }
}
