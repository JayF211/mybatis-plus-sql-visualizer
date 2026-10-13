# -*- coding: utf-8 -*-
"""A15 探针 v10：v9 右键点 (557,134) 没弹出菜单（那一行实测中心在 y≈145，134 偏高）。
本探针把右键点下移到行中心，并在 0.9s / 1.9s 各抓一次（排除「弹了又被关」）。
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

TOKEN = (557, 148)
LAST_ITEM = (700, 940)
CROP = {"left": 480, "top": 120, "width": 900, "height": 950}


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
    pyautogui.click(600, 400); time.sleep(0.4)
    goto_col(59, 15)
    time.sleep(0.5)
    post_wheel(hwnd, -5)
    time.sleep(0.9)

    pyautogui.click(*TOKEN, button='right')
    time.sleep(0.9)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(CROP).rgb, (CROP["width"], CROP["height"]),
                         output="shots/p10_menu_09s.png")
    print("shot @0.9s")

    pyautogui.moveTo(*LAST_ITEM, duration=0.5)
    time.sleep(1.5)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(CROP).rgb, (CROP["width"], CROP["height"]),
                         output="shots/p10_menu_hover.png")
    print("shot hover")
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc')


if __name__ == "__main__":
    sys.exit(main())
