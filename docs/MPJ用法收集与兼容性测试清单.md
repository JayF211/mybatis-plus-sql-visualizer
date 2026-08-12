# MyBatis-Plus-Join（MPJ）用法收集与兼容性测试清单

> **文档用途**：为「MyBatis-Plus SQL Visualizer」IDEA 插件提供本项目内**全部 MPJ 真实用法语料**（已跨文件去重），用于插件兼容性测试。
> 插件核心目标（F-11 MPJLambdaWrapper 联表、F-12 MPJQueryWrapper 字符串联表、F-02 参数替换、F-03 格式化、F-13 表名列名解析）需覆盖以下清单中的每一种模式。
>
> 配套文档：「MyBatis-Plus SQL Visualizer」插件的《项目介绍》与《功能概览》（插件仓库内，不在本仓库）。

---

## 1. 环境与依赖版本

| 依赖 | 版本 | 说明 |
|------|------|------|
| `mybatis-plus-join-boot-starter` | **1.5.6** | MPJ 核心（`com.github.yulichang`） |
| `mybatis-plus-spring-boot3-starter` | **3.5.15** | Spring Boot 3 版 MyBatis-Plus |
| `mybatis` | **3.5.19** | MyBatis 底层 |
| Java / Spring Boot | 项目现有 | 无特殊 MPJ 要求 |

**MPJ 包导入面（全部 import 统计）**：

| import | 出现文件数 |
|--------|-----------|
| `com.github.yulichang.base.MPJBaseMapper` | 67 |
| `com.github.yulichang.base.MPJBaseServiceImpl` | 62 |
| `com.github.yulichang.base.MPJBaseService` | 59 |
| `com.github.yulichang.wrapper.MPJLambdaWrapper` | 59 |
| `com.github.yulichang.toolkit.StrUtils` | 4（均为 `StrUtils.equals` 字符串工具，与 Wrapper 无关） |
| `com.github.yulichang.query.MPJLambdaQueryWrapper` | 1 |

---

## 2. 统计总览

| 指标 | 数值 |
|------|------|
| 使用 MPJ 的源文件数 | **61** |
| MPJ 用法点总数 | **约 150 处** |
| 使用 `MPJLambdaWrapper` | ✅ 全覆盖（150 处） |
| 使用 `MPJLambdaQueryWrapper` | 1 处（`SubAccountAppService.java:242`） |
| 使用 `MPJQueryWrapper` | ❌ **0 处（项目中完全未使用）** |

**消费方法分布（去重后）**：

| 消费方法 | 载体 | 出现情况 |
|----------|------|----------|
| `selectJoinList(Class<R>, wrapper)` | `MPJBaseService` / `MPJBaseMapper` / 继承类自身 | 最常见（列表/下拉/聚合） |
| `selectJoinOne(Class<R>, wrapper)` | 同上 | 常见（详情） |
| `selectJoinListPage(Page<R>, Class<R>, wrapper)` | 自定义 Service 扩展 / `MPJBaseService` | 常见（分页列表） |
| `selectJoinCount(wrapper)` | Service | 少量（计数） |
| `getOne(wrapper)` / `count(wrapper)` / `remove(wrapper)` | 原生 `IService` | 少量（MPJ wrapper 直接喂给原生方法） |
| `selectList(wrapper)` | 普通 `BaseMapper` | 少量（仅条件、无 join 时也允许） |
| `selectJoinList(Class<R>, wrapper)`（Mapper 直调） | `MPJBaseMapper` | 少量 |
| `IService.list(wrapper)` | 普通 Service | 1 处（`MPJLambdaQueryWrapper`） |

---

## 3. Wrapper 构造形式（去重）

| # | 构造形式 | 示例 | 出现位置 |
|---|----------|------|----------|
| C1 | 无参 + 链式启动 | `new MPJLambdaWrapper<CarDeviceInfoEntity>()` | 绝大多数 |
| C2 | 菱形推断 + 分步 `.` 续链 | `MPJLambdaWrapper<X> w = new MPJLambdaWrapper<>(); w.select(...)` | `StationInfoService`、`BikeStationShareSchemeMerchantInfoServiceImpl` 等 |
| C3 | 实体类构造 | `new MPJLambdaWrapper<>(MerchantRoleEntity.class)` | `MerchantRoleAppService`、`UserDiscountAppService`、`MerchantTeamInfoServiceImpl` |
| C4 | **实体类 + 表别名构造** | `new MPJLambdaWrapper<>(OrderInvoiceApplyEntity.class, "apply_info")` | `CarInvoiceAppService.buildOrderRelApplyJoinQuery` |
| C5 | 全限定名构造（无 import） | `new com.github.yulichang.wrapper.MPJLambdaWrapper<X>()` | `AbstractOperationStatsInfoPushService` |
| C6 | 由私有工厂方法返回再续链 | `baseWrapper().eq(...)` | `GiftFundAppService`、`MerchantWalletSettlementAppService`、merchant-admin 的 `CarDeviceInfoAppService`/`BikeDeviceInfoAppService` 等 |

---

## 4. 去重后的用法模式清单（核心测试语料）

> 每条模式 = 一个**可独立转换为 SQL 的用法形态**。模式编号 `P01~P20` 为稳定标识。
> 位置格式：`{模块}/{类名}#{方法}`；代码为代表性节选（已还原泛型尖括号）。模块缩写：admin / merchant / merchant-admin / consumer / bridge / modules / integration。

### P01 单条详情查询：`selectAll + selectAs + leftJoin + eq (+orderBy)`

典型三表联查，`selectAll` 主表全列 + `selectAs` 关联表列映射到 VO。

