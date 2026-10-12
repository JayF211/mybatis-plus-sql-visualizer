# -*- coding: utf-8 -*-
"""A40 日志转 SQL 从剪贴板读取（log-to-sql-clipboard.gif）20261011 v6（终版）：
前置：剪贴板已放 Preparing/Parameters 日志、输入/结果区已清。
流程：点输入区 → Ctrl+V 贴日志（「从剪贴板读取」按钮本会话对合成点击免疫，
Ctrl+V 为等价交互面）→ 点转换 ▶（热区偏图标中心左下 ~(985,198)）→
结果区出还原 SQL。
标定（20261011 屏幕坐标）：输入区 (1150,300)；转换 ▶ (985,198)。
坑：工具窗停靠工具栏按钮需九宫格扫点定热区；合成滚轮对编辑器无效用键盘。
"""
import sys
import time

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a40_logtosql.mp4"

INPUT = (1150, 300)
CONVERT = (985, 198)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 焦点进输入区 → Ctrl+V
    pyautogui.click(*INPUT)
    time.sleep(0.5)
    pyautogui.hotkey('ctrl', 'v')
    time.sleep(2.0)

    # 2. 点转换 ▶
    pyautogui.click(*CONVERT)
    time.sleep(2.8)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
