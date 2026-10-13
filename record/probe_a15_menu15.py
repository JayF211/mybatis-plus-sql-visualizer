# -*- coding: utf-8 -*-
"""A15 探针 v15：定稿几何 + 走完整流程，看**执行结果落在哪里**（v13 take 里没入镜）。

几何（窗口 277,0 1370x1030；Services 已开）：
  表名行 L59  screen y≈144     菜单末项 ≈1003     子菜单三项 ≈920/960/998（x≈1000）
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

TOKEN = (557, 144)
MENU_LAST = (700, 1003)
SUB_SAMPLE = (1000, 998)


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
    u32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(0), 277, 0, 1370, 1030, 0x0004)
    time.sleep(1.0)
    print("rect:", next(r for h, p, t, r in windows() if h == hwnd))

    pyautogui.hotkey('alt', '8'); time.sleep(1.8)
    pyautogui.click(700, 300); time.sleep(0.5)
    pyautogui.hotkey('ctrl', 'home'); time.sleep(0.8)
    post_wheel(hwnd, -19)
    time.sleep(0.9)

    pyautogui.click(*TOKEN, button='right'); time.sleep(1.6)
    pyautogui.moveTo(*MENU_LAST, duration=0.5); time.sleep(1.6)
    pyautogui.click(*SUB_SAMPLE); time.sleep(3.5)
    pyautogui.screenshot().save("shots/p15_after_exec.png")
    print("exec shot saved")


if __name__ == "__main__":
    sys.exit(main())
