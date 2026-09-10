# -*- coding: utf-8 -*-
"""T2 batch-operation：saveBatch 调用行 gutter 点击 → 批量参数面板 →
取消勾选 age 列 → INSERT SQL 实时收缩 → 关闭。

前置：UserServiceImpl.java 打开，滚动到 line 211（saveBatch），
gutter 图标在 GUTTER；无弹窗。
"""
import sys
import time

from gifkit import Recorder, click, wait_stable

REGION = (277, 25, 1370, 984)
OUT = "perf/t2_batch.mp4"

GUTTER = (758, 439)     # line 211 saveBatch gutter 图标
CHK_AGE = (429, 523)    # 参数列 toInsert.age 复选框
CANCEL = (1478, 822)    # 弹窗「取消」


def main():
    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    click(*GUTTER)                       # 1. 点 saveBatch gutter 图标
    w = wait_stable(REGION)
    print("popup stable wait:", w)
    time.sleep(1.4)                      # 批量面板定格

    click(*CHK_AGE, move_dur=0.35)       # 2. 取消勾选 age 列
    time.sleep(2.0)                      # SQL 收缩定格

    click(*CANCEL, move_dur=0.3)         # 3. 关闭
    time.sleep(0.9)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
