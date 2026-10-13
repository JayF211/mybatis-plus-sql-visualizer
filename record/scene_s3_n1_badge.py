# -*- coding: utf-8 -*-
"""S3 同结构（N+1）徽章（n1-badge.gif）。

素材：demo 的 `/mpj/perf/n1`（循环按 id 单查 5 次 → 同模板 5 条）。
成片：捕获态下灌入两轮 N+1 → 列表里同一模板聚成一组、元信息行出现「同结构 N 条」徽章
→ 右键徽章 → 菜单里选「只看同结构」（若菜单未保持则停在徽章上收尾）。
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
OUT = "perf/s3_n1_badge.mp4"

GAZE_BADGE = (1490, 335)     # 「同结构 N 条」徽章大致位置（列表右上）
PARK = (1000, 700)


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

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.8)
    hit("/mpj/perf/n1", wait=1.6)        # 第一轮 5 条同模板
    hit("/mpj/perf/n1", wait=2.4)        # 第二轮 → 同结构计数更高
    pyautogui.moveTo(*GAZE_BADGE, duration=0.9); time.sleep(2.4)   # 凝视徽章
    pyautogui.click(*GAZE_BADGE, button='right'); time.sleep(1.8)  # 右键徽章
    pyautogui.moveTo(GAZE_BADGE[0] + 40, GAZE_BADGE[1] + 30, duration=0.5); time.sleep(1.6)
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.6)
    pyautogui.moveTo(*PARK, duration=0.6); time.sleep(1.2)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
