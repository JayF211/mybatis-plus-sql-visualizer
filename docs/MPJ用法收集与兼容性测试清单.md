# MyBatis-Plus-Join（MPJ）用法收集与兼容性测试清单

> **文档用途**：为「MyBatis-Plus SQL Visualizer」IDEA 插件提供一套**完整的 MPJ 用法语料**（已跨文件去重），用于插件兼容性测试。
> 插件核心目标（F-11 MPJLambdaWrapper 联表、F-12 MPJQueryWrapper 字符串联表、F-02 参数替换、F-03 格式化、F-13 表名列名解析）需覆盖以下清单中的每一种模式。
>
> **脱敏说明**：本清单中的实体类名、模块名、方法名、常量名均已替换为通用示例名（如 `DeviceInfoEntity`、`app-admin`、`com.example.platform`），仅为保留 MPJ 语法结构而命名，与任何真实业务系统无关。
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
| C1 | 无参 + 链式启动 | `new MPJLambdaWrapper<DeviceInfoEntity>()` | 绝大多数 |
| C2 | 菱形推断 + 分步 `.` 续链 | `MPJLambdaWrapper<X> w = new MPJLambdaWrapper<>(); w.select(...)` | `StationInfoService`、`BikeStationSchemeMerchantInfoServiceImpl` 等 |
| C3 | 实体类构造 | `new MPJLambdaWrapper<>(RoleEntity.class)` | `RoleAppService`、`UserDiscountAppService`、`TeamInfoServiceImpl` |
| C4 | **实体类 + 表别名构造** | `new MPJLambdaWrapper<>(InvoiceApplyEntity.class, "apply_info")` | `InvoiceAppService.buildApplyJoinQuery` |
| C5 | 全限定名构造（无 import） | `new com.github.yulichang.wrapper.MPJLambdaWrapper<X>()` | `AbstractOperationStatsInfoPushService` |
| C6 | 由私有工厂方法返回再续链 | `baseWrapper().eq(...)` | `CouponAppService`、`WalletSettlementAppService`、merchant-admin 的 `DeviceInfoAppService`/`BikeDeviceInfoAppService` 等 |

---

## 4. 去重后的用法模式清单（核心测试语料）

> 每条模式 = 一个**可独立转换为 SQL 的用法形态**。模式编号 `P01~P20` 为稳定标识。
> 位置格式：`{模块}/{类名}#{方法}`；代码为代表性节选（已还原泛型尖括号）。模块缩写：admin / merchant / merchant-admin / consumer / bridge / modules / integration。

### P01 单条详情查询：`selectAll + selectAs + leftJoin + eq (+orderBy)`

典型三表联查，`selectAll` 主表全列 + `selectAs` 关联表列映射到 VO。

```java
MPJLambdaWrapper<DeviceInfoEntity> wrapper = new MPJLambdaWrapper<DeviceInfoEntity>()
        .selectAll(DeviceInfoEntity.class)
        .selectAs(DeviceInfoEntity::getId, DeviceDetailVO::getDeviceId)
        .selectAs(StationInfoEntity::getStationName, DeviceDetailVO::getStationName)
        .leftJoin(StationInfoEntity.class, StationInfoEntity::getId, DeviceInfoEntity::getStationId)
        .leftJoin(ProductInfoEntity.class, ProductInfoEntity::getId, DeviceInfoEntity::getProductId)
        .eq(DeviceInfoEntity::getId, deviceId);
DeviceDetailVO vo = deviceInfoService.selectJoinOne(DeviceDetailVO.class, wrapper);
```

**出现位置**：`merchant/DeviceManagementService#queryDeviceDetail`、`merchant/DeviceManagementService#queryBikeDeviceDetail`、`merchant/PortManagementService#queryPortDetail`（3 个 leftJoin）、`merchant/PortManagementService#queryBikePortDetail`、`admin/BikeDeviceInfoAppService#getById`、`admin/DeviceInfoAppService#getById`、`admin/IcCardInfoAppService#getDetail`、`admin/UserDiscountAppService#detail`、`modules/IcCardInfoServiceImpl`（2 处）、`modules/TeamInfoServiceImpl`、`modules/UserInfoServiceImpl`、`consumer/StationAppService#getDetail`、`bridge/PlatformService#findBasicPushDeviceViewByStationIdAndPortId`。