```java
MPJLambdaWrapper<CarDeviceInfoEntity> wrapper = new MPJLambdaWrapper<CarDeviceInfoEntity>()
        .selectAll(CarDeviceInfoEntity.class)
        .selectAs(CarDeviceInfoEntity::getId, DeviceDetailVO::getDeviceId)
        .selectAs(CarStationInfoEntity::getStationName, DeviceDetailVO::getStationName)
        .leftJoin(CarStationInfoEntity.class, CarStationInfoEntity::getId, CarDeviceInfoEntity::getStationId)
        .leftJoin(CarDeviceProductInfoEntity.class, CarDeviceProductInfoEntity::getId, CarDeviceInfoEntity::getProductId)
        .eq(CarDeviceInfoEntity::getId, deviceId);
DeviceDetailVO vo = carDeviceInfoService.selectJoinOne(DeviceDetailVO.class, wrapper);
```

**出现位置**：`merchant/DeviceManagementService#queryCarDeviceDetail`、`merchant/DeviceManagementService#queryBikeDeviceDetail`、`merchant/ChargingPortManagementService#queryCarChargingPortDetail`（3 个 leftJoin）、`merchant/ChargingPortManagementService#queryBikeChargingPortDetail`、`admin/BikeDeviceInfoAppService#getById`、`admin/CarDeviceInfoAppService#getById`、`admin/IcCardInfoAppService#getDetail`、`admin/UserDiscountAppService#detail`、`modules/IcCardInfoServiceImpl`（2 处）、`modules/MerchantTeamInfoServiceImpl`、`modules/UserVinServiceImpl`、`consumer/CarStationAppService#getDetail`、`bridge/PlatformService#findBasicPushPortDeviceViewByStationIdAndPortId`。

**测试要点**：多表 `leftJoin` 顺序、主表与关联表字段混排、`selectJoinOne` 返回值映射。

---

### P02 分页列表查询：`selectAll + selectAs + leftJoin + 动态条件 + orderByDesc + selectJoinListPage`

条件均带 `condition` 布尔参数（如 `StringUtils.hasText(...)`、`dto.getX()!=null`），未命中时忽略该条件。

```java
MPJLambdaWrapper<CarStationInfoEntity> wrapper = new MPJLambdaWrapper<CarStationInfoEntity>()
        .selectAll(CarStationInfoEntity.class)
        .selectAs(MerchantEntity::getName, CarStationInfoVO::getManagerName)
        .like(StringUtils.hasText(dto.getStationCode()), CarStationInfoEntity::getStationCode, dto.getStationCode())
        .eq(dto.getId()!=null, CarStationInfoEntity::getId, dto.getId())
        .leftJoin(MerchantEntity.class, MerchantEntity::getId, CarStationInfoEntity::getManagerId)
        .orderByDesc(CarStationInfoEntity::getCreateTime);
Page<CarStationInfoVO> page = carStationInfoService.selectJoinListPage(
        new Page<>(dto.getPageNum(), dto.getPageSize()), CarStationInfoVO.class, wrapper);
```

**出现位置**：`admin/CarStationInfoAppService#page`、`admin/BikeStationInfoAppService#page`、`admin/CarDeviceInfoAppService#page`、`admin/BikeDeviceInfoAppService#page`、`admin/MerchantWalletSettlementAppService#page`、`admin/MerchantWalletWithdrawAppService#list`、`admin/MerchantShareRecordAppService#page`、`admin/UserDiscountAppService#page`、`admin/IcCardInfoAppService#list`、`admin/UserAppService#carOrder/#bikeOrder/#carOccupyOrder/#walletFlow`、`merchant-admin/CarStationInfoAppService#page`、`merchant-admin/BikeStationInfoAppService#page`、`merchant-admin/BikeDeviceInfoAppService#page`、`merchant-admin/CarDeviceInfoAppService#page`、`merchant/IcCardInfoAppService#page`、`merchant/HomeService#queryCarDevicePorts/#queryBikeDevicePorts`、`merchant/StationInfoService`（部分）、`consumer/CarOccupyAppService#page`、`bridge/PlatformService#pageStationConfig`、`bridge/PlatformService#pageThirdPlatformOrder`、`bridge/PlatformService#pageThirdPlatformByPlatformCode`。

**测试要点**：`eq/like/likeRight/ge/le` 的条件参数重载（第一个参数为 boolean）；`selectJoinListPage` 的 `Page` 参数为泛型 `Page<R>`；多条件组合、双 `orderByDesc`。

---

### P03 私有工厂方法返回 Wrapper + 跨方法续链

Wrapper 由 `baseWrapper()` / `buildQueryWrapper(dto)` 等私有方法构建并返回，调用方继续 `.eq(...)`、`.orderByDesc(...)`。**插件需要跨方法追踪 Wrapper 变量**。

```java
// 工厂方法（返回可变 MPJ wrapper）
private MPJLambdaWrapper<GiftFundEntity> baseJoinWrapper() {
    return new MPJLambdaWrapper<GiftFundEntity>()
            .selectAll(GiftFundEntity.class)
            .selectAs(MerchantEntity::getName, GiftFundVO::getMerchantName)
            .leftJoin(MerchantEntity.class, MerchantEntity::getId, GiftFundEntity::getMerchantId);
}
// 调用方续链
MPJLambdaWrapper<GiftFundEntity> wrapper = baseJoinWrapper()
        .eq(StrUtil.isNotBlank(dto.getGiftFundCode()), GiftFundEntity::getGiftFundCode, dto.getGiftFundCode())
        .orderByDesc(GiftFundEntity::getCreateTime);
```

**出现位置**：`admin/GiftFundAppService#baseJoinWrapper/#list/#detail`、`admin/MerchantWalletSettlementAppService#baseWrapper/#page`、`admin/MerchantWalletWithdrawAppService#baseWithdrawJoinWrapper/#buildWithdrawJoinWrapper/#applyWithdrawFilters`、`admin/MerchantShareRecordAppService#buildQueryWrapper/#buildExportWrapper`、`merchant-admin/CarDeviceInfoAppService#baseWrapper/#page/#getById`、`merchant-admin/BikeDeviceInfoAppService#baseWrapper`、`merchant-admin/MerchantShareRecordAppService#buildQueryWrapper`、`admin/CarInvoiceAppService#buildOrderRelApplyJoinQuery`。

