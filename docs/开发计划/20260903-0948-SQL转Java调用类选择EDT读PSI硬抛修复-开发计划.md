# SQL转Java调用类选择 EDT 读 PSI 硬抛修复 开发计划

> 问题：SQL→Java 面板点击调用类选择字段（空值态「… 浏览」/按钮），弹 Goto Class 选择器的瞬间 EDT 抛
> `RuntimeExceptionWithAttachments: Read access is allowed from inside read-action only`。
> 根因：新增的「调用类智能识别」（当前编辑器类上下文）在 `pickClass()` 里无 ReadAction 直读 PSI
> （`detectCurrentEditorClass` → `psiFile.classes`），262 平台软断言升级为硬抛。
> 范围：仅 `SqlToJavaPanel.kt` 的 `ClassPickerField` 选择链路（pickClass / elementChosen / 校验 / tooltip），
> 不改选择器行为、不改生成逻辑。

## 问题分析

### 用户实测堆栈（原样保留关键帧）

```
RuntimeExceptionWithAttachments: Read access is allowed from inside read-action only
Current thread: Thread[#77,AWT-EventQueue-0,6,main] (EventQueue.isDispatchThread()=true)
  at ApplicationImpl.assertReadAccessAllowed(ApplicationImpl.java:1223)
  at PsiFileImpl.assertReadAccessAllowed(PsiFileImpl.java:245)
  at PsiFileImpl.getStubTreeOrFileElement(PsiFileImpl.java:794)
  at PsiJavaFileBaseImpl.getClasses(PsiJavaFileBaseImpl.java:134)
  at SqlToJavaPanel$ClassPickerField.detectCurrentEditorClass(SqlToJavaPanel.kt:492)
  at SqlToJavaPanel$ClassPickerField.pickClass(SqlToJavaPanel.kt:465)
  at SqlToJavaPanel$ClassPickerField$attachClick$1.mouseClicked(SqlToJavaPanel.kt:700)
```

### 调用链

```
mouseClicked (SqlToJavaPanel.kt:700)        ← AWT-EventQueue-0（EDT）
└─ pickClass() (:465)
   ├─ detectCurrentEditorClass() (:487)     ← 无 ReadAction
   │  └─ psiFile.classes (:492)             ← getClasses → stub 树遍历 → 硬抛
   └─ ChooseByNamePopup.createPopup + invoke（正常路径）
```

### 关键事实

| 事实 | 源码位置 | 影响 |
|------|----------|------|
| `detectCurrentEditorClass` 直读 `psiFile.classes`（PsiClassOwner） | `SqlToJavaPanel.kt:487-495` | 无 ReadAction，EDT 点击即硬抛 |
| 方法注释自称「须在 ReadAction 内调用（PSI 访问）」但无调用方满足 | `:485` | 约束与实现脱节（T-Mxx 占位符未收口） |
| `validateCallerClass` 在既有 ReadAction **之前**读 `cls.name/isInterface/hasModifierProperty` | `:556` → `:576-589` | 选普通类时下一处硬抛 |
| `buildSpecialTooltip` 在 elementChosen 里直读 `currentCls.name/superTypes/泛型参数` | `:473` → `:501-544` | 选 this/baseMapper 时硬抛 |
| 已收口的先例：`pickPsiClassForTest` 的 Triple 读取、`CallerClassByNameModel.getFullName/getElementName` 均包 ReadAction | `:561`、`:650`、`:656` | T-M44 同根因已修，本次是新引入的漏网 |
| 262 平台 `ReadAction.compute(ThrowableComputable)` 标 @Deprecated | `:553` 注释 | 243 编译目标仅此重载可用，沿用 @Suppress(DEPRECATION) |

### 为什么现有代码无法自我修正

- `pickClass()` 是 EDT 上的 Swing 回调（mouseClicked/actionListener），无任何 PSI 访问豁免——
  平台不做隐式包装，`assertReadAccessAllowed` 直接软断言（262 起硬抛）。
- `detectCurrentEditorClass` 的语义（读取当前编辑器活动文件的主类）要求**同步**得到结果，
  不能改成异步——正确做法是 EDT 上包 `ReadAction.compute`（同步执行、语义不变），
  与 `pickPsiClassForTest`（:561）同一模式。

## 目标

点击调用类选择字段不再抛 `RuntimeExceptionWithAttachments`；选中普通类 / this / baseMapper / 非法类
各分支行为与修复前完全一致（不改变选择器候选、tooltip 文案、拒绝提示）。

## 任务

### T-CallerPicker-01 pickClass 入口包 ReadAction（最小修复）

- `pickClass()` 中 `detectCurrentEditorClass()` 调用包 `ReadAction.compute<PsiClass?, Throwable>`，
  EDT 同步执行，语义不变：

  ```kotlin
  private fun pickClass() {
      // T-CallerPicker：PSI 读取须在 ReadAction 内（EDT 同步，语义不变）——262 硬抛实证
      callerModelConcrete.currentEditorClass = ReadAction.compute<PsiClass?, Throwable> {
          detectCurrentEditorClass()
      }
      val popup = ChooseByNamePopup.createPopup(projectRef, callerModel, null)
      // ... 不变
  }
  ```

