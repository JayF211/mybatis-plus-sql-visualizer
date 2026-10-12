# -*- coding: utf-8 -*-
"""A39 数据源上下文角标（datasource-badge.gif）20261011：
设置页（SQL Log，已滚动到开关可见）勾选「数据源角标显示完整上下文」→ OK →
列表角标 [MySQL] → [MySQL · mp_demo@localhost:xxxx] 即时切换。
标定（20261011 实测）：复选框 (907,374)、OK (1242,914)。
"""
import sys
import time

from gifkit import Recorder, click

REGION = (277, 25, 1370, 984)
OUT = "perf/a39_badge.mp4"

CHECKBOX = (907, 374)
OK_BTN = (1242, 914)


def main():
    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 勾选「数据源角标显示完整上下文」
    click(*CHECKBOX, move_dur=0.3, pauses=(0.2, 0.3))
    time.sleep(0.8)

    # 2. OK 关闭设置
    click(*OK_BTN, move_dur=0.4, pauses=(0.2, 0.2))
    time.sleep(2.4)  # 列表角标重渲染

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