**测试要点**：Wrapper 在方法间传递仍为**同一可变对象**；`buildExportWrapper` 返回后继续 `.last("LIMIT n")`；一处 wrapper 被 `selectJoinListPage` 与 `selectJoinCount` 复用。

---

### P04 下拉/回显：`select + selectAs` 两列映射到 DropListVO

无 join，主表 `select(Entity::getId)` + `selectAs(Entity::getName, DropListVO::getValue)`。

```java
return bikeDeviceProductInfoService.selectJoinList(DropListVO.class,
        new MPJLambdaWrapper<BikeDeviceProductInfoEntity>()
                .select(BikeDeviceProductInfoEntity::getId)
                .selectAs(BikeDeviceProductInfoEntity::getModel, DropListVO::getValue));
```

**出现位置**：`admin/BikeDeviceProductInfoAppService#dropAll`、`admin/BikeStationInfoAppService#getDropList`、`admin/CarStationInfoAppService#getDropList`、`admin/CarStationInfoAppService#getDropListMerchant`（含 leftJoin + orderByDesc）、`admin/MerchantRoleAppService#getDropList`、`admin/UserAppService#listAllForDropList`、`admin/HelpCenterInfoAppService#listAllDropList`（含 `ne`）、`merchant-admin/RoleAppService#getDropList`、`consumer/CarStationAppService#listAllEnabledStationTags`、`admin/BikeDeviceInfoAppService#listUnbound`（含 `isNull`）、`admin/CarDeviceInfoAppService#listUnbound`。

**测试要点**：`select` varargs（单列/多列）、`selectAs` 别名映射、无 join 的纯单表场景、`isNull`/`ne` 条件。

---

### P05 `distinct + innerJoin` 多级联表去重

连续两次 `innerJoin` 关联到二级表，`distinct` 去重后取主表外键列；同一链同时被 `selectJoinList(Long.class, ...)` 与 `selectJoinCount(wrapper)` 消费。

```java
MPJLambdaWrapper<CarStationShareSchemeMerchantInfoEntity> schemeWrapper = new MPJLambdaWrapper<>();
schemeWrapper.select(CarStationShareSchemeInfoEntity::getStationId)
        .distinct()
        .innerJoin(CarStationShareSchemeInfoEntity.class,
                CarStationShareSchemeInfoEntity::getId, CarStationShareSchemeMerchantInfoEntity::getSchemeId)
        .innerJoin(CarStationInfoEntity.class,
                CarStationInfoEntity::getId, CarStationShareSchemeInfoEntity::getStationId)
        .eq(CarStationShareSchemeMerchantInfoEntity::getMerchantId, merchantId);
return carStationShareSchemeMerchantInfoService.selectJoinList(Long.class, schemeWrapper);
```

**出现位置**：`merchant/StationInfoService#getCarStationIdsByMerchantId/#getBikeStationIdsByMerchantId/#countCarStationIdsByMerchantId/#countBikeStationIdsByMerchantId`、`modules/CarStationShareSchemeMerchantInfoServiceImpl`、`modules/BikeStationShareSchemeMerchantInfoServiceImpl`。

**测试要点**：`distinct` 生成 `SELECT DISTINCT`；多级 `innerJoin`；`selectJoinCount` 需忽略 select 列生成 `COUNT(*)`。

---

### P06 `selectCollection` 一对多集合映射（详情聚合）

`selectCollection(子表类, 目标VO::get集合, map -> map.id(...).result(...))` 生成一对多子查询；可与多个 `leftJoin` 叠加。

```java
MPJLambdaWrapper<BizCarOccupyRecordEntity> wrapper = new MPJLambdaWrapper<BizCarOccupyRecordEntity>()
        .selectAll(BizCarOccupyRecordEntity.class)
        .selectCollection(
                BizCarOccupyConsumptionRecordEntity.class,
                CarOccupyRecordDetailVO::getConsumptions,
                map -> map
                        .id(BizCarOccupyConsumptionRecordEntity::getId, CarOccupyRecordConsumptionItemVO::getId)
                        .result(BizCarOccupyConsumptionRecordEntity::getLadder, CarOccupyRecordConsumptionItemVO::getLadder)
                        .result(BizCarOccupyConsumptionRecordEntity::getFee, CarOccupyRecordConsumptionItemVO::getFee))
        .leftJoin(BizCarOccupyConsumptionRecordEntity.class,
                BizCarOccupyConsumptionRecordEntity::getOccupyId, BizCarOccupyRecordEntity::getId)
        .eq(BizCarOccupyRecordEntity::getId, id)
        .orderByAsc(BizCarOccupyConsumptionRecordEntity::getLadder);
```

**变体**：`selectCollection(类, VO::get集合)` 简写（无子映射）见 `admin/UserAppService#wallet`、`modules/GiftFundServiceImpl`、`modules/MerchantTeamInfoServiceImpl`；单条内两个 `selectCollection` 见 `admin/CarOccupyRecordAppService#detail`、`admin/UserAppService#basic`。

**出现位置**：`admin/CarOccupyRecordAppService#detail`、`admin/CarStationPriceTaskRecordAppService#getById`、`admin/UserAppService#basic`（2 个）、`modules/BizCarStationOccupyInfoServiceImpl`（2 处）、`consumer/CarDeviceAppService#getDetailByDeviceCode`、`consumer/CarOccupyAppService#detail`。

**测试要点**：`selectCollection` 子映射 `id/result`；多 `selectCollection`；`selectCollection` + `selectAll` + 多 `leftJoin` 组合；一对多结果按 `orderByAsc(子表列)` 排序。

---

### P07 `selectCollection` 内嵌套 `collection` 三层关联

