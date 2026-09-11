# -*- coding: utf-8 -*-
"""T5 sql-log（修订九构图 120%+13pt+树关）v2：应用已在 Services 中运行 →
点「捕捉」开始监听已存在的控制台 → 轮询 /user/list 触发新查询（Preparing/
Parameters 进控制台被捕获）→ 列表填充 → 点开第一条看还原 SQL。

旧版「先捕捉后启动」流程实证无效：捕捉时无控制台，监听未建立即失效（列表恒空）。
捕捉按钮 (1184,206)、列表首条 (1377,295) 为修订九重标。
"""
import sys
import time
import urllib.request

from gifkit import Recorder, click

REGION = (277, 25, 1370, 984)
OUT = "perf/t5_sqllog.mp4"

CAPTURE = (1184, 206)   # 工具窗 ▶ 捕捉（修订九重标）
ENTRY = (1377, 295)     # SQL 列表第一条（修订九重标）


def query_once():
    try:
        with urllib.request.urlopen("http://localhost:8081/user/list", timeout=5) as r:
            return r.status == 200
    except Exception:
        return False


def main():
    assert query_once(), "app not running on 8081 - start MybatisPlusDemoApplication first"

    r = Recorder(REGION, OUT)
    time.sleep(1.0)
    r.start()
    time.sleep(0.5)

    click(*CAPTURE, move_dur=0.3)        # 1. 开始捕捉（控制台已存在）
    time.sleep(0.8)

    for _ in range(4):                   # 2. 触发查询，控制台刷 Preparing/Parameters
        query_once()
        time.sleep(1.4)
    time.sleep(1.2)                      # 3. 列表填充定格

    click(*ENTRY, move_dur=0.35)         # 4. 点开第一条
    time.sleep(1.8)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
