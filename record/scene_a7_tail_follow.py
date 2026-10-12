# -*- coding: utf-8 -*-
"""A7 滚动到底部与尾部跟随（sql-log-tail-follow.gif）20261011：
前置：SQL 日志列表空（由本脚本 prep 清空）、捕获已开、demo 应用 8081 在跑。
流程：Storm 并发灌入 → 视口自动贴底（新条目不断顶上来）→ 滚轮上滑 → 跟随暂停
      （继续灌入但视口不动）→ 点工具栏「滚动到底部」→ 跳回底部并恢复跟随 → 新条目继续贴底。

标定（20261011 屏幕坐标，IDE @ 277,25；从裁剪放大图量）：
  列表区域（滚轮/取焦落点） region (1000,400) → screen (1277,425)
  工具栏「滚动到底部」      region (917,181)  → screen (1194,206)
  工具栏「清空」            region (744,182)  → screen (1021,207)

关键实测（本会话踩出来的）：
  ① 该列表的滚轮**只在列表拿到焦点后才生效**——空白处点一下即可取焦（无行则不会弹预览浮层）；
  ② 滚轮方向：pyautogui.scroll(负) = 向上滚（本环境实测）；
  ③ 滚动条 thumb 拖拽与 PageUp 都不生效，别浪费轮次；
  ④ 列表若不在底部（有未读尾巴）时 auto-follow 才会暂停，滚回底部即恢复——这正是本图要拍的。
"""
import sys
import threading
import time
import urllib.request

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a7_tail_follow.mp4"

LIST_POINT = (1277, 425)
SCROLL_BOTTOM = (1194, 206)
TRASH = (1021, 207)


def feed(path, timeout=20):
    try:
        urllib.request.urlopen("http://localhost:8081" + path, timeout=timeout).read()
    except Exception:  # noqa: BLE001
        pass


def ide_hwnd():
    """按标题找沙盒 IDE 主窗口句柄（不按进程名——沙盒 IDE 是 java.exe）。"""
    import ctypes
    from ctypes import wintypes
    user32 = ctypes.windll.user32
    hits = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
    def cb(h, _l):
        if not user32.IsWindowVisible(h):
            return True
        buf = ctypes.create_unicode_buffer(300)
        user32.GetWindowTextW(h, buf, 300)
        if "mybatis-plus-sql-visualizer-demo" in buf.value:
            hits.append(h)
        return True

    user32.EnumWindows(cb, 0)
    return hits[0] if hits else 0


def wheel_up(hwnd, notches=5, sx=LIST_POINT[0], sy=LIST_POINT[1]):
    """直接向 IDE 主窗口投递 WM_MOUSEWHEEL（本环境唯一能滚动该列表的机制：
    pyautogui.scroll / 滚动条 thumb 拖拽 / PageUp 三者实测全部无效）。"""
    import ctypes
    user32 = ctypes.windll.user32
    WM_MOUSEWHEEL = 0x020A
    lp = (sy << 16) | (sx & 0xFFFF)
    for _ in range(notches):
        user32.PostMessageW(hwnd, WM_MOUSEWHEEL, (120 & 0xFFFF) << 16, lp)
        time.sleep(0.03)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    # ---- prep（录制段外）：清空 → 喂几条（保证点得到行）→ 贴底 → 点行取焦 → Esc 关浮层 ----
    pyautogui.click(*TRASH)
    time.sleep(1.3)
    for path in ("/user/list",
                 "/user/nested?username=ab&minAge=20&maxAge=30",
                 "/logFeed/heartbeat?times=3&intervalMs=200"):
        feed(path)
        time.sleep(0.6)
    time.sleep(1.0)
    pyautogui.click(*SCROLL_BOTTOM)
    time.sleep(1.0)
    pyautogui.click(*LIST_POINT)      # 点行：列表拿到焦点，同时弹出轻量浮层
    time.sleep(1.6)
    pyautogui.press("escape")         # 只关浮层，列表保留焦点（工具窗不会被隐藏）
    time.sleep(1.0)

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.8)

    # 1. 并发灌入：同模板不同参数持续涌入
    threading.Thread(
        target=feed, args=("/logFeed/storm?qps=15&seconds=10",), daemon=True
    ).start()
    time.sleep(3.2)          # 视口自动贴底，新条目不断顶上来

    # 2. 滚轮上滑 → 跟随暂停（后面继续灌入，视口不再动）
    hwnd = ide_hwnd()
    assert hwnd, "未找到沙盒 IDE 主窗口"
    pyautogui.moveTo(*LIST_POINT, duration=0.2)
    wheel_up(hwnd, notches=4)
    time.sleep(2.4)

    # 3. 点「滚动到底部」→ 跳回底部并恢复跟随
    pyautogui.click(*SCROLL_BOTTOM)
    time.sleep(2.4)

    # 4. 跟随恢复后，新条目继续把视口钉在底部
    time.sleep(2.2)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
