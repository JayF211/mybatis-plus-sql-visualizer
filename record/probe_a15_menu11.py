# -*- coding: utf-8 -*-
"""A15 探针 v11：关掉 SQL 的粘性行（sticky lines）。

v10 反证：右键点 (557,148) 弹的是**粘性行自己的菜单**（Configure Sticky Lines… / Disable for SQL / Disable）
——编辑器顶部约 40px 被粘性行覆盖，右键全被它吃掉。粘性行还占掉「末项必须落在取景框内」
所依赖的那点纵向余量，故先关掉它（仅 SQL，属录制环境设置，不影响插件行为）。
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

STICKY_HIT = (557, 148)
DISABLE_SQL = (650, 192)          # 粘性行菜单第二项「Disable for SQL」
TOP_CROP = {"left": 480, "top": 100, "width": 520, "height": 230}


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

    pyautogui.click(*STICKY_HIT, button='right')      # 弹粘性行菜单
    time.sleep(1.2)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab({"left": 480, "top": 120, "width": 620, "height": 160}).rgb,
                         (620, 160), output="shots/p11_sticky_menu.png")
    pyautogui.click(*DISABLE_SQL)                     # 关掉（仅 SQL）
    time.sleep(1.2)

    pyautogui.click(1000, 700)                        # 取焦回编辑器
    time.sleep(0.5)
    goto_col(59, 15)                                  # 重新定位光标到表名
    time.sleep(0.4)
    post_wheel(hwnd, -5)
    time.sleep(0.9)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(TOP_CROP).rgb, (TOP_CROP["width"], TOP_CROP["height"]),
                         output="shots/p11_top_rows.png")
    print("done")


if __name__ == "__main__":
    sys.exit(main())