子映射中继续 `.collection(孙表类, 孙VO::get集合)`。

```java
.selectCollection(CarStationPriceTaskStationRelEntity.class, CarStationPriceTaskExecutionBO::getStations,
        stationMap -> stationMap
                .id(CarStationPriceTaskStationRelEntity::getId, CarStationPriceTaskExecutionStationBO::getRelationId)
                .result(CarStationPriceTaskStationRelEntity::getStationId, CarStationPriceTaskExecutionStationBO::getStationId)
                .collection(CarDeviceInfoEntity.class, CarStationPriceTaskExecutionStationBO::getDevices))
.selectCollection(CarStationPriceTaskItemRecordEntity.class, CarStationPriceTaskExecutionBO::getPriceItems)
```

**出现位置**：`modules/CarStationPriceTaskRecordServiceImpl`（同时 `innerJoin` + `leftJoin` + 3 个 `orderByAsc`）。

**测试要点**：两级集合嵌套的 SQL 形态（多级子查询/分组）、`selectCollection` 简写与带映射混用。

---

### P08 `selectAssociation` 一对一关联映射

```java
MPJLambdaWrapper<CarDeviceInfoEntity> wrapper = new MPJLambdaWrapper<CarDeviceInfoEntity>()
        .selectAll(CarDeviceInfoEntity.class)
        .selectAssociation(CarDeviceProductInfoEntity.class, CarDeviceInfoBO::getProductInfo)
        .leftJoin(CarDeviceProductInfoEntity.class, CarDeviceProductInfoEntity::getId, CarDeviceInfoEntity::getProductId)
        .eq(CarDeviceInfoEntity::getId, stationId);
return Optional.ofNullable(selectJoinOne(CarDeviceInfoBO.class, wrapper)).map(this::getDeviceInfoBO);
```

**出现位置**：`bridge/PlatformService#findCarDeviceInfoByStationIds`、`modules/CarDeviceInfoServiceImpl`（4 处：`getById`、按 IDs 列表、按 deviceCode、按 deviceCodes）。

**测试要点**：`selectAssociation` 一对一映射、结果后处理（`stream().map(...).toList()`）。

---

### P09 `selectFunc` 自定义 SQL 聚合函数（统计查询）

`selectFunc("COUNT(%s)", func -> func.accept(列).values(参数), "别名")`，`%s` 占列、`{0}`/`{1}` 占绑定参数。

```java
MPJLambdaWrapper<MerchantWalletWithdrawEntity> wrapper = applyWithdrawFilters(baseWithdrawQueryWrapper(), dto)
        .selectFunc("COUNT(%s)", func -> func.accept(MerchantWalletWithdrawEntity::getId), "totalCount")
        .selectFunc("COUNT(CASE WHEN %s = {0} AND %s = {1} THEN 1 END)",
                func -> func.accept(MerchantWalletWithdrawEntity::getDicAuditStatus,
                                MerchantWalletWithdrawEntity::getDicWithdrawStatus)
                        .values(DicConstants.DIC_VALUE_MERCHANT_WALLET_AUDIT_APPROVED,
                                DicConstants.DIC_VALUE_MERCHANT_WITHDRAW_PROCESSING),
                "paymentProcessingCount")
        .selectFunc("COALESCE(SUM(%s), 0)",
                func -> func.accept(MerchantWalletWithdrawEntity::getWithdrawAmount), "totalWithdrawAmount");
return merchantWalletWithdrawService.selectJoinOne(MerchantWalletWithdrawStatisticsVO.class, wrapper);
```

**出现位置**：`admin/MerchantWalletWithdrawAppService#queryStatistics`（共 9 个 `selectFunc`，含 COUNT/CASE WHEN/COALESCE/SUM）。

**测试要点**：`%s` 列占位符替换、`{0}` 参数替换、`func.accept(...)` 多列、`func.values(...)` 多值、别名列名映射到 VO 字段。

---

### P10 `apply` 动态/参数化 SQL 片段

```java
// 形式一：无条件 SQL 片段
wrapper.apply("1 = 0");
// 形式二：带 boolean 条件 + 占位参数 {0} {1}
wrapper.apply(ObjectUtil.isNotNull(lastApplyId) && lastApplyId > 0,
        "(rel.apply_id, rel.id) > ({0}, {1})", lastApplyId, ObjectUtil.isNull(lastRelId) ? 0L : lastRelId);
// 形式三：拼接外部 SQL（复合条件）
String sql = buildStationIdsFilterSql(columnName, stationIds);  // 如 "(x.station_ids) && (...)"
wrapper.apply(sql);
```

**出现位置**：`admin/CarInvoiceAppService#applyBusinessOpenInfoFilter`（`apply("1 = 0")` + `in`）、`admin/CarInvoiceAppService#applyStationIdsFilter`、`admin/CarInvoiceAppService#listOrderRelByApplyJoinCursor`（参数化 apply）、`admin/GiftFundAppService#list`（`apply(condition, buildShowStatusCondition(...))`）。

**测试要点**：`apply` 原样透传 SQL；`{0}/{1}` 绑定参数替换；带 boolean 条件的重载。

---

### P11 表别名构造 + 字符串列 + 带别名 join

```java
MPJLambdaWrapper<OrderInvoiceApplyEntity> wrapper = new MPJLambdaWrapper<>(OrderInvoiceApplyEntity.class, "apply_info")
        .selectAs(OrderInvoiceApplyOrderRelEntity::getId, CarInvoiceOrderExportJoinRow::getId)
        .innerJoin(OrderInvoiceApplyOrderRelEntity.class, "rel",
                OrderInvoiceApplyOrderRelEntity::getApplyId, OrderInvoiceApplyEntity::getId)
        .likeRight(StrUtil.isNotBlank(dto.getApplyNo()), OrderInvoiceApplyEntity::getApplyNo, dto.getApplyNo())
        .ge(ObjectUtil.isNotNull(dto.getStartTime()), OrderInvoiceApplyEntity::getCreateTime, dto.getStartTime());

// 字符串列 + 带别名 join（auditAccount）
.selectAs(AUDIT_ACCOUNT_ALIAS + ".username", MerchantWalletWithdrawVO::getAuditedUsername)
.leftJoin(AccountEntity.class, AUDIT_ACCOUNT_ALIAS, AccountEntity::getId, MerchantWalletWithdrawEntity::getAuditedBy)
```

