# -*- coding: utf-8 -*-
"""A15 探针 v8：`apps` 键的菜单开在**鼠标位置**（v7 反证：鼠标在 (1000,700) 时菜单被夹到屏幕底
220..1045，与光标行 y=134 无关）。所以把鼠标停到编辑区**上部**、光标仍压在表名上，
菜单即从上部向下展开，末项与子菜单都能进取景框。
"""
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

MOUSE_TOP = (557, 160)            # 编辑区上部（菜单从这里向下展开）
LAST_ITEM_GUESS = (700, 950)      # 末项粗估：160 + 818 ≈ 978
CROP = {"left": 480, "top": 120, "width": 900, "height": 950}


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

    # 光标压表名（不选中；T-SqlTemplateCaret 之后光标即可）
    pyautogui.click(600, 400); time.sleep(0.4)
    goto_col(59, 15)
    time.sleep(0.6)
    print("caret placed")

    # 鼠标停到编辑区上部 → apps 开菜单
    pyautogui.moveTo(*MOUSE_TOP, duration=0.3); time.sleep(0.4)
    pyautogui.press('apps')
    time.sleep(1.6)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(CROP).rgb, (CROP["width"], CROP["height"]),
                         output="shots/p8_menu.png")
    print("menu shot")

    pyautogui.moveTo(*LAST_ITEM_GUESS, duration=0.5)
    time.sleep(1.8)
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab(CROP).rgb, (CROP["width"], CROP["height"]),
                         output="shots/p8_submenu.png")
    print("submenu shot")
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc')


if __name__ == "__main__":
    sys.exit(main())