**测试要点**：多表 `leftJoin` 顺序、主表与关联表字段混排、`selectJoinOne` 返回值映射。

---

### P02 分页列表查询：`selectAll + selectAs + leftJoin + 动态条件 + orderByDesc + selectJoinListPage`

条件均带 `condition` 布尔参数（如 `StringUtils.hasText(...)`、`dto.getX()!=null`），未命中时忽略该条件。

```java
MPJLambdaWrapper<StationInfoEntity> wrapper = new MPJLambdaWrapper<StationInfoEntity>()
        .selectAll(StationInfoEntity.class)
        .selectAs(MerchantEntity::getName, StationInfoVO::getManagerName)
        .like(StringUtils.hasText(dto.getStationCode()), StationInfoEntity::getStationCode, dto.getStationCode())
        .eq(dto.getId()!=null, StationInfoEntity::getId, dto.getId())
        .leftJoin(MerchantEntity.class, MerchantEntity::getId, StationInfoEntity::getManagerId)
        .orderByDesc(StationInfoEntity::getCreateTime);
Page<StationInfoVO> page = stationInfoService.selectJoinListPage(
        new Page<>(dto.getPageNum(), dto.getPageSize()), StationInfoVO.class, wrapper);
```

**出现位置**：`admin/StationInfoAppService#page`、`admin/BikeStationInfoAppService#page`、`admin/DeviceInfoAppService#page`、`admin/BikeDeviceInfoAppService#page`、`admin/WalletSettlementAppService#page`、`admin/WalletWithdrawAppService#list`、`admin/ShareRecordAppService#page`、`admin/UserDiscountAppService#page`、`admin/IcCardInfoAppService#list`、`admin/UserAppService#orderRecord/#bikeOrderRecord/#occupyOrder/#walletFlow`、`merchant-admin/StationInfoAppService#page`、`merchant-admin/BikeStationInfoAppService#page`、`merchant-admin/BikeDeviceInfoAppService#page`、`merchant-admin/DeviceInfoAppService#page`、`merchant/IcCardInfoAppService#page`、`merchant/HomeService#queryDevicePorts/#queryBikeDevicePorts`、`merchant/StationInfoService`（部分）、`consumer/OccupyAppService#page`、`bridge/PlatformService#pageStationConfig`、`bridge/PlatformService#pageThirdPlatformOrder`、`bridge/PlatformService#pageThirdPlatformByPlatformCode`。

**测试要点**：`eq/like/likeRight/ge/le` 的条件参数重载（第一个参数为 boolean）；`selectJoinListPage` 的 `Page` 参数为泛型 `Page<R>`；多条件组合、双 `orderByDesc`。

---

### P03 私有工厂方法返回 Wrapper + 跨方法续链

Wrapper 由 `baseWrapper()` / `buildQueryWrapper(dto)` 等私有方法构建并返回，调用方继续 `.eq(...)`、`.orderByDesc(...)`。**插件需要跨方法追踪 Wrapper 变量**。

```java
// 工厂方法（返回可变 MPJ wrapper）
private MPJLambdaWrapper<CouponEntity> baseJoinWrapper() {
    return new MPJLambdaWrapper<CouponEntity>()
            .selectAll(CouponEntity.class)
            .selectAs(MerchantEntity::getName, CouponVO::getMerchantName)
            .leftJoin(MerchantEntity.class, MerchantEntity::getId, CouponEntity::getMerchantId);
}
// 调用方续链
MPJLambdaWrapper<CouponEntity> wrapper = baseJoinWrapper()
        .eq(StrUtil.isNotBlank(dto.getCouponCode()), CouponEntity::getCouponCode, dto.getCouponCode())
        .orderByDesc(CouponEntity::getCreateTime);
```

**出现位置**：`admin/CouponAppService#baseJoinWrapper/#list/#detail`、`admin/WalletSettlementAppService#baseWrapper/#page`、`admin/WalletWithdrawAppService#baseWithdrawJoinWrapper/#buildWithdrawJoinWrapper/#applyWithdrawFilters`、`admin/ShareRecordAppService#buildQueryWrapper/#buildExportWrapper`、`merchant-admin/DeviceInfoAppService#baseWrapper/#page/#getById`、`merchant-admin/BikeDeviceInfoAppService#baseWrapper`、`merchant-admin/ShareRecordAppService#buildQueryWrapper`、`admin/InvoiceAppService#buildApplyJoinQuery`。

