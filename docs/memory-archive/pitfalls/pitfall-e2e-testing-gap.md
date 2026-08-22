---
name: pitfall-e2e-testing-gap
description: 单元测试无法捕获 IDE 集成 bug——必须编写端到端测试（PreviewEndToEndTest）模拟完整预览链路
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bb5d9591-b617-4ac9-9c22-22ca1ad5d96a
---

**端到端测试缺失陷阱（206-08-21 用户反复报告"为什么老是有这么多问题测不出来"后总结）**

### 问题

整个会话中反复出现"测试没覆盖到的 bug"：
- 多调用方法 gutter 图标显示错误 SQL（单元测试只测 locator 返回第一个调用）
- WHERE 出现 Java 代码（单元测试只测简单 cb.equal 场景，不测 List<Predicate>.toArray()）
- 位置参数名显示 #param1（单元测试只测 extractParamNamesFromHql fallback）
- 参数类型不推断（单元测试只测 isNumericQuery 单独逻辑）
- 重复 gutter 图标（单元测试不测多 provider 互锁）
- JOIN FETCH 别名重复（单元测试用简单 HQL 不用多行拼接）

### 根因

单元测试测试的是**单个函数**（如 `HqlConverter.convert()`），但实际 bug 出现在**完整链路**中：
```
gutter 图标点击 → locate() → convert() → previewHql() → buildJpaPreviewResult() → 面板渲染
```

每个环节都可能出错，但单元测试只验证了各环节独立工作正常。

### 修复：端到端测试模式

创建 `PreviewEndToEndTest`（integration 包），直接调用完整预览链路：

```kotlin
class PreviewEndToEndTest : BasePlatformTestCase() {
    private lateinit var service: SqlPreviewService

    override fun setUp() {
        super.setUp()
        service = SqlPreviewService(myFixture.project)
    }

    fun testChainedCall_parameterNamesFromHql() {
        myFixture.configureByText("UserDao.java", """...""")
        val call = findMethodCall("createQuery")
        val result = service.previewHql(call)  // 完整链路
        assertNotNull(result)
        // 验证参数名正确
    }
}
```

**关键点**：
- 使用 `myFixture.project` 获取正确项目上下文（非 `ProjectManager.getInstance().openProjects.first()`）
- 直接构造 `SqlPreviewService(project)` 而非 `getService`（测试环境可能未注册）
- 测试完整链路：locator → converter → preview → result

### 测试覆盖清单

端到端测试应覆盖的 IDE 集成场景：
1. **多调用方法**：方法体内有 2+ 个 createQuery，点击不同图标显示不同 SQL
2. **链式调用**：无变量赋值，参数名从 setParameter 的 valueExpr 提取
3. **参数类型推断**：String→加引号，Integer→不加
4. **CriteriaQuery where(List<Predicate>.toArray())**：不出现 Java 代码
5. **JOIN FETCH 多行 HQL**：不重复、不缺空格
6. **cb.not(cb.equal()) 嵌套**：不重复提取
7. **cb.in().value(list)**：IN 子句有占位符可输入多值
8. **createNativeQuery**：表名正确解析
9. **NEW DTO 查询**：构造函数参数成为 SELECT 列

**Why:** IntelliJ 插件的 bug 几乎都出现在多组件集成的边界上——locator 找到错误调用、converter 误解表达式、preview 链参数丢失。单元测试验证组件正确性，端到端测试验证集成正确性。

**How to apply:** 每个新功能/修复都应同时编写单元测试（组件级）和端到端测试（集成级）。端到端测试应模拟真实用户操作路径（点击 gutter 图标 → 预览面板）。测试 fixture 必须使用真实的 JPA 注解（@Entity/@Table）而非普通 class。