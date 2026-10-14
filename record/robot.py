# -*- coding: utf-8 -*-
"""组件树定位客户端 —— 直连 IDE 内的 robot-server，按**组件身份**取屏幕坐标。

**为什么要有这个**：像素坐标点不准（弹窗高度随内容变、悬停才渲染、CJK 双宽、独立窗口遮挡）。
robot-server 是 JetBrains 官方 UI 测试框架的**服务端插件**，跑在 IDE 进程内，提供 HTTP/JSON 接口：
按 XPath/类名/文本找到组件，**直接把该组件当前的屏幕矩形算给你**。于是「定位」与「点击」解耦：
定位靠组件身份（稳），点击仍用现有 pyautogui（不用改管线）。

**协议（从 remote-robot.jar / robot-server 插件 jar 反查核实，非猜测）**：
- `POST /xpath/component`  请求 `{"xpath": "..."}` → 响应 `{"elementList": [{id?, className, name, x, y, width, height}, ...]}`
- `POST /js/execute`       请求 `{"script": "..."}`
- `GET  /screenshot`       截图
- 端口由 IDE 系统属性 `robot-server.port` 决定，**默认 8580**，只监听本机。

**把 robot-server 装进录制沙盒（两种方式）**：
1. 官方 DSL（只覆盖**测试**沙盒，不覆盖 `runIde` 的沙盒）：`intellijPlatformTesting { plugins { robotServerPlugin("0.11.23") } }`；
2. 本仓录制用的 `runIde` 沙盒：把 `robot-server-plugin-<ver>.zip` 解包到某稳定目录
   （本仓放 `record/robot-plugins/`），再给沙盒 `config/idea64.exe.vmoptions` 加一行
   `-Didea.plugins.path=<该目录绝对路径>`（`runIde` 会合并这个文件，见 skill 坑表 32），
   于是它不会被 `prepareSandbox` 重建 `plugins/` 冲掉。

**用法**：
    python robot.py probe                 # 探活 + 打印根节点，确认端口/协议
    python robot.py find "//div[@class='...']"
    python robot.py click "<xpath>"
    # 脚本里：
    from robot import click_component, find_components
    click_component("//div[@accessiblename='执行 SQL']")
"""
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_BASE = os.environ.get("ROBOT_SERVER", "http://127.0.0.1:8580")


class RobotServerError(RuntimeError):
    pass


def _request(path, body=None, base=DEFAULT_BASE, timeout=20, method=None):
    url = base.rstrip("/") + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers,
                                 method=method or ("POST" if data is not None else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:400]
        raise RobotServerError("HTTP %s %s -> %s: %s" % (e.code, path, e.reason, detail))
    except urllib.error.URLError as e:
        raise RobotServerError("连不上 robot-server（%s）：%s——沙盒起了吗？robot-server 装了吗？"
                               % (url, e.reason))
    if not raw:
        return None
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return raw


def find_components(xpath, base=DEFAULT_BASE, timeout=20, **kw):
    """按 XPath 找组件，返回原始组件 dict 列表（字段见模块 docstring）。"""
    resp = _request("/xpath/component", {"xpath": xpath}, base=base, timeout=timeout, **kw)
    if isinstance(resp, dict):
        return resp.get("elementList") or []
    return []


def find_one(xpath, base=DEFAULT_BASE, timeout=20, **kw):
    """找第一个命中的组件；没有则 None。"""
    hits = find_components(xpath, base=base, timeout=timeout, **kw)
    return hits[0] if hits else None


def component_center(comp, offset=(0, 0)):
    """组件 dict → 屏幕中心点。字段缺失时抛错（**不猜坐标**）。"""
    need = ("x", "y", "width", "height")
    miss = [k for k in need if comp.get(k) is None]
    if miss:
        raise RobotServerError("组件缺少坐标字段 %s：%s" % (miss, comp))
    return (int(comp["x"]) + int(comp["width"]) // 2 + offset[0],
            int(comp["y"]) + int(comp["height"]) // 2 + offset[1])


def click_component(xpath, base=DEFAULT_BASE, offset=(0, 0), button="left",
                    move_first=True, timeout=20):
    """找到组件即点其中心，返回 True；**没找到返回 False（不猜坐标）**。"""
    comp = find_one(xpath, base=base, timeout=timeout)
    if not comp:
        return False
    x, y = component_center(comp, offset=offset)
    import pyautogui
    if move_first:
        pyautogui.moveTo(x, y, duration=0.25)
    pyautogui.click(x, y, button=button)
    return True


def require_component(xpath, base=DEFAULT_BASE, offset=(0, 0), retries=3, interval=0.6, **kw):
    """开录前的闸门：找不到就抛错（与 verify.Guard.require / anchor.require_anchor 同性质）。"""
    import time
    for _ in range(max(1, retries)):
        comp = find_one(xpath, base=base, **kw)
        if comp:
            return comp
        time.sleep(interval)
    raise RobotServerError("组件未找到: %s——XPath 写错了？界面还没出现？" % xpath)


def screenshot(base=DEFAULT_BASE, out=None, timeout=30):
    """取 IDE 侧截图（返回 bytes；给了 out 就落盘）。"""
    raw = _request("/screenshot", base=base, timeout=timeout)
    if not isinstance(raw, (bytes, bytearray)):
        raise RobotServerError("截图接口返回的不是二进制：%r" % type(raw))
    if out:
        with open(out, "wb") as f:
            f.write(raw)
    return raw


def execute_js(script, base=DEFAULT_BASE, timeout=30):
    """在 IDE 进程里跑一段脚本（robot-server 的 /js/execute）。"""
    return _request("/js/execute", {"script": script}, base=base, timeout=timeout)


def _cli(argv):
    base = os.environ.get("ROBOT_SERVER", DEFAULT_BASE)
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[1]
    try:
        if cmd == "probe":
            print("探活 %s ..." % base)
            root = find_components("//div")
            print("根节点数:", len(root))
            if root:
                print("首个:", json.dumps(root[0], ensure_ascii=False)[:300])
            return 0
        if cmd == "find" and len(argv) >= 3:
            hits = find_components(argv[2], base=base)
            print("命中 %d 个" % len(hits))
            for h in hits[:5]:
                print("  ", json.dumps(h, ensure_ascii=False)[:220])
            return 0 if hits else 1
        if cmd == "click" and len(argv) >= 3:
            ok = click_component(argv[2], base=base)
            print("已点击" if ok else "未找到组件（未点击）")
            return 0 if ok else 1
        if cmd == "shot":
            out = argv[2] if len(argv) >= 3 else "robot_shot.png"
            screenshot(base=base, out=out)
            print("已存", out)
            return 0
    except RobotServerError as e:
        print("错误:", e)
        return 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(_cli(sys.argv))
