# ZCode 仓库 Wiki 同步 Gitee Wiki 经验沉淀

> 分析基线：2026-09-14 用 ZCode repo-wiki 为本插件生成的 60 页架构 Wiki，导出后推送 Gitee wiki 仓库上线（master `0b9d0dc` 同步 60 页 + `0490a98` 目录页改名落地首屏）。
> 适用范围：任何 Gitee 仓库想把 ZCode repo-wiki 的本地产物发布为在线 Wiki 的场景。导出脚本已入库 `dev-support/export_zcode_wiki_to_gitee.py`。

## 一、背景与结论

ZCode 的 repo-wiki（文件树仓库名右侧图标入口）是纯本地能力：读完代码库生成「架构导读」式文档站，只存在本机用户目录，官方文档明确不写任何远程仓库。对话轮次结束时的自动刷新（`#refresh`）也只更新本地那份 JSON，代码没变就跳过。

Gitee 的 Wiki 本身是一个独立 git 仓库（`<owner>/<repo>.wiki.git`），所以「同步」没有现成开关，路径是：把 wiki.json 里的页面导出成 Markdown 文件，推进 wiki 仓库。本次实装后线上效果：访问 [仓库 /wikis](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/wikis) 默认落目录页，左侧页面树 00~60 顺序排列，`_Sidebar.md` 渲染为侧边导航，页面内 Mermaid 图正常渲染。

## 二、ZCode repo-wiki 本地数据结构

存储位置 `~/.zcode/v2/repo-wiki/<工作区标识哈希>/`，哈希与工作区的对应关系看目录内 `task.json`（`workspaceKey` 字段即工作区绝对路径，`status/totalPages/updatedAt` 是生成状态）。

生成完成后目录内有三个权威/中间产物：

| 文件 | 性质 | 说明 |
| --- | --- | --- |
| `wiki.json` | 权威全量 | 全部页面的 Markdown 正文与源码引用，单文件几百 KB |
| `draft.json` | 中间态 | 生成中断时的草稿， cancelled 任务只有它 |
| `draft-pages/*.json` | 中间态 | 逐页中间产物，页数与 `totalPages` 一致 |

`wiki.json` 顶层字段：`workspacePath`（工作区路径）、`language`、`updatedAt`（毫秒时间戳）、`generationOptions`（模型与选项）、以及两个核心结构：

- `catalogTree`：嵌套目录树。节点带 `title/order/children`，**挂了页面的节点才有 `pageId`**，纯章节标题没有；
- `pages`：页面数组。每页 `id/title/order/markdown/filePaths/sources`，`markdown` 就是正文，`filePaths` 是该页涉及的源码清单。

`pages` 里可能存在没被 `catalogTree` 引用的孤儿页，导出时要兜底补到目录末尾。

## 三、导出脚本 `dev-support/export_zcode_wiki_to_gitee.py`

Python 标准库实现，无三方依赖。输出 Gitee wiki 仓库布局：

```
00-目录.md      首页：标题 + 生成时间 + 按目录树缩进的完整目录（链接带 <...> 尖括号防中文文件名断链，不带 .md 后缀）
_Sidebar.md     侧边导航（Gitee 自动渲染为 wiki 左侧栏）
NN-页面标题.md  按目录树顺序编号的正文页，Mermaid 代码块原样保留
```

常用命令：

```bash
# 列出本机全部 wiki 及对应工作区
python dev-support/export_zcode_wiki_to_gitee.py --list

# 导出（wiki 目录 = ~/.zcode/v2/repo-wiki/<哈希>/）
python dev-support/export_zcode_wiki_to_gitee.py ~/.zcode/v2/repo-wiki/<哈希> --out <wiki仓库克隆目录>

# 可选：--with-sources 每页末尾追加涉及源码清单；--no-home/--no-sidebar 跳过首页与侧边栏
```

文件名规则：`NN-标题`（标题中 `\/:*?"<>|` 等非法字符替换为 `-`），重名自动加序号。中文文件名直接可用，git status 里的八进制转义只是显示问题。

## 四、Gitee wiki 机制实测

1. **wiki 仓库要先初始化**：网页端仓库 Wiki 标签页点一次初始化，`<owner>/<repo>.wiki.git` 才存在。初始内容只有一个占位 `Home.md`，可直接覆盖。
2. **页面树按文件名排序**：左侧「页面」列表不是按生成顺序，是纯文件名序。`Home.md` 混在 `01-`~`60-` 编号文件里会排到倒数第二。
3. **落地页规则**（匿名访问 `/wikis` 实测）：仓库 wiki 有 `Home.md` 时跳转 Home；没有时**落排序第一的页面**。所以目录页命名为 `00-目录.md`，既排到树顶又成为默认首屏，一举两得——`Home.md` 这个名字反而是坑。
4. **`_Sidebar.md` 自动生效**：放在仓库根即可，无需配置。
5. **验证手段**：Gitee 页面是 JS 渲染，WebFetch/curl 抓不到 wiki 正文；Gitee API v5 的 `/repos/{owner}/{repo}/wikis` 匿名访问返回 40001（登录失效），需要令牌。最省事的验证方式是真实浏览器打开落地页看 URL 跳转与页面树顺序。

## 五、踩坑清单

- **提交信息必须 ASCII**：本机 Bash 命令行写中文必乱码，`git commit -m` 用英文；文件内容不受影响（Python 侧全程 `encoding="utf-8"` 读写）。
- **LF/CRLF warning 无害**：Windows `autocrlf` 下推送整批文件会刷一屏 `LF will be replaced by CRLF`，内容不受影响。
- **本地目录链接要用尖括号且不带 .md 后缀**：`[标题](<01-中文文件名>)`，尖括号防 Gitee 渲染时中文文件名截断链接；**后缀必须去掉**——Gitee wiki 不改写相对 `.md` 链接（与 GitHub 不同），点击 `[标题](<01-x.md>)` 会按字面解析成 `/wikis/01-x.md`，找不到该页被兜底弹回落地页（实测 2026-09-14，浏览器访问 `/wikis/01-x.md` 直接 302 回 `00-目录`），表现为「目录页点标题又跳回目录页」。不带后缀由浏览器相对解析成 `/wikis/01-x`，与左侧页面树生成的 URL 一致。
- **刷新后要重新导出**：wiki.json 是全量文件，refresh 之后整目录覆盖导出再 push，git 自动算增量；元数据里的 `updatedAt` 与生成时的 commit 对应，长期不同步会让线上 Wiki 落后于代码。
- **导出脚本以仓库内 `dev-support/` 版本为权威**：`C:\Users\USER\ZCodeProject\export_zcode_wiki_to_gitee.py` 是开发时的原始副本，功能一致但后续只维护仓库内版本。

## 六、后续同步流程

ZCode 自动 refresh 本地 wiki 后，同步只需导出加推送两步：

```bash
python F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer/dev-support/export_zcode_wiki_to_gitee.py \
  ~/.zcode/v2/repo-wiki/a5fb69f4d7d7 \
  --out "F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer.wiki"

git -C "F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer.wiki" add -A \
  && git -C "F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer.wiki" commit -m "docs: sync zcode repo-wiki" \
  && git -C "F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer.wiki" push
```

wiki 仓库克隆固定在主仓库旁：`F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer.wiki`。本机其他 wiki 同理（`--list` 查哈希），cyzc-platform（哈希 `03d76b6c2205`，38 页）已确认暂不同步。