**测试要点**：Wrapper 在方法间传递仍为**同一可变对象**；`buildExportWrapper` 返回后继续 `.last("LIMIT n")`；一处 wrapper 被 `selectJoinListPage` 与 `selectJoinCount` 复用。

---

### P04 下拉/回显：`select + selectAs` 两列映射到 DropListVO

无 join，主表 `select(Entity::getId)` + `selectAs(Entity::getName, DropListVO::getValue)`。

```java
return bikeProductInfoService.selectJoinList(DropListVO.class,
        new MPJLambdaWrapper<BikeProductInfoEntity>()
                .select(BikeProductInfoEntity::getId)
                .selectAs(BikeProductInfoEntity::getModel, DropListVO::getValue));
```

**出现位置**：`admin/BikeProductInfoAppService#dropAll`、`admin/BikeStationInfoAppService#getDropList`、`admin/StationInfoAppService#getDropList`、`admin/StationInfoAppService#getDropListMerchant`（含 leftJoin + orderByDesc）、`admin/RoleAppService#getDropList`、`admin/UserAppService#listAllForDropList`、`admin/HelpCenterInfoAppService#listAllDropList`（含 `ne`）、`merchant-admin/RoleAppService#getDropList`、`consumer/StationAppService#listAllEnabledStationTags`、`admin/BikeDeviceInfoAppService#listUnbound`（含 `isNull`）、`admin/DeviceInfoAppService#listUnbound`。

**测试要点**：`select` varargs（单列/多列）、`selectAs` 别名映射、无 join 的纯单表场景、`isNull`/`ne` 条件。

---

### P05 `distinct + innerJoin` 多级联表去重

连续两次 `innerJoin` 关联到二级表，`distinct` 去重后取主表外键列；同一链同时被 `selectJoinList(Long.class, ...)` 与 `selectJoinCount(wrapper)` 消费。

```java
MPJLambdaWrapper<StationSchemeMerchantInfoEntity> schemeWrapper = new MPJLambdaWrapper<>();
schemeWrapper.select(StationSchemeInfoEntity::getStationId)
        .distinct()
        .innerJoin(StationSchemeInfoEntity.class,
                StationSchemeInfoEntity::getId, StationSchemeMerchantInfoEntity::getSchemeId)
        .innerJoin(StationInfoEntity.class,
                StationInfoEntity::getId, StationSchemeInfoEntity::getStationId)
        .eq(StationSchemeMerchantInfoEntity::getMerchantId, merchantId);
return stationSchemeMerchantInfoService.selectJoinList(Long.class, schemeWrapper);
```

**出现位置**：`merchant/StationInfoService#getStationIdsByMerchantId/#getBikeStationIdsByMerchantId/#countStationIdsByMerchantId/#countBikeStationIdsByMerchantId`、`modules/StationSchemeMerchantInfoServiceImpl`、`modules/BikeStationSchemeMerchantInfoServiceImpl`。

**测试要点**：`distinct` 生成 `SELECT DISTINCT`；多级 `innerJoin`；`selectJoinCount` 需忽略 select 列生成 `COUNT(*)`。

---

### P06 `selectCollection` 一对多集合映射（详情聚合）

`selectCollection(子表类, 目标VO::get集合, map -> map.id(...).result(...))` 生成一对多子查询；可与多个 `leftJoin` 叠加。

```java
MPJLambdaWrapper<OccupyRecordEntity> wrapper = new MPJLambdaWrapper<OccupyRecordEntity>()
        .selectAll(OccupyRecordEntity.class)
        .selectCollection(
                OccupyConsumptionRecordEntity.class,
                OccupyRecordDetailVO::getConsumptions,
                map -> map
                        .id(OccupyConsumptionRecordEntity::getId, OccupyRecordConsumptionItemVO::getId)
                        .result(OccupyConsumptionRecordEntity::getLadder, OccupyRecordConsumptionItemVO::getLadder)
                        .result(OccupyConsumptionRecordEntity::getFee, OccupyRecordConsumptionItemVO::getFee))
        .leftJoin(OccupyConsumptionRecordEntity.class,
                OccupyConsumptionRecordEntity::getOccupyId, OccupyRecordEntity::getId)
        .eq(OccupyRecordEntity::getId, id)
        .orderByAsc(OccupyConsumptionRecordEntity::getLadder);
```

