# -*- coding: utf-8 -*-
"""A15 探针 v12：-3 档滚动（少 6 行）→ 表名行落在 y≈177 → 菜单顶 177、末项 ≈976、
子菜单从末项向上展开 → 全在取景框（y≤1009）内。本探针量末项与子菜单三项的屏幕坐标。
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

NOTCHES = int(sys.argv[1]) if len(sys.argv) > 1 else -3
TOKEN = (557, 177)
LAST_ITEM = (700, 976)
CROP = {"left": 560, "top": 830, "width": 830, "height": 200}


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
    post_wheel(hwnd, NOTCHES)
    time.sleep(0.9)

    with mss.mss() as sct:
        mss.tools.to_png(sct.grab({"left": 480, "top": 100, "width": 520, "height": 230}).rgb,
                         (520, 230), output="shots/p12_top.png")

    pyautogui.click(*TOKEN, button='right')
    time.sleep(1.7)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(CROP).rgb, (CROP["width"], CROP["height"]),
                         output="shots/p12_menu_bottom.png")

    pyautogui.moveTo(*LAST_ITEM, duration=0.5)
    time.sleep(1.8)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(CROP).rgb, (CROP["width"], CROP["height"]),
                         output="shots/p12_submenu.png")
    print("done")
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc')


if __name__ == "__main__":
    sys.exit(main())
