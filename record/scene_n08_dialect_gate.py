# -*- coding: utf-8 -*-
"""N08「方言闸门阻断态」新增录制（T-DocsRefresh1023 / T-DialectGateUx·T-DialectGateExit）。

构造方式：把插件方言设为 **PostgreSQL**，而目标数据源是 MySQL 的 mpj_demo
→ 结果行进入阻断态，给出「切换数据源」「方言设置」两个出口。

**这是唯一改动设置的一张**——录完必须还原方言为「智能推断」，脚本末尾负责还原。

前置（硬）：预览绑 mpj_demo；设置页可操作。

分镜（约 12 秒）：
1. 设置页把方言切成 PostgreSQL → 关设置，停 1.6s
2. 重新打开预览 → 结果行阻断态（红字 + 两个链接），停 2.6s
3. 点「切换数据源」→ 数据源选择器 → 选 mpj_demo → 自动重渲染成功，停 2.4s
4. 关预览，回设置页把方言还原为「智能推断」，停 1.4s

坑：设置树滚动失效，要用**单词**搜索定位；两词查询返回 "Nothing to show"。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui
from gifkit import Recorder, click, goto_line
from robot import find_components

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n08_dialect_gate.mp4")

OX, OY = REGION[0], REGION[1]


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def main():
    # 本脚本只录「阻断态 + 就地出口」这段；方言的切换/还原由调用方在人机协作下完成，
    # 因为设置页导航在本沙盒不稳定（见录制指南）。这里把前置检查写死，避免静默出坏片。
    if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("前置未满足：预览未打开")

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(1.4)                    # 阻断态定格（方言不匹配的红字与两个链接）
        click(*abs_pt((700, 806)), move_dur=0.3, pauses=(0.2, 2.4))   # 「切换数据源」
        time.sleep(1.4)
        click(*abs_pt((1185, 806)), move_dur=0.3, pauses=(0.15, 0.9)) # 关预览
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