**变体**：`selectCollection(类, VO::get集合)` 简写（无子映射）见 `admin/UserAppService#wallet`、`modules/CouponServiceImpl`、`modules/TeamInfoServiceImpl`；单条内两个 `selectCollection` 见 `admin/OccupyRecordAppService#detail`、`admin/UserAppService#basic`。

**出现位置**：`admin/OccupyRecordAppService#detail`、`admin/StationPriceTaskRecordAppService#getById`、`admin/UserAppService#basic`（2 个）、`modules/StationOccupyInfoServiceImpl`（2 处）、`consumer/DeviceAppService#getDetailByDeviceCode`、`consumer/OccupyAppService#detail`。

**测试要点**：`selectCollection` 子映射 `id/result`；多 `selectCollection`；`selectCollection` + `selectAll` + 多 `leftJoin` 组合；一对多结果按 `orderByAsc(子表列)` 排序。

---

### P07 `selectCollection` 内嵌套 `collection` 三层关联

子映射中继续 `.collection(孙表类, 孙VO::get集合)`。

```java
.selectCollection(StationPriceTaskStationRelEntity.class, StationPriceTaskExecutionBO::getStations,
        stationMap -> stationMap
                .id(StationPriceTaskStationRelEntity::getId, StationPriceTaskExecutionStationBO::getRelationId)
                .result(StationPriceTaskStationRelEntity::getStationId, StationPriceTaskExecutionStationBO::getStationId)
                .collection(DeviceInfoEntity.class, StationPriceTaskExecutionStationBO::getDevices))
.selectCollection(StationPriceTaskItemRecordEntity.class, StationPriceTaskExecutionBO::getPriceItems)
```

**出现位置**：`modules/StationPriceTaskRecordServiceImpl`（同时 `innerJoin` + `leftJoin` + 3 个 `orderByAsc`）。

**测试要点**：两级集合嵌套的 SQL 形态（多级子查询/分组）、`selectCollection` 简写与带映射混用。

---

### P08 `selectAssociation` 一对一关联映射

```java
MPJLambdaWrapper<DeviceInfoEntity> wrapper = new MPJLambdaWrapper<DeviceInfoEntity>()
        .selectAll(DeviceInfoEntity.class)
        .selectAssociation(ProductInfoEntity.class, DeviceInfoBO::getProductInfo)
        .leftJoin(ProductInfoEntity.class, ProductInfoEntity::getId, DeviceInfoEntity::getProductId)
        .eq(DeviceInfoEntity::getId, stationId);
return Optional.ofNullable(selectJoinOne(DeviceInfoBO.class, wrapper)).map(this::getDeviceInfoBO);
```

**出现位置**：`bridge/PlatformService#findDeviceInfoByStationIds`、`modules/DeviceInfoServiceImpl`（4 处：`getById`、按 IDs 列表、按 deviceCode、按 deviceCodes）。

**测试要点**：`selectAssociation` 一对一映射、结果后处理（`stream().map(...).toList()`）。

---

### P09 `selectFunc` 自定义 SQL 聚合函数（统计查询）

`selectFunc("COUNT(%s)", func -> func.accept(列).values(参数), "别名")`，`%s` 占列、`{0}`/`{1}` 占绑定参数。

```java
MPJLambdaWrapper<WalletWithdrawEntity> wrapper = applyWithdrawFilters(baseWithdrawQueryWrapper(), dto)
        .selectFunc("COUNT(%s)", func -> func.accept(WalletWithdrawEntity::getId), "totalCount")
        .selectFunc("COUNT(CASE WHEN %s = {0} AND %s = {1} THEN 1 END)",
                func -> func.accept(WalletWithdrawEntity::getDicAuditStatus,
                                WalletWithdrawEntity::getDicWithdrawStatus)
                        .values(DicConstants.DIC_VALUE_WALLET_AUDIT_APPROVED,
                                DicConstants.DIC_VALUE_WITHDRAW_PROCESSING),
                "paymentProcessingCount")
        .selectFunc("COALESCE(SUM(%s), 0)",
                func -> func.accept(WalletWithdrawEntity::getWithdrawAmount), "totalWithdrawAmount");
return walletWithdrawService.selectJoinOne(WalletWithdrawStatisticsVO.class, wrapper);
```

**出现位置**：`admin/WalletWithdrawAppService#queryStatistics`（共 9 个 `selectFunc`，含 COUNT/CASE WHEN/COALESCE/SUM）。

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

