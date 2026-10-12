# -*- coding: utf-8 -*-
"""A1 跳转到源码（sql-log-jump-source.gif）20261011：
增强捕获条目右键 → 跳转到 Java → 编辑器打开 UserServiceImpl.java 并落到执行 SQL 的行。
前置：列表已有条目（时间降序 #3 INSERT/#2 SELECT:55/#1 SELECT:41）、预览已关、编辑器无文件打开。
菜单项偏移：右键点 → 跳转到 Java = (+90, +209)（20261011 实测标定）。
"""
import sys
import time

from gifkit import Recorder, click, right_click, wait_stable, move

REGION = (277, 25, 1370, 984)
OUT = "perf/a1_jump.mp4"

PREVIEW_CLOSE = (1480, 497)  # 内嵌预览 ✕（条目 #2 选中态时）
ENTRY2 = (1250, 395)         # 条目 #2 SELECT UserServiceImpl.java:55 行区
MENU_JUMP = (1340, 604)      # 右键(1250,395) 后「跳转到 Java」= +90,+209


def main():
    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.5)

    # 1. 右键条目 #2 → 上下文菜单
    right_click(*ENTRY2, move_dur=0.35)
    time.sleep(1.4)

    # 2. 跳转到 Java
    click(*MENU_JUMP, move_dur=0.3, pauses=(0.2, 0.2))
    time.sleep(2.8)  # 编辑器开文件 + 滚到目标行

    # 3. 鼠标滑向落点行，引导视线
    move(760, 380, dur=0.5)
    time.sleep(1.2)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
