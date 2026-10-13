# -*- coding: utf-8 -*-
"""A15 探针 v4：滚轮挪到**编辑区空白处**再滚（v3 在标识符上滚 → 触发「鼠标悬停显示文档」弹层，滚轮被吃）。

目标：把第 59 行 `CREATE TABLE `car_device_info`` 沉到编辑区下部（y≈900+），
右键时菜单向上展开，末项「SQL 快捷模板 ▸」才落进取景框（region 只到 y=1009）。
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

NEUTRAL = (1150, 700)      # 编辑区右侧空白（文字只到 x≈960）——悬停无标识符，不会弹文档层


def main():
    ide_pid = sandbox_pid()
    hwnd = next(h for h, p, t, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    force_en_us()

    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.4)
    close_preview(ide_pid)
    pyautogui.click(1420, 908); time.sleep(0.6)

    pyautogui.click(600, 400); time.sleep(0.4)
    goto_col(59, 15)
    time.sleep(0.5)
    pyautogui.moveTo(*NEUTRAL, duration=0.2); time.sleep(0.3)
    pyautogui.scroll(10)
    time.sleep(0.9)
    pyautogui.screenshot().save("shots/p4_01_scrolled.png")
    print("ok")


if __name__ == "__main__":
    sys.exit(main())
