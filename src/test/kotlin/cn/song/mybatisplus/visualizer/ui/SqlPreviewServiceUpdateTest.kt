package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class SqlPreviewServiceUpdateTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    /** T-TenantLine：每个测试结束后重置多租户配置为默认，防止状态泄漏。 */
    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
        PluginSettings.getInstance().state.tenantColumnName = PluginSettings.DEFAULT_TENANT_COLUMN_NAME
        super.tearDown()
    }

    private fun anchor(): PsiElement {
        val file = myFixture.file
        val methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        // 空调用链场景（如无条件的 LambdaDeleteWrapper）：锚定变量声明本身
        val variable = PsiTreeUtil.findChildOfType(file, PsiLocalVariable::class.java)
        if (variable != null) return variable
        return PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(file)
    }

    fun testUpdateSetBooleanConditionPrefix() {
        // U03（T-M15-04）：set(boolean, col, val) 条件 set 布尔前缀剥离（真实项目约 25 处）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    orderInvoiceApplyService.update(new LambdaUpdateWrapper<OrderInvoiceApplyEntity>()
                        .eq(OrderInvoiceApplyEntity::getId, applyId)
                        .set(StrUtil.isNotBlank(seqId), OrderInvoiceApplyEntity::getSeqId, seqId)
                        .set(OrderInvoiceApplyEntity::getRespDesc, respDesc)
                        .set(OrderInvoiceApplyEntity::getDicApplyStatus, 2));
                }

                static class OrderInvoiceApplyEntity {
                    private Long id;
                    private String seqId;
                    private String respDesc;
                    private Integer dicApplyStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("条件 set 应产出预览结果", result)
        assertEquals(
            """
            UPDATE order_invoice_apply_entity
            SET seq_id = ?, resp_desc = ?, dic_apply_status = 2
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testUpdateSetSqlPlaceholderParams() {
        // U04-③（T-M15-04）：setSql("...{0}", val) 绑定参数替换（consumer/GiftFundPayService 余额保护真实用例）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    giftFundPayService.lambdaUpdate()
                        .setSql("frozen_amount = COALESCE(frozen_amount, 0) - {0}", allocated)
                        .apply("COALESCE(frozen_amount, 0) >= {0}", allocated)
                        .eq(GiftFundEntity::getId, giftId)
                        .update();
                }

                static class GiftFundEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("setSql 参数化应产出预览结果", result)
        assertEquals(
            """
            UPDATE gift_fund_entity
            SET frozen_amount = COALESCE(frozen_amount, 0) - ?
            WHERE COALESCE(frozen_amount, 0) >= ?
                AND id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testUpdateLastRawTailPassthrough() {
        // U05（T-M15-04）：更新 wrapper 上 last("AND (... >= 0)") 防超发兜底（modules/StoreCouponServiceImpl#deduction 真实用例）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    UpdateWrapper<StoreCouponEntity> wrapper = new UpdateWrapper<>();
                    wrapper.setSql("last_total = last_total - 1");
                    wrapper.last("AND (last_total - 1 >= 0)");
                    wrapper.eq("id", id);
                    update(wrapper);
                }

                static class StoreCouponEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("last 尾缀透传应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_entity
            SET last_total = last_total - 1
            WHERE id = ?
            AND (last_total - 1 >= 0)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testUpdateEntityWrapperConsumesEntityColumns() {
        // U07-②（T-M15-04）：service.update(entity, wrapper) 双参 upsert（admin/StationBarrierPushConfigRelAppService 真实用例）
        // wrapper 无 set → 实体字段展开为 SET 列（值占位），主键列排除
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void saveOrUpdate() {
                    boolean saved = stationBarrierPushConfigRelService.update(
                            entity,
                            Wrappers.<BizStationBarrierPushConfigRelEntity>lambdaUpdate()
                                .eq(BizStationBarrierPushConfigRelEntity::getStationId, stationId))
                        || stationBarrierPushConfigRelService.save(entity);
                }

                static class BizStationBarrierPushConfigRelEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private Long stationId;
                    private Integer dicStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("update(entity, wrapper) 应产出预览结果", result)
        assertEquals(
            """
            UPDATE biz_station_barrier_push_config_rel_entity
            SET station_id = ?, dic_status = ?
            WHERE station_id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testWrappersLambdaUpdateNoTypeArgInfersEntity() {
        // U01-③（T-M15-04）：Wrappers.lambdaUpdate() 无显式泛型（真实项目约 25 处）——
        // 实体从链内 set/eq 首参方法引用推断（T-M15-01 fallback）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    accountService.update(Wrappers.lambdaUpdate()
                        .set(AccountEntity::getLastLoginTime, now)
                        .eq(AccountEntity::getId, account.getId()));
                }

                static class AccountEntity {
                    private Long id;
                    private String lastLoginTime;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("无泛型 lambdaUpdate 应产出预览结果", result)
        assertEquals(
            """
            UPDATE account_entity
            SET last_login_time = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testIfElseBranchesKeepFirstBranchOnly() {
        // P15（T-M15-05）：if/else 互斥分支只保留源码序第一个分支（then），
        // 避免 `if (x) { w.eq(...) } else { w.in(...) }` 合并成 `WHERE id = ? AND id IN (...)` 语义错误
        // （bridge/PlatformService#findCarDeviceInfoByStationIds 真实用例）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .selectAll(DeviceInfoEntity.class);
                    if (stationIds.size() == 1) {
                        w.eq(DeviceInfoEntity::getStationId, stationIds.get(0));
                    } else {
                        w.in(DeviceInfoEntity::getStationId, stationIds);
                    }
                }

                static class DeviceInfoEntity {
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("if/else 分支应产出预览结果", result)
        // 仅保留 then 分支 eq；else 分支 in 不并入
        assertEquals(
            "SELECT station_id FROM device_info_entity WHERE station_id = ?",
            result!!.compactSql.trim(),
        )
    }

    fun testUpdateArgumentAnchorRendersUpdate() {
        // U08（T-M15-05）：`baseMapper.update(null, wrapper)` 行的 wrapper 实参锚点 → 回查变量声明完整链
        // （modules/CouponUserQueryServiceImpl#overdueTask 真实用例：selectCount 预检 + 复用追加 set）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void task() {
                    LambdaUpdateWrapper<StoreCouponUserEntity> wrapper = new LambdaUpdateWrapper<StoreCouponUserEntity>()
                        .eq(StoreCouponUserEntity::getCouponId, couponId)
                        .lt(StoreCouponUserEntity::getExpireTime, now);
                    Long count = baseMapper.selectCount(wrapper);
                    if (count <= 0) { return; }
                    wrapper.set(StoreCouponUserEntity::getDicStatus, 1).set(StoreCouponUserEntity::getUseTime, now);
                    int updated = baseMapper.update(null, wrapper);
                }

                static class StoreCouponUserEntity {
                    private Long couponId;
                    private String expireTime;
                    private Integer dicStatus;
                    private String useTime;
                }
            }
            """.trimIndent(),
        )
        // 锚定 update 行（wrapper 作为实参）——T-M15-05 实参锚点支持
        val updateCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "update" }
        val result = newService().preview(updateCall)
        assertNotNull("update 行实参锚点应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_user_entity
            SET dic_status = 1, use_time = ?
            WHERE coupon_id = ?
                AND expire_time < ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testFactoryArgumentThreeLevelChain() {
        // P03 3 级链（T-M15-05）：applyWithdrawFilters(baseWithdrawQueryWrapper(), dto) ——
        // 工厂方法 return 链首的 qualifier 是参数 wrapper，实参工厂（baseWithdrawQueryWrapper）的链需前置
        // （admin/MerchantWalletWithdrawAppService#queryStatistics 真实用例结构）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<WalletWithdrawEntity> wrapper = applyWithdrawFilters(baseWithdrawQueryWrapper(), dto)
                        .orderByDesc(WalletWithdrawEntity::getCreateTime);
                }

                private MPJLambdaWrapper<WalletWithdrawEntity> baseWithdrawQueryWrapper() {
                    return new MPJLambdaWrapper<WalletWithdrawEntity>()
                        .selectAll(WalletWithdrawEntity.class)
                        .leftJoin(AccountEntity.class, AccountEntity::getId, WalletWithdrawEntity::getAuditedBy);
                }

                private MPJLambdaWrapper<WalletWithdrawEntity> applyWithdrawFilters(
                        MPJLambdaWrapper<WalletWithdrawEntity> wrapper, DTO dto) {
                    return wrapper
                        .eq(WalletWithdrawEntity::getMerchantId, dto.getMerchantId())
                        .ge(WalletWithdrawEntity::getCreateTime, dto.getStartTime());
                }

                static class WalletWithdrawEntity {
                    private Long merchantId;
                    private String createTime;
                    private Long auditedBy;
                }

                static class AccountEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("3 级工厂链应产出预览结果", result)
        assertEquals(
            """
            SELECT t.merchant_id, t.create_time, t.audited_by
            FROM wallet_withdraw_entity t
            LEFT JOIN account_entity accountEntity ON accountEntity.id = t.audited_by
            WHERE t.merchant_id = ?
                AND t.create_time >= ?
            ORDER BY t.create_time DESC
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMpjSelectCollectionWithThreeArgJoinOn() {
        // P06（T-M15-06）：selectCollection + 三参 leftJoin —— 子查询 LEFT JOIN 必须带 ON
        // （修复前仅匹配 on(col1,col2) 形态，三参形态下子查询无 ON 产生笛卡尔积）
        // 真实结构：admin/CarOccupyRecordAppService#detail
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void detail() {
                    MPJLambdaWrapper<OccupyRecordEntity> wrapper = new MPJLambdaWrapper<OccupyRecordEntity>()
                        .selectAll(OccupyRecordEntity.class)
                        .selectCollection(OccupyConsumptionRecordEntity.class, OccupyRecordDetailVO::getConsumptions,
                            map -> map
                                .id(OccupyConsumptionRecordEntity::getId, OccupyRecordConsumptionItemVO::getId)
                                .result(OccupyConsumptionRecordEntity::getLadder, OccupyRecordConsumptionItemVO::getLadder))
                        .leftJoin(OccupyConsumptionRecordEntity.class,
                            OccupyConsumptionRecordEntity::getOccupyId, OccupyRecordEntity::getId)
                        .eq(OccupyRecordEntity::getId, 1001L);
                }

                static class OccupyRecordEntity {
                    private Long id;
                }

                static class OccupyConsumptionRecordEntity {
                    private Long id;
                    private Integer ladder;
                    private Long occupyId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("selectCollection 应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id
            FROM occupy_record_entity t
            LEFT JOIN (SELECT id, ladder FROM occupy_consumption_record_entity occupyConsumptionRecordEntity) occupyConsumptionRecordEntity ON t.id = occupyConsumptionRecordEntity.occupy_id
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

}