- `detectCurrentEditorClass` 注释更新：由「须在 ReadAction 内调用」改为说明调用方
  `pickClass` 已保证 ReadAction（消除约束与实现脱节）。
- 编译目标 243，沿用既有 `@Suppress("DEPRECATION")` 模式（`:553` 同款）。

### T-CallerPicker-02 elementChosen 回调 PSI 读取统一收口

- **PsiClass 分支**（`pickPsiClassForTest` :554）：`validateCallerClass`（:556）在既有
  ReadAction.compute（:561）之前直读 `cls.name/isInterface/hasModifierProperty`——
  把「校验 + 读取」合并进**单次** ReadAction，拒绝原因键与类名一并读出，EDT 内无 PSI 访问：

  ```kotlin
  internal fun pickPsiClassForTest(cls: PsiClass) {
      // 校验与读取合并进单次 ReadAction（262 硬抛：isInterface/hasModifierProperty 同属 PSI 访问）
      val result = ReadAction.compute<Pair<String?, Triple<String, String?, Icon?>?>, Throwable> {
          val rejectReason = validateCallerClass(cls)
          if (rejectReason != null) return@compute Pair(rejectReason, null)
          Pair(null, Triple(cls.name ?: return@compute Pair(rejectReason, null), cls.qualifiedName, cls.getIcon(0)))
      } ?: return
      // ... 依据 result 分发：拒绝提示 or setPicked（纯净 EDT 代码）
  }
  ```

- **String 分支**（:473）：`buildSpecialTooltip` 的 `currentCls.name/superTypes/泛型参数` 读取包
  `ReadAction.compute<String?, Throwable>`，仅计算结果留 EDT 渲染。
- **审查兜底**：`ClassPickerField` 内其余回调（`specialRenderer.getListCellRendererComponent` :631
  读静态 map、`refresh`/`setPicked`）确认无 PSI 访问；`callerModelConcrete.currentEditorClass`
  仅是字段读写。

### T-CallerPicker-03 单元测试

- Light 测试环境（inTestMode）不触发 262 断言，单测无法复现硬抛——单测目标是**行为不变**：
  - `validateCallerClass` 迁移进 ReadAction 后拒绝矩阵不变（VO/DTO/… 拒绝、Mapper/Service 放行、接口/抽象类拒绝）——复用既有拒绝矩阵用例。
  - `buildSpecialTooltip` 包 ReadAction 后 tooltip 文案不变（含当前编辑器类上下文分支
    `ServiceImpl<X, Entity>` / `BaseMapper<Entity>` / 无泛型回落）。
  - `pickPsiClassForTest` 重构后通过 `pickPsiClassForTest` 测试缝选取正常类仍走 `setPicked`
    （类图标 + 简单类名 + 全限定名 + 变更回调）。
- 既有 `SqlToJavaPanel*` 测试全绿（调用类选择器选型、拒绝提示、this/baseMapper tooltip、
  `CallerClassByNameModel` ReadAction 委托行为等）。

### T-CallerPicker-04 runIde 真机验证

- runIde 打开 SQL→Java 面板，点击调用类选择字段（空值态「… 浏览」，三个可点子组件
  iconLabel/textLabel/按钮宿主各点一次）→ Goto Class 弹窗正常弹出，**无**
  `RuntimeExceptionWithAttachments`（堆栈含 `SqlToJavaPanel.kt pickClass`）。
- 弹窗内分别选中：普通 Service 类（tooltip 应含当前编辑器类上下文）、`this`、
  `baseMapper`、VO/DTO（弹拒绝提示）——与修复前行为一致。
- 多编辑器场景：活动编辑器为 Java/Kotlin 文件与非 Java 文件各验证一次（
  `detectCurrentEditorClass` 返回 null 分支不崩）。

## 实施顺序

```
T-CallerPicker-01 (pickClass 入口包裹) ──→ T-CallerPicker-02 (elementChosen 收口)
      └── 独立可并行 ──────────────────────┘
                    ↓
        T-CallerPicker-03 (单测) ──→ T-CallerPicker-04 (runIde 真机)
```

- 01、02 独立（01 已消除主堆栈，02 消除同链剩余隐患），可并行实施。
- 03 依赖 01/02 完成；04 为最终验收门（262 断言仅真机可证）。

## 并行隔离（铁律）

- 本计划撰写未跑 gradlew，无需登记；实施前先
  `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`，有活跃任务则
  `register --name "SQL转Java调用类选择EDT读PSI硬抛修复" --sandbox <独立沙盒>` 登记，跑完 `done`。
- 若并行 worktree 挂起期间本会话直接改 it——`Sandbox` 平分，参照
  `docs/MEMORY.md`「并行测试协调登记表」节。

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-09-03 | 立项：用户实测堆栈定位（pickClass→detectCurrentEditorClass→getClasses 硬抛），T-CallerPicker-01~04 任务拆分；同链 `validateCallerClass`/`buildSpecialTooltip` 隐患识别 |