# Gitee 自动发行工作流说明文档

> 沉淀自 v2026.9.14-beta.1 内测版全自动发行实录（2026-09-12，提交 c507833，Gitee 发行版 id 1139592）。
> 覆盖从跑门禁到 Gitee 发行版上线的完整链路，可照单执行；正式通道上架流程见
> [Marketplace发布流程-说明文档.md](./Marketplace发布流程-说明文档.md)，版本号规则见
> [版本号规范-说明文档.md](./版本号规范-说明文档.md)。

## 一、总流程（顺序执行）

```
parallel_tasks check/register
  → ① 全量测试
  → ② verifyPlugin 三目标门禁
  → ③ 发行文档同步（八处，见下）
  → ④ 发行提交（单提交，10 文件量级）
  → ⑤ buildPlugin 产 zip
  → ⑥ git tag + push master + push tag
  → ⑦ Gitee API 建发行版 + 上传 zip 附件（见第三节）
  → ⑧ 回读校验 downloadUrl
  → parallel_tasks done
  → 人工收尾：preview.update.json 拷入 update/update.json（独立单文件提交，不进发行提交）
```

跑任何 `./gradlew` 前按并行铁律先 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`，
有活跃任务先 `register`，全部跑完 `done --name <任务名>` 注销（裸 `done` 注销全部，禁用）。

## 二、准备阶段要点

### 范围核算

- 区间 = 上一个发行标签到 HEAD：`git log --format="%h %ad %s" --date=format:"%m-%d %H:%M" <上版tag>..HEAD`。
- **打标签之后才落盘的提交不在上版发行包里**——本次实证：T-ReverseParenWira（dab3d3b）晚于
  `2026.9.11` 标签，其条目原挂在 CHANGELOG `[2026.9.11]`，须移入本版块并加注说明。
- 纯 docs 提交（记忆精简、计划定稿）不计入发行内容。

### 八处同步清单

| # | 文件 | 内容 |
|---|------|------|
| 1 | `docs/发行说明/{YYYYMMDD}-{HHMM}-{主题}-发行说明.md` | 新建，完整发行说明（新增/修复/测试/兼容性/安装/反馈）；全文同时作为 Gitee 发行版正文 |
| 2 | `CHANGELOG.md` | `[Unreleased]` → `[{版本}] - {日期}`，Keep a Changelog 格式 |
| 3 | `gradle.properties` | `version=`；内测通道追加 `-beta.N` 后缀 |
| 4 | `build.gradle.kts` `patchPluginXml.changeNotes` | 新增 `<h2>v{版本}</h2>` 块置顶（红线：只主标题、无括号、无冒号副题） |
| 5 | `plugin.xml` `<description>` | 仅当出现新能力类别时并入一条；无新类别不动（「最近更新」节已整体移除，见下） |
| 6 | `update/preview.update.json` | 新版本号 + downloadUrl + changeNotes（与 #4 同文案）+ publishDate + sinceBuild |
| 7 | `marketplace-custom-page.html` | 功能矩阵行 + 「为什么选它」按能力类别判定；单源（docs 副本已删） |
| 8 | `docs/MEMORY.md` + `docs/变更记录.md` | 发行说明索引最新在上；总册缺行按日期倒序补齐 |

description / 市场页定位是纯能力陈列，不承担「最近更新」播报（2026-09-12 用户定案）；
功能更新对外可见性由 changeNotes / preview.update.json / CHANGELOG / 发行说明四处承担。

### 内测 beta 系列专项规则

- 对外更新清单（changeNotes / preview.update.json）**只保留最新一个 `-beta.N` 标题块**，
  内容为全系列累计；后一发 beta 替换前一发的块；转正时改纯数字标题并将累计内容并入正式版块。
- `PluginVersionTest` 版本正则须容忍内测后缀：`\d{4}\.\d{1,2}\.\d{1,2}(-beta\.\d+)?`
  （2026-09-12 已放宽入测试，正式版无需回改）。
- 正式版 2026.9.14 发行时须包含 beta.1~N 全部更新日志的合并版。

### 门禁

- 全量测试：total/skipped/failures/errors 四计数记录进发行说明。本次基线 3353/0/0/6。
- verifyPlugin：BUILD SUCCESSFUL + `build/reports/pluginVerifier/IU-{243,253,262}/report.html`
  三份 Compatible 0 issues。warning 级弃用计数与上版对比（基线 243=3 / 253=6 / 262=41）留观察不阻断。
- 测试假失败两类先证伪再排逻辑：`build/test-results` 中途损坏（`NoSuchFileException:
  ...in-progress-results-generic.bin` + 成片 ClassNotFoundException）→ `rm -rf build/test-results`
  重跑即绿，不是代码问题。

### 发行提交与产物

- 提交信息格式：`feat(release): 发行 v{ver}——版本号 + CHANGELOG + 变更记录 + 发行说明 + preview 清单`，
  正文分条列本次内容。
- 发行提交 = 单提交含全部同步文件（本次 10 文件）；**工作树必须干净才 buildPlugin**，
  否则半成品混进 zip。
- 标签名 = `gradle.properties` 版本号逐字（含 `-beta.N`），不带 `v` 前缀；
  `git tag {ver} && git push origin master && git push origin {ver}`。
  标签先推远端，Gitee API 建发行版时才能引用。

## 三、Gitee API 发布（本次实证端点与参数）

鉴权一律 `access_token` 参数（Personal Access Token，需 projects 权限）。
令牌存仓库根 `gitee-token.json`（字段 `gitee_personal_access_token`/`owner`/`repo`；已进 .gitignore，
严禁提交/外传），脚本读取：
`TOKEN = json.load(open('gitee-token.json', encoding='utf-8'))['gitee_personal_access_token']`。
以下 `{owner}/{repo}` = `songrenshuo/mybatis-plus-sql-visualizer`。

### 1. 建发行版

```
POST https://gitee.com/api/v5/repos/{owner}/{repo}/releases
```

| 参数 | 值 | 备注 |
|------|-----|------|
| `tag_name` | 完整版本号（含 `-beta.N`） | 远端标签必须已存在 |
| `target_commitish` | `master` | |
| `name` | `🎉 SQL Visualizer v{ver}`（内测版加「 内测版」后缀） | 沿用历次标题风格 |
| `body` | 发行说明 md 全文 | **参数名是 `body`，不是 GitHub 的 `description`** |
| `prerelease` | `'true'` | 即勾选「这是一个预览版本」 |

**坑位实录**：传 `description` 报 400 `{"messages":["body is missing"]}`——Gitee API 与
GitHub API 在此参数名上不兼容，网上抄的 GitHub 式脚本直接翻车。

成功响应里取 `id`（本次 1139592），上传附件要用。

### 2. 上传 zip 附件

```
POST https://gitee.com/api/v5/repos/{owner}/{repo}/releases/{id}/attach_files
Content-Type: multipart/form-data
```

multipart 两个字段：`file`（zip 二进制，filename 必须等于
`mybatis-plus-sql-visualizer-{ver}.zip`，即附件名直接决定下载 URL 尾段）+ `access_token`。
成功返回 201 与附件信息。

**坑位实录**：`/upload_files`（社区文章流传）与 GitHub 风格 `POST /releases/{id}/assets`
实测均 404，只有 `attach_files` 可用；`PUT /upload_files` 也 404。不要再试。

附件下载地址形如：

```
https://gitee.com/{owner}/{repo}/releases/download/{tag}/mybatis-plus-sql-visualizer-{ver}.zip
```

### 3. 回读校验（必做）

```
GET https://gitee.com/api/v5/repos/{owner}/{repo}/releases/{id}
```

断言三件事：`prerelease == True`；`assets` 里存在插件 zip（另两个 tag 自动归档 zip/tar.gz
是 Gitee 自带的，不算）；**附件 `browser_download_url` 与 `preview.update.json` 的
`downloadUrl` 逐字一致**——这是自更新通道能否命中包体的唯一保证。

### 附：Python 无依赖参考实现

```python
import json, os, urllib.request, urllib.parse

