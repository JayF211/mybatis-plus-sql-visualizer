# -*- coding: utf-8 -*-
"""A15 探针 v3：把表名滚到编辑区**下部**再右键——菜单向上展开，末项才落在取景框内。

v2 结论：菜单高约 850px、被**屏幕**底边夹住（末项恒在 y≈1050），而取景框只到 y=1009。
菜单若从点击点**向上**展开，末项就落在点击点上方 → 取景框内。
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
    hwnd = next(h for h, p, t, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    force_en_us()

    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.4)
    close_preview(ide_pid)
    pyautogui.click(1420, 908); time.sleep(0.6)      # 关数据源提示气泡（幂等）

    pyautogui.click(600, 400); time.sleep(0.4)
    goto_col(59, 15)
    time.sleep(0.5)
    pyautogui.moveTo(600, 500); time.sleep(0.2)
    pyautogui.scroll(10)                              # 正数 = 向上滚 → 内容下移，第 59 行沉到下部
    time.sleep(0.8)
    pyautogui.screenshot().save("shots/p3_01_scrolled.png")
    print("scrolled; see shots/p3_01_scrolled.png")


if __name__ == "__main__":
    sys.exit(main())
