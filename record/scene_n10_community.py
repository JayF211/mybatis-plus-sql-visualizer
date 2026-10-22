# -*- coding: utf-8 -*-
"""N10「加入交流群」新增录制（T-DocsRefresh1023 / T-SqlLogCommunityDialog）。

分镜（约 6 秒）：
1. SQL 日志页签（列表有内容），停 1.0s
2. 点工具栏交流群图标 → 原生弹框（作者微信 + 交流群说明并排），停 2.2s
3. 关闭弹框，停 0.9s

前置（prep）：SQL Visualizer 工具窗已开、SQL 日志页签激活、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui
from gifkit import Recorder, click
from robot import find_components

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n10_community.mp4")

OX, OY = REGION[0], REGION[1]
COMMUNITY_ICON = (1094, 126)   # 工具栏交流群图标（region 相对，实测）


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def main():
    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(0.9)
        click(*abs_pt(COMMUNITY_ICON), move_dur=0.35, pauses=(0.2, 2.4))
        pyautogui.press("escape")      # 关弹框
        time.sleep(1.0)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
