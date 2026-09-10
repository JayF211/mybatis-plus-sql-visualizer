# -*- coding: utf-8 -*-
"""T5 sql-log：点「捕捉」开始监听 → 重启 MybatisPlusDemo 应用 → 控制台刷
Preparing/Parameters → SQL 列表自动捕获 → 点开一条看还原 SQL。

前置：SQL Visualizer 工具窗口停在「SQL 日志」tab；Services 面板可见且
MybatisPlusDemoApplication 已选中；应用当前已停止。
"""
import subprocess
import sys
import time
import urllib.request

from gifkit import Recorder, click, wait_stable

REGION = (277, 25, 1370, 984)
OUT = "perf/t5_sqllog.mp4"

CAPTURE = (1184, 240)   # 工具窗口 ▶ 捕捉
SVC_STOP = (823, 732)   # Services ■ 停止
SVC_RUN = (744, 732)    # Services ▶ 运行
ENTRY = (1370, 360)     # SQL 列表第一条


def app_up():
    try:
        with urllib.request.urlopen("http://localhost:18082/user/list", timeout=3) as r:
            return r.status == 200
    except Exception:
        return False


def main():
    r = Recorder(REGION, OUT)
    time.sleep(1.0)
    r.start()
    time.sleep(0.5)

    click(*CAPTURE, move_dur=0.3)        # 1. 开始捕捉
    time.sleep(0.9)

    click(*SVC_STOP, move_dur=0.25)      # 2. 停掉旧实例（若在跑）
    time.sleep(1.2)
    click(*SVC_RUN, move_dur=0.25)       # 3. 启动应用（控制台流式输出）
    t0 = time.time()
    while time.time() - t0 < 60 and not app_up():
        time.sleep(1.5)
    print("boot+query wait:", round(time.time() - t0, 1))
    time.sleep(1.5)                      # 4. 列表填充定格

    click(*ENTRY, move_dur=0.35)         # 5. 点开第一条
    time.sleep(1.8)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
