#!/usr/bin/env python3
"""
T-DynUpdate 热替换本地验证服务器——serve 真实新版插件 zip + 动态生成 update.json

与 M28 的 local-manifest-server.py 区别：那个 serve 仓库清单 + 假 zip（校验触达即可，
当时安装走「重启生效」脚本路径）；本脚本服务**真实可安装的新版包**——T-DynUpdate 的
动态热替换会真的解包装载它（zip 前置校验会读 plugin.xml 的 <id>/<version>）。

用法：
  1. 构建新版包（版本号必须高于沙盒当前版本；gradle.properties 版本 = 沙盒装机的版本）：
       ./gradlew buildPlugin -Pversion=2026.9.11-beta.1
  2. 起服务（默认端口 8919，与 .run 配置一致）：
       python dev-support/hotswap-local-server.py
     （脚本从 build/distributions/ 自动选最新的 mybatis-plus-sql-visualizer-*.zip，
      在同目录生成 update.json 并整个目录作为 HTTP 根）
  3. 跑「Run IDE (Local Manifest)」启动沙盒 IDE。
  4. 沙盒里：设置 → SQL Visualizer → 更新分区 → 「检查更新」→「下载并安装」。

判定（免重启成功）：
  - 通知文案「已更新并立即生效」（而非「重启 IDE 生效」）
  - 沙盒 idea.log 搜 `dynamic install succeeded — plugin hot-reloaded`
  - 设置页「当前版本」即时变为新版
  - 更新后立刻开预览/日志窗口功能正常（旧实例回归）

失败定位：idea.log 搜 `dynamic install not applicable — falling back to restart script:`
  原因字段写明卡在哪一环（开发守卫 / zip 校验 / 反射缺失 / vetoer / 卸载失败）。

重置测试环境：停止本脚本 → 删除 .intellijPlatform/hotswap-sandbox → 重跑 Run IDE。
"""
import json
import os
import re
import sys
from http.server import HTTPServer, SimpleHTTPRequestHandler

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8919

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SCRIPT_DIR)
DIST_DIR = os.path.join(PROJECT_DIR, "build", "distributions")
SERVE_DIR = DIST_DIR  # 直接 serve 构建产物目录（update.json 写在这里）
MANIFEST_FILE = os.path.join(SERVE_DIR, "update.json")

ZIP_PATTERN = re.compile(r"^mybatis-plus-sql-visualizer-(?P<version>.+)\.zip$")


def newest_zip():
    """build/distributions 下最新的插件 zip（按修改时间）。"""
    candidates = []
    for name in os.listdir(DIST_DIR):
        m = ZIP_PATTERN.match(name)
        if m:
            path = os.path.join(DIST_DIR, name)
            candidates.append((os.path.getmtime(path), name, m.group("version")))
    if not candidates:
        sys.exit(
            f"未找到插件包：{DIST_DIR}\n"
            "先构建新版（版本号须高于沙盒当前版本，见 gradle.properties）：\n"
            "  ./gradlew buildPlugin -Pversion=2026.9.11-beta.1"
        )
    return max(candidates)


def gradle_properties_version():
    props = os.path.join(PROJECT_DIR, "gradle.properties")
    try:
        with open(props, encoding="utf-8") as f:
            for line in f:
                if line.strip().startswith("version="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return None


def main():
    mtime, zip_name, version = newest_zip()
    sandbox_version = gradle_properties_version()
    print(f"serve 插件包：{zip_name}（version={version}）")
    if sandbox_version and version <= sandbox_version:
        print(
            f"警告：serve 版本 {version} 不高于沙盒装机版本 {sandbox_version}——\n"
            f"清单比较会判定「已是最新」，不会触发更新。请用更高的版本号重新构建：\n"
            f"  ./gradlew buildPlugin -Pversion=<更高版本>"
        )

    manifest = {
        "version": version,
        "downloadUrl": f"http://localhost:{PORT}/{zip_name}",
        "changeNotes": "T-DynUpdate 热替换本地验证包",
    }
    with open(MANIFEST_FILE, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print(f"生成清单：{MANIFEST_FILE}")
    print(f"清单地址：http://localhost:{PORT}/update.json")

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=SERVE_DIR, **kwargs)

        def log_message(self, fmt, *args):
            print(f"[{self.address_string()}] {fmt % args}")

    print(f"HTTP 服务已启动：http://localhost:{PORT}/  （Ctrl+C 停止）")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
