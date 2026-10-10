package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiClass

/**
 * 实体别名反查的**有界扫描**（T-GutterDupFreeze S3，20261008 用户真机冻结事故）。
 *
 * 事故形态：`userMapper.selectList(<wrapper 链>)` 被误认成自定义 DAO HQL 后，预览链拿链源码里的
 * `from t_order` 去反查实体类——不存在的名字必然扫完整个项目（每个类短名一次 StubIndex 查询，
 * 索引未就绪期每次都是随机磁盘读），实测 70 秒；该扫描跑在 `ReadAction.compute`（不可取消）里，
 * 读锁全程不放 → EDT 等读许可被冻结、平台 `SuvorovProgress` 覆盖层接管事件（空白窗关不掉）。
 *
 * 本对象把该扫描收口为「有上限 + 可取消 + 命中即止」，并把判定逻辑与 PSI 取数解耦，便于用纯用例
 * 钉住「超限即停」这条判别行为（守卫类改动须可反向验证）。
 *
 * 上限口径：正常工程的类短名数远小于上限，只有病态/超大工程或误判输入才会触顶；触顶按**未命中**
 * 降级并回调记账。**触顶不是「确认不存在」**——调用方据此**不写负缓存**（见
 * [cn.song.mybatisplus.visualizer.ui.SqlPreviewService] 的调用点），避免大工程里的合法别名被
 * 一次性截断后永久记成未命中。
 */
internal object EntityAliasSweep {

    /**
     * 单次反查允许遍历的类短名数上限。
     *
     * 取 5000：常规工程（含多模块 demo）的全量短名在千级，功能上等于不设限；真正起作用的是
     * 病态/超大工程下把「冷索引逐名随机读」的最坏代价钉住（配合取消检查，平台需要写锁时可中断）。
     */
    internal const val SCAN_LIMIT: Int = 5000

    /** 取消检查间隔（每 N 个短名查一次）：`checkCanceled` 自身有开销，不必逐个查。 */
    private const val CANCEL_CHECK_INTERVAL: Int = 64

    /**
     * 扫描 [names]（类短名序列），返回首个实体别名等于 [aliasName]（忽略大小写）的实体类。
     *
     * @param aliasName   目标实体别名（`@Entity(name = ...)` 的 name）
     * @param names       待遍历的类短名（生产侧＝`PsiShortNamesCache.getAllClassNames()`）
     * @param lookup      按短名取候选类（生产侧＝`PsiShortNamesCache.getClassesByName(shortName, scope)`，
     *                    平台签名返回 `PsiClass[]`）
     * @param limit       遍历上限，默认 [SCAN_LIMIT]
     * @param onTruncated 触顶回调（入参＝已遍历短名数），生产侧记账用
     * @return 命中的实体类；未命中或触顶（降级为未命中）时 null
     */
    fun findAliasOwner(
        aliasName: String,
        names: Sequence<String>,
        lookup: (String) -> Array<PsiClass>,
        limit: Int = SCAN_LIMIT,
        onTruncated: (Int) -> Unit = {},
    ): PsiClass? {
        var scanned = 0
        for (shortName in names) {
            if (scanned >= limit) {
                onTruncated(scanned)
                return null
            }
            if (scanned % CANCEL_CHECK_INTERVAL == 0) ProgressManager.checkCanceled()
            scanned++
            for (clazz in lookup(shortName)) {
                // 与旧实现同口径：接口/枚举/注解类型不可能是 @Entity 实体
                if (clazz.isInterface || clazz.isEnum || clazz.isAnnotationType) continue
                val alias = JpaMetadataReader.entityNameOf(clazz) ?: continue
                if (alias.equals(aliasName, ignoreCase = true)) return clazz
            }
        }
        return null
    }
}
