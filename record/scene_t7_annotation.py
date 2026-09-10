# -*- coding: utf-8 -*-
"""T7 annotation-preview：@Select script 方法行 → gutter 点击 → SQL 弹窗 →
参数输入框回填 username/status → SQL 实时回填 → 关闭。

前置：UserMapper.java 打开，滚动到 line 33（@Select("<script>")），
gutter 图标在 GUTTER 坐标；无弹窗。
"""
import sys
import time

from gifkit import Recorder, click, keys, typewrite, wait_stable

REGION = (277, 25, 1370, 984)
OUT = "perf/t7_annotation.mp4"

GUTTER = (764, 360)      # line 33 @Select gutter 图标（T-GifHeader 两行头部后重标）
IN_USERNAME = (584, 548) # 参数 #username 输入框
IN_STATUS = (584, 613)   # 参数 #status 输入框
CANCEL = (1480, 822)     # 弹窗「取消」


def main():
    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    click(*GUTTER)                        # 1. 点注解行 gutter 图标
    w = wait_stable(REGION)
    print("popup stable wait:", w)
    time.sleep(1.3)                       # 弹窗定格

    click(*IN_USERNAME, move_dur=0.3)     # 2. 回填 username
    typewrite("song", interval=0.06)
    time.sleep(0.5)

    click(*IN_STATUS, move_dur=0.3)       # 3. 回填 status
    typewrite("1", interval=0.06)
    time.sleep(2.0)                       # SQL 回填效果定格

    click(*CANCEL, move_dur=0.3)          # 4. 关闭
    time.sleep(0.9)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
