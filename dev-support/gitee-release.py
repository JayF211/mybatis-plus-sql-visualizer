#!/usr/bin/env python3
"""
Gitee 自动发行脚本（可复用）

用法:
  python dev-support/gitee-release.py --version 2026.10.6 --notes docs/发行说明/xxx-发行说明.md
  python dev-support/gitee-release.py --version 2026.10.6-beta.1 --notes docs/发行说明/xxx.md --prerelease

功能（对应《Gitee自动发行工作流-说明文档.md》第三节）:
  1. 建发行版（POST /releases，body 参数名坑位已在实现内规避）
  2. 上传 zip 附件（POST /releases/{id}/attach_files，multipart 字段 file + access_token）
  3. 回读校验（GET /releases/{id}，断言附件 browser_download_url 与预期 downloadUrl 逐字一致）

依赖:
  - 仓库根 gitee-token.json（字段: gitee_personal_access_token / owner / repo；已 gitignore）
  - build/distributions/mybatis-plus-sql-visualizer-{version}.zip 已构建（buildPlugin）
  - 远端标签 {version} 已 push（否则建发行版 400）
"""
import argparse
import json
import os
import sys
import urllib.request
import urllib.parse

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)


def load_config():
    token_path = os.path.join(REPO_ROOT, "gitee-token.json")
    with open(token_path, encoding="utf-8") as f:
        cfg = json.load(f)
    return (
        cfg["gitee_personal_access_token"],
        cfg.get("owner", "songrenshuo"),
        cfg.get("repo", "mybatis-plus-sql-visualizer"),
    )


def create_release(token, owner, repo, version, name, body_md, prerelease=False):
    """建发行版。坑位：参数名是 body 不是 description（Gitee 与 GitHub API 不兼容）。"""
    url = f"https://gitee.com/api/v5/repos/{owner}/{repo}/releases"
    data = urllib.parse.urlencode({
        "access_token": token,
        "tag_name": version,
        "target_commitish": "master",
        "name": name,
        "body": body_md,
        "prerelease": "true" if prerelease else "false",
    }).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    resp = json.load(urllib.request.urlopen(req, timeout=60))
    return resp["id"]


def upload_zip(token, owner, repo, release_id, zip_path):
    """上传附件。坑位：端点只有 attach_files（upload_files / assets 均 404）；
    附件 filename 直接决定下载 URL 尾段。"""
    zip_name = os.path.basename(zip_path)
    boundary = "----UploadBoundary"
    with open(zip_path, "rb") as f:
        zip_data = f.read()
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{zip_name}"\r\n'
        f"Content-Type: application/zip\r\n\r\n"
    ).encode() + zip_data + (
        f"\r\n--{boundary}\r\n"
        f'Content-Disposition: form-data; name="access_token"\r\n\r\n'
        f"{token}\r\n--{boundary}--\r\n"
    ).encode()
    url = f"https://gitee.com/api/v5/repos/{owner}/{repo}/releases/{release_id}/attach_files"
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    resp = json.load(urllib.request.urlopen(req, timeout=180))
    return resp


def verify_release(token, owner, repo, release_id, version, zip_name, prerelease):
    """回读校验：prerelease 标记、附件在场、downloadUrl 与预期逐字一致。"""
    url = f"https://gitee.com/api/v5/repos/{owner}/{repo}/releases/{release_id}?access_token={token}"
    resp = json.load(urllib.request.urlopen(url, timeout=60))
    assert resp.get("prerelease") == prerelease, \
        f"prerelease mismatch: expected {prerelease}, got {resp.get('prerelease')}"
    expected_url = f"https://gitee.com/{owner}/{repo}/releases/download/{version}/{zip_name}"
    assets = resp.get("assets", [])
    urls = {a["browser_download_url"] for a in assets}
    assert expected_url in urls, f"downloadUrl mismatch: expected {expected_url}, got {urls}"
    return resp


def main():
    parser = argparse.ArgumentParser(description="Gitee 自动发行脚本")
    parser.add_argument("--version", required=True,
                        help="版本号，逐字等于 gradle.properties（如 2026.10.6 或 2026.10.6-beta.1）")
    parser.add_argument("--notes", required=True,
                        help="发行说明 markdown 路径（全文作为发行版正文 body）")
    parser.add_argument("--name", default=None,
                        help="发行版标题（默认: 🎉 SQL Visualizer v{version}，内测加「 内测版」请显式传）")
    parser.add_argument("--prerelease", action="store_true", help="标记为预览版本（内测通道）")
    parser.add_argument("--skip-upload", action="store_true", help="跳过附件上传（仅建发行版）")
    parser.add_argument("--skip-verify", action="store_true", help="跳过回读校验")
    args = parser.parse_args()

    version = args.version
    zip_name = f"mybatis-plus-sql-visualizer-{version}.zip"
    zip_path = os.path.join(REPO_ROOT, "build", "distributions", zip_name)
    notes_path = args.notes if os.path.isabs(args.notes) else os.path.join(REPO_ROOT, args.notes)
    name = args.name or f"\U0001f389 SQL Visualizer v{version}"

    token, owner, repo = load_config()

    if not os.path.exists(zip_path):
        print(f"ERROR: zip not found: {zip_path}\n  先跑 ./gradlew buildPlugin", file=sys.stderr)
        sys.exit(1)
    if not os.path.exists(notes_path):
        print(f"ERROR: notes not found: {notes_path}", file=sys.stderr)
        sys.exit(1)

    with open(notes_path, encoding="utf-8") as f:
        body_md = f.read()

    print(f"[1/4] Creating release v{version} ...")
    release_id = create_release(token, owner, repo, version, name, body_md, args.prerelease)
    print(f"  Release ID: {release_id}")

    if not args.skip_upload:
        print(f"[2/4] Uploading {zip_name} ...")
        upload_zip(token, owner, repo, release_id, zip_path)
        print("  Upload complete")
    else:
        print("[2/4] Skipped (--skip-upload)")

    if not args.skip_verify:
        print("[3/4] Verifying ...")
        verify_release(token, owner, repo, release_id, version, zip_name, args.prerelease)
        print("  Verification passed")
    else:
        print("[3/4] Skipped (--skip-verify)")

    print("[4/4] Done!")
    print(f"  Release URL : https://gitee.com/{owner}/{repo}/releases/{release_id}")
    print(f"  Download URL: https://gitee.com/{owner}/{repo}/releases/download/{version}/{zip_name}")


if __name__ == "__main__":
    main()
