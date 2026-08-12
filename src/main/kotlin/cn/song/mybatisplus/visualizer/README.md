# 包结构总览

| 包 | 职责 | 对应功能 |
|----|------|----------|
| `actions` | AnAction：右键菜单、快捷键、悬停触发入口 | F-04/05/06 |
| `psi` | PSI 解析：Wrapper 调用链定位、Lambda 解析、实体/注解读取 | F-13 |
| `model` | 内部模型：WrapperChain、Condition、SqlStatement 等 | F-01 基础 |
| `converter` | Converter SPI 与各 Wrapper 转换实现 | F-01/09/10/11/12 |
| `sql` | SQL 生成器、方言适配、格式化器、参数替换 | F-02/03/14 |
| `ui` | 工具窗口、弹窗、高亮面板、复制按钮 | F-07/08 |
| `config` | Settings 页面与持久化 | F-16 |
| `util` | 命名策略、缓存等工具 | F-13/F-20 |

依赖方向（单向，禁止反向）：`actions`/`ui` → `converter`/`config` → `model`/`psi`/`sql`
