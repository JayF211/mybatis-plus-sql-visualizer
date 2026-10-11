# -*- coding: utf-8 -*-
"""A36 历史与日志导出（history-export.gif）20261011：
历史二级列表两条已选中 → 右键菜单「导出 SQL」→ 格式对话框（SQL 默认）Enter →
存盘对话框（默认名 sql-export-<ts>.sql）Enter → 后台写盘 → balloon「已导出 2 条到 …」。
标定（20261011 屏幕坐标）：菜单导出 SQL (1230,455)。
"""
import sys
import time

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a36_export.mp4"

MENU_EXPORT = (1230, 455)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 菜单点「导出 SQL」
    pyautogui.click(*MENU_EXPORT)
    time.sleep(1.4)

    # 2. 格式对话框（SQL 默认选中）→ Enter
    pyautogui.keyDown('return'); time.sleep(0.08); pyautogui.keyUp('return')
    time.sleep(1.6)

    # 3. 存盘对话框（默认名）→ Enter
    pyautogui.keyDown('return'); time.sleep(0.08); pyautogui.keyUp('return')
    time.sleep(2.8)  # 写盘 + balloon

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
