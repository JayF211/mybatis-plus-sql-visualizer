# -*- coding: utf-8 -*-
"""A15 探针 v6：用 PostMessage(WM_MOUSEWHEEL) 把表名滚到编辑区最上一行。

v5 教训：`pyautogui.scroll` 的合成滚轮**IDE 不吃**（与本轮 SQL 日志列表同源）——
唯一可用是直接 PostMessage 给 IDE 主窗口（见 record/wheel_msg.py 的既有结论）。
"""
import ctypes
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mss  # noqa: E402
import pyautogui  # noqa: E402

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

from locate import close_preview, ensure_foreground, sandbox_pid, windows  # noqa: E402
from nav import force_en_us, goto_col  # noqa: E402

NOTCHES = int(sys.argv[1]) if len(sys.argv) > 1 else -5      # 负数 = 向下滚（内容上移）
SX, SY = 1000, 700                                            # 滚轮落点：编辑区右侧空白
CROP = {"left": 277, "top": 100, "width": 620, "height": 910}


def main():
    ide_pid = sandbox_pid()
    hwnd = next(h for h, p, t, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    force_en_us()

    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.4)
    close_preview(ide_pid)
    pyautogui.click(1420, 908); time.sleep(0.5)

    pyautogui.click(600, 400); time.sleep(0.4)
    goto_col(59, 15)
    time.sleep(0.5)

    user32 = ctypes.windll.user32
    lp = (SY << 16) | (SX & 0xFFFF)
    for _ in range(abs(NOTCHES)):
        delta = 120 if NOTCHES > 0 else -120
        user32.PostMessageW(hwnd, 0x020A, ((delta & 0xFFFF) << 16), lp)
        time.sleep(0.03)
    time.sleep(1.0)

    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(CROP).rgb, (CROP["width"], CROP["height"]),
                         output="shots/p6_crop.png")
    print("posted %d notches -> shots/p6_crop.png" % NOTCHES)


if __name__ == "__main__":
    sys.exit(main())