**出现位置**：`admin/CarInvoiceAppService#buildOrderRelApplyJoinQuery`（别名 `apply_info`）、`admin/MerchantWalletWithdrawAppService#baseWithdrawJoinWrapper/#baseWithdrawQueryWrapper`（别名 `auditAccount`，字符串列 + 别名 join 配合）。

**测试要点**：带别名构造（`new MPJLambdaWrapper<>(Class, alias)`）、`leftJoin(Class, alias, ...)` 四参重载、`selectAs("alias.column", ...)` 字符串列映射、`orderByAsc("alias", Entity::getX)` 别名排序。

---

### P12 `last` 分页 / 游标截断

```java
return Optional.ofNullable(carOrderMonitorRecordService.getOne(
        new MPJLambdaWrapper<CarOrderMonitorRecordEntity>()
                .eq(CarOrderMonitorRecordEntity::getOrderId, orderId)
                .orderByDesc(CarOrderMonitorRecordEntity::getTime)
                .last("limit 1")));
```

**出现位置**：`bridge/PlatformService`（`last("limit 1")` 4 处：`findActivePushAccountByStationIdAndPlatformCode`、`findPushPortDeviceViewByStationIdAndPortId`、`findBasicPushPortDeviceViewByStationIdAndPortId`、`findLatestThirdOrderMonitorByOrderId`、`findLatestOrderMonitorByOrderCode`）、`consumer/CarOccupyAppService#getLastUnpaid`、`admin/CarInvoiceAppService#listOrderRelByApplyJoinCursor`、`admin/MerchantShareRecordAppService#listNextDetails`、`admin/MerchantWalletWithdrawAppService`（导出批量）、`modules/UserVinServiceImpl`（`last("limit 1")`）。

**测试要点**：`last` 原样追加到 SQL 末尾（不换行、不改写）；与 `orderByDesc` 组合的"取最新一条"；导出游标 `last("LIMIT " + N)`。

---

### P13 `leftJoin` lambda 形式 ON 条件

```java
wrapper.leftJoin(GiftFundStationEntity.class, on -> on
                .eq(GiftFundStationEntity::getGiftFundId, GiftFundEntity::getId)
                .eq(GiftFundStationEntity::getStationId, stationId)
                .eq(GiftFundStationEntity::getDicStationCategory, dicStationCategory))
        .and(w -> w.eq(GiftFundEntity::getDicGiftType, DicConstants.DIC_VALUE_GIFT_FUND_TYPE_GENERAL)
                .or(t -> t.eq(GiftFundEntity::getDicGiftType, DicConstants.DIC_VALUE_GIFT_FUND_TYPE_TARGETED)
                        .isNotNull(GiftFundStationEntity::getId)));
```

**出现位置**：`modules/GiftFundServiceImpl`（2 处）。

**测试要点**：`leftJoin(Class, on -> on.eq(...))` lambda ON；`on` 内多条件 `eq`；与 `.and(w -> ...or(t -> ...isNotNull(...)))` 叠加。

---

### P14 `and` / `or` 嵌套复合条件

```java
wrapper.and(condition -> condition
        .gt(SysMerchantShareRecordEntity::getDicBizType, cursor.dicBizType)
        .or(or -> or.eq(SysMerchantShareRecordEntity::getDicBizType, cursor.dicBizType)
                .gt(SysMerchantShareRecordEntity::getBizId, cursor.bizId))
        .or(or -> or.eq(SysMerchantShareRecordEntity::getDicBizType, cursor.dicBizType)
                .eq(SysMerchantShareRecordEntity::getBizId, cursor.bizId)
                .gt(SysMerchantShareRecordEntity::getMerchantId, cursor.merchantId)));

// 无参 or() 作分隔符（AND ( a = ? OR b = ? )）
wrapper.and(wr -> wr.eq(UserVinEntity::getVin, vin)
        .or()
        .eq(UserVinEntity::getVin, new StringBuilder(vin).reverse().toString()));
```

**出现位置**：`admin/MerchantShareRecordAppService#appendCursorCondition`（3 层嵌套）、`merchant-admin/MerchantShareRecordAppService#appendCursorCondition`、`merchant/HomeService#applyCarKeywordFilter`（`and(w -> w.like(...).or().like(...).or().like(...))`）、`merchant/HomeService#applyCarDicStatusFilter`（`and(w -> w.eq(...).or().eq(...))`）、`merchant/IcCardInfoAppService#page`、`modules/UserVinServiceImpl`、`modules/GiftFundServiceImpl`。

**测试要点**：`and(lambda)` / `or(lambda)` 嵌套、无参 `or()` 分隔符、参数可为 `new StringBuilder(...).reverse()` 表达式。

---

### P15 条件式续链（if/else 分叉）

```java
MPJLambdaWrapper<CarDeviceInfoEntity> wrapper = new MPJLambdaWrapper<CarDeviceInfoEntity>()
        .selectAll(CarDeviceInfoEntity.class)
        .selectAssociation(CarDeviceProductInfoEntity.class, CarDeviceInfoBO::getProductInfo)
        .leftJoin(CarDeviceProductInfoEntity.class, CarDeviceProductInfoEntity::getId, CarDeviceInfoEntity::getProductId);
if (stationIds.size() == 1) {
    wrapper.eq(CarDeviceInfoEntity::getStationId, stationIds.get(0));
} else {
    wrapper.in(CarDeviceInfoEntity::getStationId, stationIds);
}
```

