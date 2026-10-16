package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.converter.jpa.JpaSqlLiteralMasking

/**
 * HQL/JPQL 语句**根实体名**提取器（T-HqlUpdateEntityMap S1 立；T-EntityNameScope 收口提取口径）。
 *
 * 缺陷背景（S1）：实体名提取原先散落三处（[cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator]
 * 的 createQuery 实体 fallback、[cn.song.mybatisplus.visualizer.ui.SqlPreviewService] 的
 * resolveHqlTable 类名来源、[cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter] 的兜底表名
 * 替换），全部只认 `FROM 实体` 正则——`update WmsDockBooking booking set ...` 这类**无 FROM 子句**的
 * UPDATE 语句提取不到实体名，resolveHqlTable 三路径全跳过、TableInfo 落空，表名/属性映射整链跳过，
 * 预览产物退化为仅参数替换的原样透传（用户报障 demo 形态 17）。
 *
 * 缺陷背景（T-EntityNameScope，20261016）：本提取器与转换链的
 * [cn.song.mybatisplus.visualizer.converter.jpa.JpaQueryEntityLookup.rootEntity] 各有一套算法，
 * 对同一句 HQL 可以给出**不同**的根实体；而预览先产出的非空 TableInfo 会被转换链优先采用，
 * 于是「弱提取」压过「强提取」（实测反例 `select (select max(d.id) from Detail d) from Inventory i`：
 * 预览认 `Detail`、转换链认 `Inventory`，最终按 `Detail` 渲染）。现把强算法**收口到本对象**，
 * 转换链改为委托，两边口径合一。
 *
 * 提取口径：
 *  - **字面量掩码**：字符串里的 `from X` 不参与（`select 'it''s ( from Wrong x )' from …` 不得被字符串抢占）；
 *  - **括号深度 0**：SELECT 里的标量子查询、CTE 内部的 FROM 不抢占外层根实体；
 *  - **语句头优先**：交替顺序即优先级——`UPDATE` / `INTO` 出现在任何子查询 `FROM` 之前，
 *    故 `INSERT INTO X … SELECT … FROM Y` 取目标表 X（与旧「语句头分派」同结果，但不再需要按头分支）；
 *  - **全限定名保留**：`from model.Inventory i` → `model.Inventory`（旧口径只取首段 `model`，
 *    会命中恰叫 `model` 的诱饵类）；
 *  - **首个深度 0 匹配即定根**，不做类名形态过滤（`update t_order o` 仍应提取出 `t_order`，属既有口径，
 *    见 `HqlEntityNameExtractorTest.lowercaseKeywords_extractsEntity`）。
 */
object HqlEntityNameExtractor {

    /**
     * 根实体候选：`FROM` / `UPDATE` / `INTO` / `DELETE [FROM]` 之后的标识符（允许全限定名）。
     *
     * 从左到右取**首个**匹配 ⇒ 语句头天然优先（`UPDATE`/`INTO` 出现在任何子查询 `FROM` 之前）；
     * 是否落在括号内由调用方按深度判定。
     */
    private val ROOT_ENTITY = Regex(
        "(?i)\\b(?:FROM|UPDATE|INTO|DELETE(?:\\s+FROM)?)\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)",
    )

    /**
     * 从 HQL/JPQL 提取**根实体**标识符（可能带包前缀）。
     *
     * 口径（T-EntityNameScope 起与转换链共用同一份实现，见文件头）：
     * 字面量掩码 → 跳过括号内（深度 > 0）的 FROM → 取首个深度 0 匹配。
     *
     * @return 根实体标识符（如 `WmsInventoryEntity` / `model.Inventory`）；提不到时 null，调用方走既有降级
     */
    fun extract(hql: String): String? {
        val masked = JpaSqlLiteralMasking.mask(hql).first
        var depth = 0
        var offset = 0
        for (match in ROOT_ENTITY.findAll(masked)) {
            while (offset < match.range.first) {
                when (masked[offset++]) {
                    '(' -> depth++
                    ')' -> depth--
                }
            }
            if (depth == 0) return match.groupValues[1]
        }
        return null
    }
}
