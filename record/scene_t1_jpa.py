# -*- coding: utf-8 -*-
"""T1 jpa-preview：派生方法 findByUsernameOrEmail gutter → SQL 弹窗定格 →
关闭 → Ctrl+G 跳 @Query 行 → gutter → JPQL 转换定格 → 关闭 → 回跳 42 行（循环无缝）。

前置：UserRepository.java 打开，Ctrl+G 42 居中，无弹窗、无选中。
GUTTER1=(743,512) 已 zoom 精标；GUTTER2=(744,514) 实测可用。
"""
import sys
import time

from gifkit import Recorder, click, pyautogui, wait_stable

REGION = (277, 25, 1370, 984)
OUT = "perf/t1_jpa.mp4"

GUTTER1 = (743, 512)
GUTTER2 = (744, 514)
CANCEL = (1478, 822)


def goto(line):
    pyautogui.hotkey('ctrl', 'g')
    time.sleep(0.6)
    pyautogui.typewrite(str(line), interval=0.06)
    time.sleep(0.35)
    pyautogui.keyDown('return'); pyautogui.keyUp('return')


def main():
    r = Recorder(REGION, OUT)
    time.sleep(1.0)
    r.start()
    time.sleep(0.4)

    click(*GUTTER1, pauses=(0.25, 0.25))     # 1. 派生方法 gutter
    w = wait_stable(REGION)
    print("popup1 stable wait:", w)
    time.sleep(1.6)

    click(*CANCEL, move_dur=0.28)            # 2. 关闭
    time.sleep(0.5)

    goto(147)                                # 3. 场景内跳 @Query
    time.sleep(1.0)

    click(*GUTTER2, pauses=(0.25, 0.25))     # 4. @Query gutter
    w = wait_stable(REGION)
    print("popup2 stable wait:", w)
    time.sleep(1.5)

    click(*CANCEL, move_dur=0.28)            # 5. 关闭
    time.sleep(0.5)

    goto(42)                                 # 6. 回跳起点（循环无缝）
    time.sleep(0.7)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