**出现位置**：`bridge/PlatformService#findCarDeviceInfoByStationIds`（if/else eq vs in）、`modules/GiftFundServiceImpl`（`if (stationId != null && dicStationCategory != null)` 追加 `leftJoin` + `and`）。

**测试要点**：Wrapper 被局部变量持有后按分支追加条件；分支内 `in(List)` 单元素等价性。

---

### P16 Wrapper 作为方法入参就地修改

```java
private void applyWithdrawFilters(MPJLambdaWrapper<MerchantWalletWithdrawEntity> wrapper, DTO dto) {
    wrapper.like(StrUtil.isNotBlank(dto.getWithdrawCode()), MerchantWalletWithdrawEntity::getWithdrawCode, dto.getWithdrawCode())
            .eq(dto.getMerchantId() != null, MerchantWalletWithdrawEntity::getMerchantId, dto.getMerchantId())
            .ge(dto.getStartTime() != null, MerchantWalletWithdrawEntity::getCreateTime, dto.getStartTime())
            .le(dto.getEndTime() != null, MerchantWalletWithdrawEntity::getCreateTime, dto.getEndTime());
}
```

**出现位置**：`admin/MerchantWalletWithdrawAppService#applyWithdrawFilters`、`admin/CarInvoiceAppService#applyBusinessOpenInfoFilter/#applyStationIdsFilter`、`admin/MerchantShareRecordAppService#appendCursorCondition`、`merchant-admin/MerchantShareRecordAppService#appendCursorCondition`、`merchant/HomeService#applyCarKeywordFilter/#applyCarDicStatusFilter/#applyBikeKeywordFilter/#applyBikeDicStatusFilter`。

**测试要点**：`MPJLambdaWrapper<T>` 作为入参类型；入参方法内继续链式；与 P03 工厂模式配合。

---

### P17 原生 MyBatis-Plus 方法消费 MPJ wrapper

```java
// 删除（remove）
if (!bikeDevicePortInfoService.remove(new MPJLambdaWrapper<BikeDevicePortInfoEntity>()
        .eq(BikeDevicePortInfoEntity::getDeviceId, id))) { ... }

// getOne / count（IService 原生）
Optional.ofNullable(carOrderMonitorRecordService.getOne(
        new MPJLambdaWrapper<CarOrderMonitorRecordEntity>().eq(...).orderByDesc(...).last("limit 1")));
long count = carOrderMonitorRecordService.count(new MPJLambdaWrapper<...>().eq(...).ge(...).lt(...));

// BaseMapper.selectList（仅条件场景）
List<CarStationChargingSummaryDayEntity> list = carStationChargingSummaryDayMapper.selectList(
        new MPJLambdaWrapper<CarStationChargingSummaryDayEntity>()
                .eq(...).ge(...).le(...));

// MPJBaseMapper.selectJoinList 直调
return carDeviceInfoMapper.selectJoinList(DeviceShareMerchantJoinBO.class, w);

// IService.list（MPJLambdaQueryWrapper 字符串版）
merchantSubAccountStationService.list(new MPJLambdaQueryWrapper<MerchantSubAccountStationEntity>()
        .select(MerchantSubAccountStationEntity::getStationId, ...)
        .eq(...))
```

**出现位置**：`admin/BikeDeviceInfoAppService#delete`（remove）、`bridge/PlatformService`（getOne 2 处、count 1 处）、`bridge/CommonThirdPlatformOpenApiServiceImpl`（selectList 3 处）、`bridge/AbstractOperationStatsInfoPushService`（selectList 3 处）、`modules/CarChargingSummaryDayServiceImpl`（selectList 2 处）、`modules/SysMerchantShareRecordServiceImpl`（Mapper selectJoinList 2 处）、`merchant/SubAccountAppService#configStationEcho`（list + MPJLambdaQueryWrapper）。

**测试要点**：MPJ wrapper 兼容喂给原生 MP 方法；`getOne`+`last("limit 1")` 取首行；`MPJLambdaQueryWrapper` 字符串字段版仅此一处。

---

### P18 聚合：`selectMax` / `selectCount` + `groupBy`

```java
// selectMax 单聚合
MPJLambdaWrapper<CarOrderMonitorRecordEntity> wrapper = new MPJLambdaWrapper<CarOrderMonitorRecordEntity>()
        .selectMax(CarOrderMonitorRecordEntity::getPower)
        .eq(CarOrderMonitorRecordEntity::getOrderId, order.getId());
List<BigDecimal> powerList = carOrderMonitorRecordService.selectJoinList(BigDecimal.class, wrapper);

// selectCount + 多 groupBy
new MPJLambdaWrapper<OrderInvoiceApplyEntity>()
        .selectCount(OrderInvoiceApplyEntity::getId, InvoiceReloginSmsReminderBO::getApplyCount)
        .innerJoin(CarHuifuInvoiceBusinessOpenInfoEntity.class,
                CarHuifuInvoiceBusinessOpenInfoEntity::getHuifuId, OrderInvoiceApplyEntity::getHuifuId)
        .in(OrderInvoiceApplyEntity::getDicApplyStatus, REMIND_APPLY_STATUSES)
        .isNotNull(OrderInvoiceApplyEntity::getHuifuId)
        .ne(OrderInvoiceApplyEntity::getHuifuId, StrUtil.EMPTY)
        .groupBy(OrderInvoiceApplyEntity::getHuifuId)
        .groupBy(CarHuifuInvoiceBusinessOpenInfoEntity::getContactName)
        .orderByAsc(OrderInvoiceApplyEntity::getHuifuId)
```

**出现位置**：`merchant/OrderManagementService#buildCarChargeCurveSummary`（selectMax + 映射 BigDecimal）、`integration/HuifuInvoiceReloginSmsReminderService`（selectCount + 3×groupBy + in/isNotNull/ne）。

