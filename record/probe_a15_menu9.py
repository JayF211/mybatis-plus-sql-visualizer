# -*- coding: utf-8 -*-
"""A15 探针 v9：把表名滚到编辑区**最上一行**后**右键点在 token 上**。

要点（前八轮实测收敛）：
  · 菜单高约 814px，**底边被屏幕夹住**（top = min(锚点 y, 1080-814≈266)）→ 锚点必须在 y≤195，
    末项才落进取景框（region 底 = 1009）。
  · 锚点 = **光标行**（v8 反证：鼠标在 y=160、光标在 y=414 时，菜单仍在 242）。
  · `apps` 键开菜单时光标曾漂到别的行（v7 状态栏 78:8）→ 改用**右键直接点在 token 上**，
    光标与锚点一次到位、无漂移余地。
  · 滚轮只有 PostMessage(WM_MOUSEWHEEL) 有效（pyautogui.scroll 被 IDE 吞）。
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

TOKEN = (557, 134)                 # 滚到顶后 `car_device_info` 所在行（v6 实测 screen y≈134）
LAST_ITEM = (700, 933)             # 末项「SQL 快捷模板 ▸」粗估（菜单顶 134 + 814 - 15）
SUB_CROP = {"left": 480, "top": 780, "width": 900, "height": 290}


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
    time.sleep(0.9)

    with mss.mss() as sct:      # 滚完先确认 token 行位置
        mss.tools.to_png(sct.grab({"left": 480, "top": 100, "width": 500, "height": 90}).rgb,
                         (500, 90), output="shots/p9_row.png")

    pyautogui.click(*TOKEN, button='right')
    time.sleep(1.7)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(SUB_CROP).rgb, (SUB_CROP["width"], SUB_CROP["height"]),
                         output="shots/p9_menu_bottom.png")
    print("menu bottom shot")

    pyautogui.moveTo(*LAST_ITEM, duration=0.5)
    time.sleep(1.8)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(SUB_CROP).rgb, (SUB_CROP["width"], SUB_CROP["height"]),
                         output="shots/p9_submenu.png")
    print("submenu shot")
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc')


if __name__ == "__main__":
    sys.exit(main())
