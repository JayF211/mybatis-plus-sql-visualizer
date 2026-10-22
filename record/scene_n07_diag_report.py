# -*- coding: utf-8 -*-
"""N07「一键诊断报告」新增录制（T-DocsRefresh1023 / R50）。

**菜单项必须「先悬停再点」**：JBPopup 的菜单项靠 hover 驱动激活，直接 click 常常落空
（本轮实测：同一坐标有时成、有时只关掉菜单）。这是 N05/N06/N07 共用的坑。

分镜（约 10 秒）：
1. 预览面板已开，停 0.9s
2. 「更多」→「分享」→ 分享对话框（七种出口），停 1.8s
3. 选「复制诊断报告（Markdown）」→ 确定 → 复制通知，停 1.4s
4. 再开分享 →「导出诊断报告（HTML）」→ 确定 → 保存框，停 1.4s
5. 取消保存框、关预览，停 1.0s

前置（prep）：预览已打开。
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
OUT = os.path.join(BASE, "perf", "n07_diag_report.mp4")

OX, OY = REGION[0], REGION[1]
MORE = (1223, 208)
ITEM_SHARE = (1233, 318)
DIAG_MD = (512, 529)           # 复制诊断报告（Markdown）
DIAG_HTML = (512, 560)         # 导出诊断报告（HTML）
OK_BTN = (751, 648)            # 分享对话框「确定」
CLOSE = (1185, 806)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def hover_click(pt, hover_wait=0.5, after=1.2):
    """先悬停再点——JBPopup 菜单项需要 hover 激活。"""
    sx, sy = abs_pt(pt)
    pyautogui.moveTo(sx, sy, duration=0.35)
    time.sleep(hover_wait)
    pyautogui.click(sx, sy)
    time.sleep(after)


def main():
    if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("前置未满足：预览未打开")

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(0.8)
        click(*abs_pt((600, 161)), move_dur=0.2, pauses=(0.1, 0.5))   # 清残留菜单

        # 1. 分享 → 复制诊断报告（Markdown）
        hover_click(MORE, after=1.3)
        hover_click(ITEM_SHARE, after=1.8)
        if not has("//div[@accessiblename='分享 SQL']"):
            raise RuntimeError("分享对话框未打开，中止")
        hover_click(DIAG_MD, after=0.6)
        hover_click(OK_BTN, after=1.6)

        # 2. 再开分享 → 导出诊断报告（HTML）
        click(*abs_pt((600, 161)), move_dur=0.2, pauses=(0.1, 0.5))
        hover_click(MORE, after=1.3)
        hover_click(ITEM_SHARE, after=1.8)
        hover_click(DIAG_HTML, after=0.6)
        hover_click(OK_BTN, after=2.0)      # 保存框
        pyautogui.press("escape")           # 取消保存框
        time.sleep(0.8)

        click(*abs_pt(CLOSE), move_dur=0.3)  # 关预览
        time.sleep(1.0)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
