# -*- coding: utf-8 -*-
"""A15 探针 v7：滚到顶 + `apps` 键在光标处开菜单 → 量末项/子菜单坐标。

v6 已证实：PostMessage(WM_MOUSEWHEEL) 能把第 59 行滚到编辑区最上一行（screen y≈139）。
菜单从光标处**向下**展开（点高处才有空间向下），末项 y ≈ 光标 y + 818；
子菜单从末项**向上**展开 → 两者都落在取景框（y≤1009）内。
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

CROP = {"left": 480, "top": 120, "width": 900, "height": 950}
LAST_ITEM_GUESS = (700, 950)      # 末项「SQL 快捷模板 ▸」粗估（菜单从 y≈139 向下展开）


def post_wheel(hwnd, notches, sx=1000, sy=700):
    user32 = ctypes.windll.user32
    lp = (sy << 16) | (sx & 0xFFFF)
    for _ in range(abs(notches)):
        delta = 120 if notches > 0 else -120
        user32.PostMessageW(hwnd, 0x020A, ((delta & 0xFFFF) << 16), lp)
        time.sleep(0.03)


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
    post_wheel(hwnd, -5)
    time.sleep(0.8)

    with mss.mss() as sct:      # 开菜单前先留一张（确认光标行位置）
        mss.tools.to_png(sct.grab({"left": 480, "top": 120, "width": 500, "height": 120}).rgb,
                         (500, 120), output="shots/p7_caret_row.png")

    pyautogui.press('apps')
    time.sleep(1.6)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(CROP).rgb, (CROP["width"], CROP["height"]),
                         output="shots/p7_menu.png")
    print("menu shot ok")

    pyautogui.moveTo(*LAST_ITEM_GUESS, duration=0.5)
    time.sleep(1.8)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(CROP).rgb, (CROP["width"], CROP["height"]),
                         output="shots/p7_submenu.png")
    print("submenu shot ok")
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc')


if __name__ == "__main__":
    sys.exit(main())
