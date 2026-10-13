# -*- coding: utf-8 -*-
"""A15 探针 v2：光标压表名 → 右键 → 量菜单末项/子菜单的**屏幕**坐标。

与 v1 的差别：T-SqlTemplateCaret（3ac0b253）之后**不再需要选中**，光标落在标识符上即可；
所以先验证「只落光标」子菜单三项是否已启用，再量菜单几何。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui  # noqa: E402

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

from locate import close_preview, ensure_foreground, sandbox_pid, windows  # noqa: E402
from nav import force_en_us, goto_col  # noqa: E402


def main():
    ide_pid = sandbox_pid()
    print("sandbox pid =", ide_pid)
    hwnd = next(h for h, p, t, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    force_en_us()

    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.4)
    close_preview(ide_pid)

    # 关掉「New/updated database configurations found」气泡（它会盖住编辑器右下）
    pyautogui.screenshot().save("shots/p2_00_before.png")
    pyautogui.click(1420, 908); time.sleep(0.8)
    pyautogui.screenshot().save("shots/p2_01_after_balloon.png")

    # 光标压到第 59 行的 `car_device_info` 上
    pyautogui.click(600, 400); time.sleep(0.4)
    goto_col(59, 15)
    time.sleep(0.6)
    pyautogui.screenshot().save("shots/p2_02_caret.png")
    print("caret placed")

    # 右键（不选中，验证 caret 路径）
    pyautogui.click(557, 374, button='right')
    time.sleep(1.6)
    pyautogui.screenshot().save("shots/p2_03_menu.png")
    print("menu shot saved")

    # 全屏原图也存一份（菜单可能超出 IDE 窗口）
    pyautogui.screenshot().save("shots/p2_04_menu_full.png")


if __name__ == "__main__":
    sys.exit(main())
