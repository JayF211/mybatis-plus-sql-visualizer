# -*- coding: utf-8 -*-
"""T8 xml-preview：selectUserByTrim 的 <select> 行 gutter 点击 → SQL 弹窗 →
Ctrl+End 露出 WHERE → 取消勾选 <if roleId> 动态条件 → SQL 实时变化 → 关闭。

前置：UserMapper.xml 打开，滚动到 line 75（selectUserByTrim），
gutter 图标在 GUTTER；无弹窗。
"""
import sys
import time

from gifkit import Recorder, click, keys, wait_stable

REGION = (277, 25, 1370, 984)
OUT = "perf/t8_xml.mp4"

GUTTER = (743, 514)      # line 75 <select> gutter 图标
SQL_AREA = (1000, 450)   # SQL 面板聚焦
CHK_ROLEID = (426, 419)  # 动态条件 roleId != null 复选框
CANCEL = (1478, 822)     # 弹窗「取消」


def main():
    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    click(*GUTTER)                       # 1. gutter 点击
    w = wait_stable(REGION)
    print("popup stable wait:", w)
    time.sleep(1.3)

    click(*SQL_AREA, move_dur=0.28)      # 2. 聚焦 SQL 面板滚到 WHERE
    keys("ctrl", "end")
    time.sleep(0.9)

    click(*CHK_ROLEID, move_dur=0.3)     # 3. 取消勾选 <if roleId>
    time.sleep(2.0)                      # SQL 变化定格

    click(*CANCEL, move_dur=0.3)         # 4. 关闭
    time.sleep(0.9)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
