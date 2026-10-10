# -*- coding: utf-8 -*-
"""A45 原生 MyBatis 拦截器自动注入（interceptor-inject.gif）20261011：
设置页勾选「自动注入源码捕获拦截器」→ 选择注入模块对话框（prep 已打开）→
只保留 native-mybatis-demo → OK → 生成拦截器 + mybatis-config.xml 注册 + 通知气球。
标定（20261011 实测，屏幕坐标＝区域坐标+277/+25）：模块复选框 x=740，行 y=468/503/538；OK (1052,628)。
录制后须还原 demo 仓工作树（注入会写文件）。
"""
import sys
import time

from gifkit import Recorder, click

REGION = (277, 25, 1370, 984)
OUT = "perf/a45_inject.mp4"

CHK_DEMO = (740, 468)
CHK_KOTLIN = (740, 503)
OK_BTN = (1052, 628)


def main():
    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 只保留 native-mybatis-demo
    click(*CHK_DEMO, move_dur=0.3, pauses=(0.15, 0.25))
    time.sleep(0.5)
    click(*CHK_KOTLIN, move_dur=0.3, pauses=(0.15, 0.25))
    time.sleep(0.6)

    # 2. OK → 注入
    click(*OK_BTN, move_dur=0.35, pauses=(0.2, 0.2))
    time.sleep(2.6)  # 生成文件 + 通知气球

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
