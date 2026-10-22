# -*- coding: utf-8 -*-
"""N11「回滚快照双态」新增录制（T-DocsRefresh1023 / UI-036·UI-041·UI-037）。

前置（硬）：预览绑 mpj_demo；演示对象是 **UPDATE** 语句（快照查询才有意义）。

分镜（约 11 秒）：
1. UPDATE 预览打开，停 1.0s
2. 「更多」→「回滚 SQL」→ 回滚弹窗（说明 + 快照查询建议），停 2.0s
3. 点「执行快照查询」→ 结果网格出现（表格态），停 2.4s
4. 点视图切换（表格 / 文本双态）→ 文本态，停 1.6s
5. 点「在 CSV 编辑器中打开」→ 原生 CSV 编辑器页签，停 2.0s
6. 关闭返回到预览，停 0.9s

坑：菜单项须悬停后点；「执行快照查询」在弹窗底部；CSV 打开会新开编辑器页签
（结束时要关掉它，避免污染下一张录制的构图）。
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
OUT = os.path.join(BASE, "perf", "n11_rollback_snapshot.mp4")

OX, OY = REGION[0], REGION[1]
MORE = (1223, 208)
ITEM_ROLLBACK = (1233, 248)     # 「更多」首项 = 回滚 SQL
SNAPSHOT_BTN = (760, 706)       # 回滚弹窗「执行快照查询」
VIEW_TOGGLE = (560, 400)        # 快照结果区 表格/文本 切换
OPEN_CSV = (700, 400)           # 「在 CSV 编辑器中打开」
TITLE_BLANK = (600, 161)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def hover_click(pt, hover_wait=0.55, after=1.2):
    sx, sy = abs_pt(pt)
    pyautogui.moveTo(sx, sy, duration=0.35)
    time.sleep(hover_wait)
    pyautogui.click(sx, sy)
    time.sleep(after)


def main():
    if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("前置未满足：预览未打开（且应为 UPDATE 语句）")

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(1.0)
        click(*abs_pt(TITLE_BLANK), move_dur=0.2, pauses=(0.1, 0.6))
        hover_click(MORE, after=1.3)
        hover_click(ITEM_ROLLBACK, after=2.2)      # 回滚弹窗

        hover_click(SNAPSHOT_BTN, after=3.0)       # 快照查询结果（表格态）
        hover_click(VIEW_TOGGLE, after=1.8)        # 切文本态
        hover_click(OPEN_CSV, after=2.4)           # 原生 CSV 编辑器
        pyautogui.hotkey("ctrl", "w")              # 关 CSV 页签，回收构图
        time.sleep(1.2)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
