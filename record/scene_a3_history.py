# -*- coding: utf-8 -*-
"""A3 SQL 历史（sql-history.gif，并入 A38 正则检索）20261011：
一级文件列表双击 sql-history-2026-10-08.sql（45 条）→ 二级列表 →
拖滚动条到底触发滚动分批续写 → 双击条目开预览弹窗 → Esc 关闭 →
点 .* 正则开关 → 搜索框粘贴正则 → 列表过滤。
标定（20261011，工具窗加宽 ~953 起）：文件行 (1125,466)；二级滚动条 x≈1370；
正则开关 (1334,212)；搜索框 (1168,208)。
"""
import sys
import time

from gifkit import Recorder, click, double_click, move, paste

REGION = (277, 25, 1370, 984)
OUT = "perf/a3_history.mp4"

FILE_ROW = (1125, 466)
SCROLL_X = 1370
REGEX_BTN = (1334, 212)
SEARCH_BOX = (1168, 208)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 双击历史文件进二级
    double_click(*FILE_ROW, move_dur=0.35)
    time.sleep(2.2)

    # 2. 拖滚动条到底 → 滚动分批续写
    pyautogui.moveTo(SCROLL_X, 320); time.sleep(0.25)
    pyautogui.mouseDown(); time.sleep(0.12)
    pyautogui.moveTo(SCROLL_X, 600, duration=0.45)
    pyautogui.mouseUp(); time.sleep(1.3)

    # 3. 双击可见条目开预览
    double_click(1100, 320, move_dur=0.3)
    time.sleep(2.2)

    # 4. Esc 关预览 → 正则检索
    pyautogui.press('esc'); time.sleep(0.8)
    click(*REGEX_BTN, move_dur=0.3, pauses=(0.15, 0.2))
    time.sleep(0.5)
    click(*SEARCH_BOX, move_dur=0.3, pauses=(0.15, 0.2))
    paste('JOIN station_info')
    time.sleep(1.4)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