**出现位置**：`admin/InvoiceAppService#applyBusinessOpenInfoFilter`（`apply("1 = 0")` + `in`）、`admin/InvoiceAppService#applyStationIdsFilter`、`admin/InvoiceAppService#listByApplyJoinCursor`（参数化 apply）、`admin/CouponAppService#list`（`apply(condition, buildShowStatusCondition(...))`）。

**测试要点**：`apply` 原样透传 SQL；`{0}/{1}` 绑定参数替换；带 boolean 条件的重载。

---

### P11 表别名构造 + 字符串列 + 带别名 join

```java
MPJLambdaWrapper<InvoiceApplyEntity> wrapper = new MPJLambdaWrapper<>(InvoiceApplyEntity.class, "apply_info")
        .selectAs(InvoiceApplyOrderRelEntity::getId, InvoiceOrderExportJoinRow::getId)
        .innerJoin(InvoiceApplyOrderRelEntity.class, "rel",
                InvoiceApplyOrderRelEntity::getApplyId, InvoiceApplyEntity::getId)
        .likeRight(StrUtil.isNotBlank(dto.getApplyNo()), InvoiceApplyEntity::getApplyNo, dto.getApplyNo())
        .ge(ObjectUtil.isNotNull(dto.getStartTime()), InvoiceApplyEntity::getCreateTime, dto.getStartTime());

// 字符串列 + 带别名 join（auditAccount）
.selectAs(AUDIT_ACCOUNT_ALIAS + ".username", WalletWithdrawVO::getAuditedUsername)
.leftJoin(AccountEntity.class, AUDIT_ACCOUNT_ALIAS, AccountEntity::getId, WalletWithdrawEntity::getAuditedBy)
```

**出现位置**：`admin/InvoiceAppService#buildApplyJoinQuery`（别名 `apply_info`）、`admin/WalletWithdrawAppService#baseWithdrawJoinWrapper/#baseWithdrawQueryWrapper`（别名 `auditAccount`，字符串列 + 别名 join 配合）。

**测试要点**：带别名构造（`new MPJLambdaWrapper<>(Class, alias)`）、`leftJoin(Class, alias, ...)` 四参重载、`selectAs("alias.column", ...)` 字符串列映射、`orderByAsc("alias", Entity::getX)` 别名排序。

---

### P12 `last` 分页 / 游标截断

```java
return Optional.ofNullable(orderMonitorRecordService.getOne(
        new MPJLambdaWrapper<OrderMonitorRecordEntity>()
                .eq(OrderMonitorRecordEntity::getOrderId, orderId)
                .orderByDesc(OrderMonitorRecordEntity::getTime)
                .last("limit 1")));
```

**出现位置**：`bridge/PlatformService`（`last("limit 1")` 4 处：`findActivePushAccountByStationIdAndPlatformCode`、`findPushDeviceViewByStationIdAndPortId`、`findBasicPushDeviceViewByStationIdAndPortId`、`findLatestOrderMonitorByOrderId`、`findLatestOrderMonitorByOrderCode`）、`consumer/OccupyAppService#getLastUnpaid`、`admin/InvoiceAppService#listByApplyJoinCursor`、`admin/ShareRecordAppService#listNextDetails`、`admin/WalletWithdrawAppService`（导出批量）、`modules/UserInfoServiceImpl`（`last("limit 1")`）。

**测试要点**：`last` 原样追加到 SQL 末尾（不换行、不改写）；与 `orderByDesc` 组合的"取最新一条"；导出游标 `last("LIMIT " + N)`。

---

### P13 `leftJoin` lambda 形式 ON 条件

```java
wrapper.leftJoin(CouponStationEntity.class, on -> on
                .eq(CouponStationEntity::getCouponId, CouponEntity::getId)
                .eq(CouponStationEntity::getStationId, stationId)
                .eq(CouponStationEntity::getDicStationCategory, dicStationCategory))
        .and(w -> w.eq(CouponEntity::getDicCouponType, DicConstants.DIC_VALUE_COUPON_TYPE_GENERAL)
                .or(t -> t.eq(CouponEntity::getDicCouponType, DicConstants.DIC_VALUE_COUPON_TYPE_TARGETED)
                        .isNotNull(CouponStationEntity::getId)));
```

**出现位置**：`modules/CouponServiceImpl`（2 处）。

