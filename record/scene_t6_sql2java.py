# -*- coding: utf-8 -*-
"""T6 sql-to-java：工具窗口「SQL → Java」tab → 粘贴 SQL → 转换 →
形态下拉切 LambdaQueryWrapper → 代码重生成 → 复制收尾。

前置：SQL Visualizer 工具窗口已打开且停在「SQL → Java (Beta)」tab；
SQL 输入区与输出区均为空。
"""
import sys
import time

from gifkit import Recorder, click, paste, wait_stable

REGION = (277, 25, 1370, 984)
OUT = "perf/t6_sql2java.mp4"

SQL_INPUT = (1200, 450)     # SQL 输入区
RUN = (1184, 240)           # ▶ 转换
FMT_DROPDOWN = (1366, 294)  # 形态下拉
FMT_LAMBDA = (1356, 409)    # LambdaQueryWrapper 选项
COPY = (1185, 596)          # 复制按钮

SQL = """SELECT id, username, age, email
FROM user
WHERE status = 1 AND age >= 18
ORDER BY id DESC"""


def main():
    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    click(*SQL_INPUT, move_dur=0.3)      # 1. 粘贴 SQL
    paste(SQL)
    time.sleep(0.5)
    click(*RUN, move_dur=0.3)            # 2. 转换
    w = wait_stable(REGION)
    print("convert stable wait:", w)
    time.sleep(1.0)

    click(*FMT_DROPDOWN, move_dur=0.3)   # 3. 形态下拉
    time.sleep(0.9)
    click(*FMT_LAMBDA, move_dur=0.3)     # 4. 切 LambdaQueryWrapper
    time.sleep(0.7)
    click(*RUN, move_dur=0.3)            #    再点转换重生成
    w = wait_stable(REGION)
    print("regen stable wait:", w)
    time.sleep(1.5)

    click(*COPY, move_dur=0.3)           # 5. 复制
    time.sleep(0.8)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