**测试要点**：`selectMax/selectCount` 聚合列生成；`groupBy` 多列；`in(List)` 大集合；`ne(列, StrUtil.EMPTY)` 空串过滤；映射到基本类型 `BigDecimal.class`。

---

### P19 全限定名构造（无 import）

```java
carStationChargingSummaryDayMapper.selectList(
        new com.github.yulichang.wrapper.MPJLambdaWrapper<CarStationChargingSummaryDayEntity>()
                .eq(CarStationChargingSummaryDayEntity::getStationId, stationId)
                .eq(CarStationChargingSummaryDayEntity::getStartDate, startDate));
```

**出现位置**：`bridge/AbstractOperationStatsInfoPushService`（3 处）。

**测试要点**：PSI 解析需支持全限定类型名识别。

---

### P20 映射到非 VO 类型 / 结果流后处理

```java
// 映射基本类型
List<Long> ids = ...selectJoinList(Long.class, schemeWrapper);
List<BigDecimal> powers = ...selectJoinList(BigDecimal.class, wrapper);
// 映射实体本身
List<MerchantRoleEntity> roles = merchantRoleService.selectJoinList(MerchantRoleEntity.class, wrapper);
List<GiftFundEntity> funds = selectJoinList(GiftFundEntity.class, wrapper);
// 结果 stream 后处理
List<CarDeviceInfoBO> list = carDeviceInfoService.selectJoinList(CarDeviceInfoBO.class, wrapper)
        .stream().peek(dto -> {...}).toList();
```

**出现位置**：`merchant/StationInfoService`（Long.class 3 处）、`merchant/OrderManagementService`（BigDecimal.class）、`merchant-admin/AuthAppService`（MerchantRoleEntity.class）、`modules/GiftFundServiceImpl`（GiftFundEntity.class 2 处）、`bridge/PlatformService#findCarDeviceInfoByStationIds`（stream().peek）、`modules/CarDeviceInfoServiceImpl`（stream().map）。

**测试要点**：`Class<R>` 形参可为基本类型包装类、实体类；转换引擎不应假设目标类型必为 VO。

---

## 5. API 方法覆盖矩阵

> ✅=项目实际使用并给出语料；❌=项目中未出现（插件需自行造用例或用兼容性降级）。

### 5.1 链式 API（去重全集）

| 分类 | 方法 | 使用 | 代表模式 |
|------|------|:----:|----------|
| 构造 | `new MPJLambdaWrapper<>()` / `<T>()` / `<T>(Class)` / `<T>(Class, alias)` / 全限定名 | ✅ | C1~C6 |
| 列 | `select`（varargs） | ✅ | P04/P20 |
| 列 | `selectAll(Class)` | ✅ | P01/P02 |
| 列 | `selectAs(SFunction, SFunction)` | ✅ | P01/P02/P04 |
| 列 | `selectAs(String, SFunction)`（字符串列/别名列） | ✅ | P11 |
| 列 | `selectCollection(Class, SFunction)` / `(Class, SFunction, map)` | ✅ | P06/P07 |
| 列 | 子映射 `id` / `result` / `collection` | ✅ | P06/P07 |
| 列 | `selectAssociation(Class, SFunction)` | ✅ | P08 |
| 列 | `selectFunc(String, func->…, alias)` | ✅ | P09 |
| 列 | `selectMax` / `selectCount` | ✅ | P18 |
| 去重 | `distinct` | ✅ | P05 |
| 联表 | `leftJoin`（三参 / 带别名四参 / lambda on） | ✅ | P01/P11/P13 |
| 联表 | `innerJoin` | ✅ | P05/P17/P18 |
| 联表 | `rightJoin` | ❌ | – |
| 条件 | `eq` / `ne` / `gt` / `ge` / `lt` / `le` | ✅ | P01/P02/P14 |
| 条件 | `in` / `notIn` | ✅(in) / ❌(notIn) | P02/P17/P18 |
| 条件 | `like` / `notLike` / `likeLeft` / `likeRight` | ✅(like/likeRight) / ❌(notLike/likeLeft) | P02 |
| 条件 | `isNull` / `isNotNull` | ✅ | P04/P17 |
| 条件 | `between` / `notBetween` | ❌ | – |
| 逻辑 | `and(lambda)` / `or(lambda)` / `or()` | ✅ | P13/P14 |
| 逻辑 | `nested` | ❌（以 lambda 嵌套等价实现） | P14 |
| 分组 | `groupBy` | ✅ | P18 |
| 分组 | `having` | ❌ | – |
| 排序 | `orderByAsc` / `orderByDesc`（含 `orderByAsc("alias", …)`） | ✅ | P02/P11 |
| 尾缀 | `last` | ✅ | P12 |
| 片段 | `apply`（含参数化 `{0}`） | ✅ | P10 |
| 片段 | `set` / `setSql`（UPDATE） | ❌（MPJ 联表更新未使用） | – |

### 5.2 消费方法（去重全集）

| 方法 | 使用 | 代表模式 |
|------|:----:|----------|
| `selectJoinList` | ✅ | 多数 |
| `selectJoinOne` | ✅ | P01 |
| `selectJoinListPage` | ✅ | P02 |
| `selectJoinCount` | ✅ | P05 |
| `selectJoinPage` | ❌ | – |
| `selectJoinMaps` / `selectJoinMapsPage` | ❌ | – |
| `getOne` / `count` / `remove` / `selectList` / `list`（原生） | ✅ | P17 |
| Mapper 直调 `selectJoinList` | ✅ | P17 |

---

## 6. 插件兼容性测试建议

### 6.1 与插件功能编号（F-xx）的映射