**测试要点**：`leftJoin(Class, on -> on.eq(...))` lambda ON；`on` 内多条件 `eq`；与 `.and(w -> ...or(t -> ...isNotNull(...)))` 叠加。

---

### P14 `and` / `or` 嵌套复合条件

```java
wrapper.and(condition -> condition
        .gt(ShareRecordEntity::getDicBizType, cursor.dicBizType)
        .or(or -> or.eq(ShareRecordEntity::getDicBizType, cursor.dicBizType)
                .gt(ShareRecordEntity::getBizId, cursor.bizId))
        .or(or -> or.eq(ShareRecordEntity::getDicBizType, cursor.dicBizType)
                .eq(ShareRecordEntity::getBizId, cursor.bizId)
                .gt(ShareRecordEntity::getMerchantId, cursor.merchantId)));

// 无参 or() 作分隔符（AND ( a = ? OR b = ? )）
wrapper.and(wr -> wr.eq(UserInfoEntity::getCode, code)
        .or()
        .eq(UserInfoEntity::getCode, new StringBuilder(code).reverse().toString()));
```

**出现位置**：`admin/ShareRecordAppService#appendCursorCondition`（3 层嵌套）、`merchant-admin/ShareRecordAppService#appendCursorCondition`、`merchant/HomeService#applyKeywordFilter`（`and(w -> w.like(...).or().like(...).or().like(...))`）、`merchant/HomeService#applyStatusFilter`（`and(w -> w.eq(...).or().eq(...))`）、`merchant/IcCardInfoAppService#page`、`modules/UserInfoServiceImpl`、`modules/CouponServiceImpl`。

**测试要点**：`and(lambda)` / `or(lambda)` 嵌套、无参 `or()` 分隔符、参数可为 `new StringBuilder(...).reverse()` 表达式。

---

### P15 条件式续链（if/else 分叉）

```java
MPJLambdaWrapper<DeviceInfoEntity> wrapper = new MPJLambdaWrapper<DeviceInfoEntity>()
        .selectAll(DeviceInfoEntity.class)
        .selectAssociation(ProductInfoEntity.class, DeviceInfoBO::getProductInfo)
        .leftJoin(ProductInfoEntity.class, ProductInfoEntity::getId, DeviceInfoEntity::getProductId);
if (stationIds.size() == 1) {
    wrapper.eq(DeviceInfoEntity::getStationId, stationIds.get(0));
} else {
    wrapper.in(DeviceInfoEntity::getStationId, stationIds);
}
```

**出现位置**：`bridge/PlatformService#findDeviceInfoByStationIds`（if/else eq vs in）、`modules/CouponServiceImpl`（`if (stationId != null && dicStationCategory != null)` 追加 `leftJoin` + `and`）。

**测试要点**：Wrapper 被局部变量持有后按分支追加条件；分支内 `in(List)` 单元素等价性。

---

### P16 Wrapper 作为方法入参就地修改

```java
private void applyWithdrawFilters(MPJLambdaWrapper<WalletWithdrawEntity> wrapper, DTO dto) {
    wrapper.like(StrUtil.isNotBlank(dto.getWithdrawCode()), WalletWithdrawEntity::getWithdrawCode, dto.getWithdrawCode())
            .eq(dto.getMerchantId() != null, WalletWithdrawEntity::getMerchantId, dto.getMerchantId())
            .ge(dto.getStartTime() != null, WalletWithdrawEntity::getCreateTime, dto.getStartTime())
            .le(dto.getEndTime() != null, WalletWithdrawEntity::getCreateTime, dto.getEndTime());
}
```

**出现位置**：`admin/WalletWithdrawAppService#applyWithdrawFilters`、`admin/InvoiceAppService#applyBusinessOpenInfoFilter/#applyStationIdsFilter`、`admin/ShareRecordAppService#appendCursorCondition`、`merchant-admin/ShareRecordAppService#appendCursorCondition`、`merchant/HomeService#applyKeywordFilter/#applyStatusFilter/#applyBikeKeywordFilter/#applyBikeStatusFilter`。

**测试要点**：`MPJLambdaWrapper<T>` 作为入参类型；入参方法内继续链式；与 P03 工厂模式配合。

---

### P17 原生 MyBatis-Plus 方法消费 MPJ wrapper

