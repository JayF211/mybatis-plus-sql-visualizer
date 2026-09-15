#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 ZCode repo-wiki 的 wiki.json 导出为 Gitee/GitHub wiki 仓库的 Markdown 布局。

用法:
  python export_zcode_wiki_to_gitee.py --list
  python export_zcode_wiki_to_gitee.py <wiki目录> [--out 输出目录] [--with-sources]

wiki 目录指 %USERPROFILE%\\.zcode\\v2\\repo-wiki\\<哈希>\\（内含 wiki.json）。
输出: 00-目录.md、_Sidebar.md、NN-页面标题.md，整体拷入 .wiki.git 克隆后提交推送即可。

Gitee wiki 落地页规则: 有 Home.md 跳 Home, 否则落页面树第一项(按文件名排序)。
目录页用 00- 前缀保证排在树顶并成为默认落地页, 不叫 Home.md 以免沉底。

链接规则: 相对链接不带 .md 后缀([标题](<NN-标题>))。Gitee wiki 不改写相对
.md 链接, 点击会解析成 /wikis/NN-标题.md 找不到页面, 被兜底弹回落地页;
不带后缀则由浏览器相对解析成 /wikis/NN-标题, 与页面树生成的 URL 一致。
"""
import argparse
import json
import re
import sys
import time
from pathlib import Path

WIKI_BASE = Path.home() / ".zcode" / "v2" / "repo-wiki"


def list_wikis():
    rows = []
    for d in sorted(WIKI_BASE.iterdir()):
        tj = d / "task.json"
        if not tj.exists():
            continue
        try:
            meta = json.loads(tj.read_text(encoding="utf-8"))
        except Exception as e:
            print(f"{d.name}: task.json 解析失败 {e}")
            continue
        has_wiki = (d / "wiki.json").exists()
        rows.append((d, meta, has_wiki))
    return rows


def fmt_ts(ms):
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(ms / 1000)) if ms else "unknown"


def load_wiki(wiki_dir: Path):
    data = json.loads((wiki_dir / "wiki.json").read_text(encoding="utf-8"))
    pages_by_id = {p["id"]: p for p in data.get("pages", [])}
    return data, pages_by_id


def slugify(title: str, order: int, used: set) -> str:
    s = re.sub(r'[\\/:*?"<>|\r\n\t]', "-", title)
    s = re.sub(r"\s+", "-", s).strip("-.")
    name = f"{order:02d}-{s}"[:80].strip("-.")
    base, i = name, 2
    while name.lower() in used:
        name = f"{base}-{i}"
        i += 1
    used.add(name.lower())
    return name


def flatten(nodes, pages_by_id, used, depth=0, rows=None):
    """按目录树顺序铺平; pageId 缺失的节点是纯章节标题, pg 为 None。"""
    if rows is None:
        rows = []
    for n in nodes:
        pg = pages_by_id.get(n.get("pageId"))
        if pg is not None and "filename" not in pg:
            pg["filename"] = slugify(pg["title"], pg.get("order", 0), used)
        rows.append((depth, n["title"], pg))
        flatten(n.get("children") or [], pages_by_id, used, depth + 1, rows)
    return rows


def toc_lines(rows):
    lines = []
    for depth, title, pg in rows:
        indent = "  " * depth
        if pg:
            lines.append(f"{indent}- [{pg['title']}](<{pg['filename']}>)")
        else:
            lines.append(f"{indent}- **{title}**")
    return lines


def build_home(data, rows):
    gen = data.get("generationOptions") or {}
    lines = [
        f"# {Path(data.get('workspacePath', 'repo')).name} 架构 Wiki",
        "",
        f"> 由 ZCode repo-wiki 生成于 {fmt_ts(data.get('updatedAt'))}，"
        f"共 {len(data.get('pages', []))} 页。代码演进后以仓库内最新源码为准。",
        "",
        "## 目录",
        "",
    ]
    lines += toc_lines(rows)
    return "\n".join(lines) + "\n"


def build_sidebar(rows):
    return "\n".join(toc_lines(rows)) + "\n"


def page_markdown(pg, with_sources):
    parts = [pg.get("markdown") or ""]
    if with_sources and pg.get("filePaths"):
        parts.append("\n## 涉及源码\n")
        parts += [f"- `{f}`" for f in pg["filePaths"]]
    return "\n".join(parts).rstrip() + "\n"


def export(wiki_dir: Path, out_dir: Path, with_sources: bool, no_home: bool, no_sidebar: bool):
    data, pages_by_id = load_wiki(wiki_dir)
    used = {"00-目录", "_sidebar"}
    rows = flatten(data.get("catalogTree") or [], pages_by_id, used)

    # 目录树没挂进去的孤儿页, 补在末尾
    linked = {p["id"] for _, _, p in rows if p}
    orphans = [p for p in data.get("pages", []) if p["id"] not in linked]
    for p in orphans:
        p["filename"] = slugify(p["title"], p.get("order", 999), used)
    if orphans:
        rows += [(0, "其他页面", None)] + [(0, p["title"], p) for p in orphans]

    out_dir.mkdir(parents=True, exist_ok=True)
    for _, _, pg in rows:
        if pg:
            (out_dir / f"{pg['filename']}.md").write_text(
                page_markdown(pg, with_sources), encoding="utf-8", newline="\n"
            )
    if not no_home:
        (out_dir / "00-目录.md").write_text(build_home(data, rows), encoding="utf-8", newline="\n")
    if not no_sidebar:
        (out_dir / "_Sidebar.md").write_text(build_sidebar(rows), encoding="utf-8", newline="\n")

    print(f"workspace : {data.get('workspacePath')}")
    print(f"updated   : {fmt_ts(data.get('updatedAt'))}")
    print(f"pages     : {len(data.get('pages', []))} (orphans: {len(orphans)})")
    print(f"exported  : {out_dir}")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("wiki_dir", nargs="?", help="包含 wiki.json 的目录")
    ap.add_argument("--out", default="wiki-export", help="输出目录 (默认 ./wiki-export)")
    ap.add_argument("--list", action="store_true", help="列出本地全部 wiki 及对应工作区")
    ap.add_argument("--with-sources", action="store_true", help="每页末尾追加涉及源码清单")
    ap.add_argument("--no-home", action="store_true", help="不生成 00-目录.md")
    ap.add_argument("--no-sidebar", action="store_true", help="不生成 _Sidebar.md")
    args = ap.parse_args()

    if args.list:
        for d, meta, has_wiki in list_wikis():
            state = "ready" if has_wiki else meta.get("status", "?")
            print(f"{d.name}  [{state}]  {meta.get('totalPages', '?')}页  "
                  f"更新 {fmt_ts(meta.get('updatedAt'))}  {meta.get('workspaceKey')}")
        return

    if not args.wiki_dir:
        ap.error("请给出 wiki 目录, 或用 --list 查看可用的 wiki")
    wiki_dir = Path(args.wiki_dir).expanduser()
    if not (wiki_dir / "wiki.json").exists():
        sys.exit(f"{wiki_dir} 下没有 wiki.json")
    export(wiki_dir, Path(args.out).expanduser(), args.with_sources, args.no_home, args.no_sidebar)


if __name__ == "__main__":
    main()