TOKEN = json.load(open('gitee-token.json', encoding='utf-8'))['gitee_personal_access_token']
BASE = 'https://gitee.com/api/v5/repos/songrenshuo/mybatis-plus-sql-visualizer/releases'

# 1) 建发行版（body 参数名！）
data = urllib.parse.urlencode({
    'access_token': TOKEN, 'tag_name': VER, 'target_commitish': 'master',
    'name': f'🎉 SQL Visualizer v{VER} 内测版', 'body': release_notes_md,
    'prerelease': 'true'}).encode()
resp = json.load(urllib.request.urlopen(urllib.request.Request(BASE, data=data)))
rid = resp['id']

# 2) 上传附件（attach_files 端点！）
zip_path = f'build/distributions/mybatis-plus-sql-visualizer-{VER}.zip'
boundary = '----UploadBoundary'
body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
        f'filename="{os.path.basename(zip_path)}"\r\nContent-Type: application/zip\r\n\r\n'.encode()
        + open(zip_path, 'rb').read() + b'\r\n'
        + f'--{boundary}\r\nContent-Disposition: form-data; name="access_token"'
          f'\r\n\r\n{TOKEN}\r\n--{boundary}--\r\n'.encode())
req = urllib.request.Request(f'{BASE}/{rid}/attach_files', data=body, method='POST')
req.add_header('Content-Type', f'multipart/form-data; boundary={boundary}')
urllib.request.urlopen(req, timeout=180)  # 201

# 3) 回读校验
r = json.load(urllib.request.urlopen(f'{BASE}/{rid}?access_token={TOKEN}'))
urls = {a['browser_download_url'] for a in r['assets']}
assert r['prerelease'] and manifest_download_url in urls
```

## 四、发行后收尾

1. `parallel_tasks.py done --name <任务名>` 注销登记。
2. 人工步骤：`preview.update.json` 逐字节拷入 `update/update.json`（先 sha1 校验一致），
   独立单文件提交随官网推送，**不进发行提交**。
3. 核对 Gitee 发行版页面：预览标记、附件可下载、正文渲染正常。
4. 若为 beta 系列：下一发 beta 重复本流程，changeNotes 块替换为本发内容（累计）。

## 相关文档

- [版本号规范-说明文档.md](./版本号规范-说明文档.md) — 基号/内测后缀/标签规则
- [Marketplace发布流程-说明文档.md](./Marketplace发布流程-说明文档.md) — 官方市场上架（beta 转正时走）
- 各期 `-发行说明.md` — 每期范围与门禁计数实录