```java
// 删除（remove）
if (!bikeDevicePortInfoService.remove(new MPJLambdaWrapper<BikeDevicePortInfoEntity>()
        .eq(BikeDevicePortInfoEntity::getDeviceId, id))) { ... }

// getOne / count（IService 原生）
Optional.ofNullable(orderMonitorRecordService.getOne(
        new MPJLambdaWrapper<OrderMonitorRecordEntity>().eq(...).orderByDesc(...).last("limit 1")));
long count = orderMonitorRecordService.count(new MPJLambdaWrapper<...>().eq(...).ge(...).lt(...));

// BaseMapper.selectList（仅条件场景）
List<StationChargingSummaryDayEntity> list = chargingSummaryDayMapper.selectList(
        new MPJLambdaWrapper<StationChargingSummaryDayEntity>()
                .eq(...).ge(...).le(...));

// MPJBaseMapper.selectJoinList 直调
return deviceInfoMapper.selectJoinList(DeviceShareMerchantJoinBO.class, w);

// IService.list（MPJLambdaQueryWrapper 字符串版）
subAccountStationService.list(new MPJLambdaQueryWrapper<SubAccountStationEntity>()
        .select(SubAccountStationEntity::getStationId, ...)
        .eq(...))
```

**出现位置**：`admin/BikeDeviceInfoAppService#delete`（remove）、`bridge/PlatformService`（getOne 2 处、count 1 处）、`bridge/CommonThirdPlatformOpenApiServiceImpl`（selectList 3 处）、`bridge/AbstractOperationStatsInfoPushService`（selectList 3 处）、`modules/ChargingSummaryDayServiceImpl`（selectList 2 处）、`modules/ShareRecordServiceImpl`（Mapper selectJoinList 2 处）、`merchant/SubAccountAppService#configStationEcho`（list + MPJLambdaQueryWrapper）。

**测试要点**：MPJ wrapper 兼容喂给原生 MP 方法；`getOne`+`last("limit 1")` 取首行；`MPJLambdaQueryWrapper` 字符串字段版仅此一处。

---

### P18 聚合：`selectMax` / `selectCount` + `groupBy`

```java
// selectMax 单聚合
MPJLambdaWrapper<OrderMonitorRecordEntity> wrapper = new MPJLambdaWrapper<OrderMonitorRecordEntity>()
        .selectMax(OrderMonitorRecordEntity::getPower)
        .eq(OrderMonitorRecordEntity::getOrderId, order.getId());
List<BigDecimal> powerList = orderMonitorRecordService.selectJoinList(BigDecimal.class, wrapper);

// selectCount + 多 groupBy
new MPJLambdaWrapper<InvoiceApplyEntity>()
        .selectCount(InvoiceApplyEntity::getId, InvoiceReminderBO::getApplyCount)
        .innerJoin(InvoiceBusinessOpenInfoEntity.class,
                InvoiceBusinessOpenInfoEntity::getPayId, InvoiceApplyEntity::getPayId)
        .in(InvoiceApplyEntity::getDicApplyStatus, REMIND_APPLY_STATUSES)
        .isNotNull(InvoiceApplyEntity::getPayId)
        .ne(InvoiceApplyEntity::getPayId, StrUtil.EMPTY)
        .groupBy(InvoiceApplyEntity::getPayId)
        .groupBy(InvoiceBusinessOpenInfoEntity::getContactName)
        .orderByAsc(InvoiceApplyEntity::getPayId)
```

**出现位置**：`merchant/OrderManagementService#buildChargeCurveSummary`（selectMax + 映射 BigDecimal）、`integration/InvoiceReminderService`（selectCount + 3×groupBy + in/isNotNull/ne）。

**测试要点**：`selectMax/selectCount` 聚合列生成；`groupBy` 多列；`in(List)` 大集合；`ne(列, StrUtil.EMPTY)` 空串过滤；映射到基本类型 `BigDecimal.class`。

---

### P19 全限定名构造（无 import）

```java
chargingSummaryDayMapper.selectList(
        new com.github.yulichang.wrapper.MPJLambdaWrapper<StationChargingSummaryDayEntity>()
                .eq(StationChargingSummaryDayEntity::getStationId, stationId)
                .eq(StationChargingSummaryDayEntity::getStartDate, startDate));
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
List<RoleEntity> roles = roleService.selectJoinList(RoleEntity.class, wrapper);
List<CouponEntity> funds = selectJoinList(CouponEntity.class, wrapper);
// 结果 stream 后处理
List<DeviceInfoBO> list = deviceInfoService.selectJoinList(DeviceInfoBO.class, wrapper)
        .stream().peek(dto -> {...}).toList();
```

