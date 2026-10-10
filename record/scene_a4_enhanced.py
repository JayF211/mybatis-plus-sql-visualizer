# -*- coding: utf-8 -*-
"""A4 增强捕获通道能力（enhanced-capture.gif）20261011：
列表已捕获态（■ 红=捕捉中）→ 清空列表 → 依次触发带参查询 + 主键冲突报错 →
条目流式入列（真实参数/毫秒耗时/[MySQL] 角标/调用点/⛔ 报错）→ 点开报错条目看预览。
前置：demo 应用 8081 在跑且 agent 会话在线、工具窗加宽、列表排序=时间降序。
"""
import sys
import time
import urllib.request
import json

from gifkit import Recorder, click, wait_stable

REGION = (277, 25, 1370, 984)
OUT = "perf/a4_enhanced.mp4"

TRASH = (1037, 207)      # 清空列表
ERR_ENTRY = (1000, 245)  # 清空后最新条目在顶部（时间降序）=INSERT 报错条目


def http_json(path, method="GET", body=None):
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(
        "http://localhost:8081" + path, data=data, method=method,
        headers={"Content-Type": "application/json"} if body else {})
    with urllib.request.urlopen(req, timeout=5) as r:
        return r.status


def main():
    assert http_json("/user/list") == 200, "app not running on 8081"

    # prep：清空列表（录制段外完成，成片从空态开始）
    click(*TRASH, move_dur=0.25)
    time.sleep(1.2)

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.5)

    # 1. 带参嵌套查询 → 条目#1（真实参数 + 耗时 + 角标 + 调用点）
    http_json("/user/nested?username=ab&minAge=20&maxAge=30")
    wait_stable(REGION, max_wait=3.0, need=5)
    time.sleep(0.6)

    # 2. 主键冲突 INSERT → 条目#2（⛔ 执行报错 + VALUES 实参）
    try:
        http_json("/user", method="POST",
                  body={"id": 1, "username": "dup", "password": "x", "age": 20,
                        "email": "d@x.com", "roleId": 1, "deptId": 1, "status": 1})
    except Exception:
        pass  # 500 预期
    wait_stable(REGION, max_wait=3.0, need=5)
    time.sleep(0.8)

    # 3. 点开报错条目 → 内嵌预览（错误警示 + SQL 全文）
    click(*ERR_ENTRY, move_dur=0.35)
    time.sleep(2.2)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
