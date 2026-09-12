---
name: t-hibdualcapture-repro-method
description: Hibernate 日志抓包复现方法论——demo 副本临时端口起服务抓字节级控制台喂真实解析器；三张截图对应 demo 接口清单（复现验证用）
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_393e0998-29f6-49fb-bb06-6774dfcb7fbc
---

复现日志捕获类缺陷的可复用路线（T-HibDualCapture 2026-09-10 实测走通）：

**Why:** 用户截图只能给症状，`Hibernate: `/logger 双副本、绑定行时序这类结论必须拿字节级控制台原文逐行喂真实解析器才算钉死；demo 工作树常有并行会话半成品（编译不过），不能直接跑。

**How to apply:**
- demo 工作树脏 ⇒ `git archive HEAD` 或 tar（--exclude .git/target/.idea）导出副本到 %TEMP%，只在副本里打最小补丁（如接口签名对齐），**绝不 stash/pop 用户工作树**。
- 起服务：`mvn.cmd -q spring-boot:run -Dspring-boot.run.arguments=--server.port=808N > log 2>&1`（本机 mvn 在 `D:/HuanJing/Maven/maven-mvnd-1.0.3/mvn/bin/mvn.cmd`；Spring Boot 2.6.3 用系统 JDK17 可跑）；curl 打端点后停进程。
- 喂解析器：单测里逐行 feed + 逐行 takeEntries（对齐 `SqlLogConsoleBridge.processConsoleLine` 顺序），末尾 closePending+takeEntries（对齐 LogToSqlPanel）。逐行取件能暴露「提前闭合/条目可见性」类 bug，一次性喂完测不出。
- **Git Bash 的 grep 对重定向中的大日志文件会静默漏结果**（buffering），统计/查找一律用 python `io.open().read()` + `str.count`。

**三张截图对应的 demo 接口（jpa-hibernate-demo，端口 8083，复现验证用）**：
- 截图① `headData`（4 个 count + 3 参数 WH01/日期/日期）：`cn.song.jpademo.controller.HibernateTemplateController#headData` → `cn.song.jpademo.service.impl.HibernateTemplateDemoServiceImpl#getHeadData`；curl `GET /jpa/hib/headData?warehouseId=WH01&startTime=2026-01-01T00:00&endTime=2026-12-31T00:00`。底层 `cn.song.jpademo.dao.impl.CommonDaoHibernateImpl#findByQueryUniqueResult(hql, new String[], new Object[])` 双数组传参。
- 截图② `legacy_item` 字面量查询（无参）：`cn.song.jpademo.controller.JpaFullCoverageController#hqlLegacy` → `cn.song.jpademo.service.impl.JpaFullCoverageServiceImpl#queryHqlOnLegacyEntity`；curl `GET /jpa/full/hqlLegacy`。
- 截图③ `userentity0_` 两个 `?`：`cn.song.jpademo.controller.JpaQueryController#derived` → `cn.song.jpademo.service.impl.JpaQueryServiceImpl#derivedQuery`；curl `GET /jpa/user/derived?lastName=Liu&minAge=20&status=1`。

**复现前提**（demo `application.yml` 须同时开，缺绑定行就抓不到参数）：`spring.jpa.show-sql: true` + `logging.level.org.hibernate.SQL: debug` + `logging.level.org.hibernate.type.descriptor.sql: trace`。

相关：[[t-hibdualcapture-plan]]