**出现位置**：`merchant/StationInfoService`（Long.class 3 处）、`merchant/OrderManagementService`（BigDecimal.class）、`merchant-admin/AuthAppService`（RoleEntity.class）、`modules/CouponServiceImpl`（CouponEntity.class 2 处）、`bridge/PlatformService#findDeviceInfoByStationIds`（stream().peek）、`modules/DeviceInfoServiceImpl`（stream().map）。

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

- 实体类 `@TableName` / `@TableField` / `@TableId` 注解清单（F-13 需要）——可另行从 `demo-*-domain` 实体类收集。
- 原生 `LambdaQueryWrapper` / `LambdaUpdateWrapper` / `LambdaDeleteWrapper`（F-01/09/10）用法——非 MPJ，不在本清单范围。
- 多数据库方言实测 SQL 输出对比（F-14）——需连接真实数据库验证。

---

## 7. 附录：61 个源文件清单

> 路径已省略公共前缀 `src/main/java/com/example/platform/`。

**app-admin**（20）
`admin/app/service/BikeDeviceInfoAppService.java`、`BikeProductInfoAppService.java`、`BikeOrderRecordAppService.java`、`BikeStationInfoAppService.java`、`DeviceInfoAppService.java`、`InvoiceAppService.java`、`OccupyRecordAppService.java`、`OrderRecordAppService.java`、`StationInfoAppService.java`、`StationPriceTaskRecordAppService.java`、`CouponAppService.java`、`HelpCenterInfoAppService.java`、`InvoiceReminderAppService.java`、`IcCardInfoAppService.java`、`RoleAppService.java`、`ShareRecordAppService.java`、`WalletSettlementAppService.java`、`WalletWithdrawAppService.java`、`UserAppService.java`、`UserDiscountAppService.java`

**app-merchant**（8）
`merchant/app/controller/UserController.java`、`merchant/app/service/SubAccountAppService.java`、`PortManagementService.java`、`DeviceManagementService.java`、`HomeService.java`、`IcCardInfoAppService.java`、`OrderManagementService.java`、`StationInfoService.java`

**app-merchant-admin**（11）
`merchantadmin/app/service/AuthAppService.java`、`BikeDeviceInfoAppService.java`、`BikeOrderRecordAppService.java`、`BikeStationInfoAppService.java`、`DeviceInfoAppService.java`、`OccupyRecordAppService.java`、`OrderRecordAppService.java`、`StationInfoAppService.java`、`ShareRecordAppService.java`、`WalletSettlementAppService.java`、`RoleAppService.java`

**app-consumer**（4）
`consumer/app/service/BikeOrderAppService.java`、`DeviceAppService.java`、`OccupyAppService.java`、`StationAppService.java`

**app-bridge**（3）
`bridge/app/service/openapi/impl/CommonThirdPlatformOpenApiServiceImpl.java`、`bridge/app/service/platform/PlatformService.java`、`bridge/app/service/push/business/template/AbstractOperationStatsInfoPushService.java`

**integration**（1）
`integration/service/InvoiceReminderService.java`

**modules**（14）
`modules/service/impl/biz/BikeDevicePortInfoServiceImpl.java`、`BikeStationSchemeMerchantInfoServiceImpl.java`、`StationOccupyInfoServiceImpl.java`、`StationPushConfigRelServiceImpl.java`、`ChargingSummaryDayServiceImpl.java`、`DeviceInfoServiceImpl.java`、`DevicePortInfoServiceImpl.java`、`StationPriceTaskRecordServiceImpl.java`、`StationSchemeMerchantInfoServiceImpl.java`、`IcCardInfoServiceImpl.java`；`modules/service/impl/sys/CouponServiceImpl.java`、`TeamInfoServiceImpl.java`、`ShareRecordServiceImpl.java`、`UserInfoServiceImpl.java`

---

## 8. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-12 | 初版：61 文件、约 150 处用法点、20 个去重模式、API 覆盖矩阵、测试建议 |
| 2026-08-12 | 脱敏：实体类名/模块名/方法名/常量名替换为通用示例名（见首部脱敏说明），模式编号与结构不变 |
