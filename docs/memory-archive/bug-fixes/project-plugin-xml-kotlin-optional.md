---
name: project-plugin-xml-kotlin-optional
description: plugin.xml 校验报错修复——Kotlin 语言 ID 需注册到可选依赖描述文件，supportsKotlinPluginMode 必须放在 extensions 内
metadata:
  node_type: memory
  type: project
  originSessionId: sess_8697e69e-2205-4083-9399-9f6eceee7ded
---

plugin.xml 校验报错修复（2026-08-25）：

1. **"无法解析 ID 为 'Kotlin' 的语言"** — `codeInsight.lineMarkerProvider` 的 `language="Kotlin"` 属性引用了 `org.jetbrains.kotlin` 插件注册的语言 ID，但该插件是可选依赖（`optional="true"`），主描述文件校验时语言 ID 不可达。修复：将 Kotlin gutter 注册从 `plugin.xml` 移至 `kotlin-optional.xml`（仅 Kotlin 插件存在时加载）。

2. **"Unknown element: supportsKotlinPluginMode"** — `<supportsKotlinPluginMode>` 必须放在 `<extensions defaultExtensionNs="org.jetbrains.kotlin">` 内部，不能作为 `<idea-plugin>` 的直接子元素。修复：
   ```xml
   <extensions defaultExtensionNs="org.jetbrains.kotlin">
       <supportsKotlinPluginMode supportsKotlinPluginMode="true"/>
   </extensions>
   ```

3. **"action element should have specified class attribute"** — `<action>` 元素必须指定 `class` 属性，即使只用于 tooltip/text 目的。无 `class` 的 `<action>` 会导致 `invalid plugin descriptor` 错误，整个插件无法加载。

**Why:** plugin.xml schema 严格校验——`supportsKotlinPluginMode` 是 kotlin 扩展点内的元素；`<action>` 必须有 `class` 才能被 ActionManager 实例化。

**How to apply:** 新增可选插件相关扩展点注册时，一律放到对应 `*-optional.xml`；`<supportsKotlinPluginMode>` 放在 `<extensions defaultExtensionNs="org.jetbrains.kotlin">` 内；所有 `<action>` 必须带 `class` 属性。