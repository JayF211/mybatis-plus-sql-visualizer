# -*- coding: utf-8 -*-
"""A2 从调用栈跳转（sql-log-stack-jump.gif）20261011 v3（弹窗已开态起录）：
预览弹窗在场（prep 双击条目打开）→ 悬停 meta 行「调用栈:」→ Frames 弹层（12 帧全展开）→
点「nested UserController:46」→ 编辑器打开 UserController.java 落 46 行。
标定（20261011 mouseMoved 修复后，弹窗 @ (100,148) 实测）：悬停点 (613,251)；nested 行 (556,624)。
"""
import sys
import time

from gifkit import Recorder, click, move

REGION = (277, 25, 1370, 984)
OUT = "perf/a2_stackjump.mp4"

HOVER = (613, 251)
AWAY = (600, 760)
FRAME_CTRL = (556, 624)
EDITOR_LAND = (500, 430)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 鼠标从远离态移回 meta 行 → 400ms 后弹层
    move(*HOVER, dur=0.25)  # 快于 400ms 计时器，锚点=最终落点
    time.sleep(1.3)

    # 2. 点 Controller 帧 → 编辑器跳转
    click(*FRAME_CTRL, move_dur=0.35, pauses=(0.2, 0.2))
    time.sleep(2.6)

    # 3. 鼠标滑向编辑器落点
    move(*EDITOR_LAND, dur=0.5)
    time.sleep(1.0)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
