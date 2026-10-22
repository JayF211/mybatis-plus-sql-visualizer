# -*- coding: utf-8 -*-
"""N08「方言闸门阻断态」新增录制（T-DocsRefresh1023 / T-DialectGateUx·T-DialectGateExit）。

**机制订正（源码实证）**：闸门在**执行时**触发，不在渲染时——所以必须先点「执行 SQL」
才会出现阻断态与两个出口（文案取自 bundle：
`execute.dialectGate.rerenderUnavailable` / `action.switchDataSource` = 「切换数据源」/
`action.openSettings` = 「方言设置」）。

构造方式：把插件方言设为 **PostgreSQL**，目标数据源是 MySQL 的 mpj_demo
→ 点执行 → 结果行进入阻断态。

**这是唯一改动设置的一张**——录完必须还原方言为「智能推断」。

分镜（约 12 秒）：
1. 预览已开（方言已是 PostgreSQL），停 1.0s
2. 点「执行 SQL」→ 阻断态（红字说明 + 两个链接），停 2.8s
3. 点「切换数据源」→ 数据源选择器 → 选 mpj_demo → 按目标方言重渲染并重试，停 2.4s
4. 关预览，停 0.9s

前置（硬）：
1. 预览绑 mpj_demo（MySQL）；
2. 插件方言已手工改成 PostgreSQL（脚本不负责改，避免在录制段里导航设置页）。

坑：设置树滚动失效，改方言要走搜索框 + 单词查询。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui
from gifkit import Recorder, click, wait_stable
from robot import find_components

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n08_dialect_gate.mp4")

OX, OY = REGION[0], REGION[1]
EXECUTE = (924, 807)             # 预览底部「执行 SQL」——闸门在这里触发
SWITCH_DS = (700, 806)           # 阻断态里的「切换数据源」（位置待现场标定）
CLOSE = (1185, 806)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def main():
    if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("前置未满足：预览未打开")

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(1.0)
        click(*abs_pt(EXECUTE), move_dur=0.3)      # 触发闸门
        wait_stable(REGION)
        time.sleep(2.8)                            # 阻断态定格（红字 + 两出口）

        click(*abs_pt(SWITCH_DS), move_dur=0.3, pauses=(0.2, 2.4))
        time.sleep(1.2)

        click(*abs_pt(CLOSE), move_dur=0.3, pauses=(0.15, 0.9))
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