| 插件功能 | 测试入口（本文档） |
|----------|--------------------|
| F-11 MPJLambdaWrapper 联表 SELECT | P01/P02/P03/P05/P08/P11/P13 全部 |
| F-12 MPJQueryWrapper 字符串版 | ⚠️ **项目中 0 使用**，需自造用例或标记"未在真实项目验证" |
| F-13 表名/列名解析（`@TableName`/`@TableField`/下划线策略） | 需补充实体注解收集（见 6.3） |
| F-02 参数替换 | P09（`{0}` 参数）、P10（apply 参数）、P14（表达式参数）、P18（in 大集合、`StrUtil.EMPTY`） |
| F-03 SQL 格式化 | P02（多子句）、P06（selectCollection 子查询）、P09（复杂函数列） |
| F-01 LambdaQueryWrapper | 本清单不覆盖（非 MPJ），另行收集 |

### 6.2 高风险 / 边界用例（优先测试）

1. **跨方法 Wrapper 传递**（P03/P16）：PSI 需解析 `baseWrapper()` 返回值并跟随续链，是插件实现难度最高点。
2. **带表别名**（P11）：`new MPJLambdaWrapper<>(Class, alias)`、`leftJoin(Class, alias, ...)`、`selectAs("alias.col", ...)` 需三处一致。
3. **`selectFunc` 自定义聚合**（P09）：`%s`/`{0}` 占位符 + `func.accept/values`，极易解析失败。
4. **`selectCollection` 嵌套**（P06/P07）：一对多甚至三层子查询，SQL 形态复杂。
5. **`apply` 参数化**（P10）：`{0}/{1}` 绑定与 `?` 参数混合。
6. **条件重载**（P02）：`eq(boolean, col, val)` 的第一个布尔参数不能被当作列名。
7. **`or()` 无参分隔符**（P14）：与 `and(lambda)` 括号配对。
8. **映射目标为基本类型/实体**（P20）：`selectJoinList(BigDecimal.class, ...)`。
9. **`MPJLambdaQueryWrapper`**：仅 1 处（`SubAccountAppService:242`），字符串版语法需单独验证。
10. **全限定名构造**（P19）：无 import 时的类型识别。

### 6.3 待补充（本清单之外）

- 实体类 `@TableName` / `@TableField` / `@TableId` 注解清单（F-13 需要）——可另行从 `cyzc-*-domain` 实体类收集。
- 原生 `LambdaQueryWrapper` / `LambdaUpdateWrapper` / `LambdaDeleteWrapper`（F-01/09/10）用法——非 MPJ，不在本清单范围。
- 多数据库方言实测 SQL 输出对比（F-14）——需连接真实数据库验证。

---

## 7. 附录：61 个源文件清单

> 路径已省略公共前缀 `src/main/java/com/cyzc/platform/`。

**cyzc-app/cyzc-app-admin**（20）
`admin/app/service/BikeDeviceInfoAppService.java`、`BikeDeviceProductInfoAppService.java`、`BikeOrderRecordAppService.java`、`BikeStationInfoAppService.java`、`CarDeviceInfoAppService.java`、`CarInvoiceAppService.java`、`CarOccupyRecordAppService.java`、`CarOrderRecordAppService.java`、`CarStationInfoAppService.java`、`CarStationPriceTaskRecordAppService.java`、`GiftFundAppService.java`、`HelpCenterInfoAppService.java`、`HuifuInvoiceAppService.java`、`IcCardInfoAppService.java`、`MerchantRoleAppService.java`、`MerchantShareRecordAppService.java`、`MerchantWalletSettlementAppService.java`、`MerchantWalletWithdrawAppService.java`、`UserAppService.java`、`UserDiscountAppService.java`

**cyzc-app/cyzc-app-merchant**（8）
`merchant/app/controller/UserController.java`、`merchant/app/service/SubAccountAppService.java`、`ChargingPortManagementService.java`、`DeviceManagementService.java`、`HomeService.java`、`IcCardInfoAppService.java`、`OrderManagementService.java`、`StationInfoService.java`

**cyzc-app/cyzc-app-merchant-admin**（11）
`merchantadmin/app/service/AuthAppService.java`、`BikeDeviceInfoAppService.java`、`BikeOrderRecordAppService.java`、`BikeStationInfoAppService.java`、`CarDeviceInfoAppService.java`、`CarOccupyRecordAppService.java`、`CarOrderRecordAppService.java`、`CarStationInfoAppService.java`、`MerchantShareRecordAppService.java`、`MerchantWalletSettlementAppService.java`、`RoleAppService.java`

**cyzc-app/cyzc-app-consumer**（4）
`consumer/app/service/BikeOrderAppService.java`、`CarDeviceAppService.java`、`CarOccupyAppService.java`、`CarStationAppService.java`

**cyzc-app/cyzc-app-bridge**（3）
`bridge/app/service/openapi/impl/CommonThirdPlatformOpenApiServiceImpl.java`、`bridge/app/service/platform/PlatformService.java`、`bridge/app/service/push/business/template/AbstractOperationStatsInfoPushService.java`

**cyzc-integration**（1）
`integration/service/HuifuInvoiceReloginSmsReminderService.java`

**cyzc-modules**（14）
`modules/service/impl/biz/BikeDevicePortInfoServiceImpl.java`、`BikeStationShareSchemeMerchantInfoServiceImpl.java`、`BizCarStationOccupyInfoServiceImpl.java`、`BizStationBarrierPushConfigRelServiceImpl.java`、`CarChargingSummaryDayServiceImpl.java`、`CarDeviceInfoServiceImpl.java`、`CarDevicePortInfoServiceImpl.java`、`CarStationPriceTaskRecordServiceImpl.java`、`CarStationShareSchemeMerchantInfoServiceImpl.java`、`IcCardInfoServiceImpl.java`；`modules/service/impl/sys/GiftFundServiceImpl.java`、`MerchantTeamInfoServiceImpl.java`、`SysMerchantShareRecordServiceImpl.java`、`UserVinServiceImpl.java`

---

## 8. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-12 | 初版：61 文件、约 150 处用法点、20 个去重模式、API 覆盖矩阵、测试建议 |
