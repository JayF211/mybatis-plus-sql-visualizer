# -*- coding: utf-8 -*-
"""A15 探针 v14：**先开 Services 再定位**（v13 的坑：prep 里开 Services 把编辑器压矮了，
goto_col 的居中与 -3 档滚动都对不上 → 右键落在 L54 `VARCHAR` 上，子菜单三项全灰）。

改法：定位不再依赖「居中 + 相对滚动」，改为**从文件头起的绝对滚动**——
Ctrl+Home 把首行钉在第 1 行，再滚固定档数（1 档 = 3 行）把目标行送到顶部。
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
from nav import force_en_us  # noqa: E402

NOTCHES = int(sys.argv[1]) if len(sys.argv) > 1 else -19
TOKEN = (557, 155)
LAST_ITEM = (700, 975)


def post_wheel(hwnd, notches, sx=1000, sy=400):
    user32 = ctypes.windll.user32
    lp = (sy << 16) | (sx & 0xFFFF)
    for _ in range(abs(notches)):
        delta = 120 if notches > 0 else -120
        user32.PostMessageW(hwnd, 0x020A, ((delta & 0xFFFF) << 16), lp)
        time.sleep(0.03)


def main():
    ide_pid = sandbox_pid()
    hwnd = next(h for h, p, t, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 300), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    force_en_us()

    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.4)
    close_preview(ide_pid)

    u32 = ctypes.WinDLL("user32", use_last_error=True)
    u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                 ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
    u32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(0), 277, 0, 0, 0, 0x0001 | 0x0004)
    time.sleep(0.9)

    pyautogui.hotkey('alt', '8'); time.sleep(1.8)          # Services 先开
    pyautogui.click(700, 300); time.sleep(0.5)             # 取焦编辑器
    pyautogui.hotkey('ctrl', 'home'); time.sleep(0.8)      # 首行钉在第 1 行
    post_wheel(hwnd, NOTCHES)
    time.sleep(0.9)

    with mss.mss() as sct:
        mss.tools.to_png(sct.grab({"left": 480, "top": 100, "width": 520, "height": 240}).rgb,
                         (520, 240), output="shots/p14_top.png")

    pyautogui.click(*TOKEN, button='right')
    time.sleep(1.7)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab({"left": 500, "top": 840, "width": 900, "height": 145}).rgb,
                         (900, 145), output="shots/p14_menu_bottom.png")

    pyautogui.moveTo(*LAST_ITEM, duration=0.5)
    time.sleep(1.8)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab({"left": 620, "top": 830, "width": 760, "height": 155}).rgb,
                         (760, 155), output="shots/p14_submenu.png")
    print("done")
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc')


if __name__ == "__main__":
    sys.exit(main())
