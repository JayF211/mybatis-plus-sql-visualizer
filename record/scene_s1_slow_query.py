# -*- coding: utf-8 -*-
"""S1 慢查询高亮（slow-query-highlight.gif）。

素材：demo 的 `/mpj/perf/slow`（SLEEP(1.2) → 稳定 1.2s）+ 普通查询若干。
成片：捕获态下先清空列表 → 灌入「普通查询 + 一条慢查询」→ 慢查询条目**红色标示**、
工具栏出现**慢查询计数**（`慢 1`）→ 点排序下拉切「耗时降序」→ 列表重排（慢查询置顶）。
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
sys.path.insert(0, HERE)

import pyautogui  # noqa: E402
from gifkit import Recorder  # noqa: E402
from locate import sandbox_pid, windows, ensure_foreground  # noqa: E402
from nav import force_en_us  # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/s1_slow_query.mp4"

CAPTURE = (1105, 212)      # 「捕捉」开关（悬停才渲染的标题栏动作；▶=未捕获 / ■=捕获中）
SORT = (1355, 212)         # 「时间升序 ▾」排序下拉
GAZE = (1250, 380)


def hit(path, wait=1.2):
    subprocess.run(["curl", "-s", "-m", "12", "http://127.0.0.1:8082" + path],
                   capture_output=True)
    time.sleep(wait)


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    p = sandbox_pid()
    hwnd = next(h for h, pp, t, r in windows() if pp == p and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=8, click_xy=(700, 300), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")

    for _ in range(2):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.3)

    # prep（不入镜）：捕获由外部脚本预先开启并核验
    # ⚠️ take 内**不碰**工具窗工具栏任何按钮——「捕捉/清空/设置」几个动作在悬停态下相邻且坐标会漂，
    #    实测点「清空」误中「设置」会把整个设置对话框弹出来毁掉整条 take（踩过三次）。
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.5)

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.8)
    hit("/mpj/device/entityList"); time.sleep(1.4)      # 普通查询
    hit("/mpj/ext/chain"); time.sleep(1.4)              # 普通查询
    hit("/mpj/perf/slow", wait=3.0)                     # 慢查询（1.2s）——放最后，列表贴底可见
    pyautogui.moveTo(*GAZE, duration=0.8); time.sleep(2.2)
    pyautogui.moveTo(GAZE[0] - 120, GAZE[1] + 40, duration=0.6); time.sleep(1.4)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
