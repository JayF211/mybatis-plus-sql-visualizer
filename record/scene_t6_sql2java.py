# -*- coding: utf-8 -*-
"""T6b sql-to-java（修订十一重录）：工具窗口「SQL → Java」tab → 粘贴 SQL → 转换 →
形态下拉切 LambdaQueryWrapper → 代码重生成定格 → 复制收尾。

修订十一重录二（2026-09-13，用户要求构图：工具窗拓宽至 ~650px、底部 Services 关闭）：
坐标均为屏幕物理像素，region=(277,25,1370,984)，全部沙盒内逐点实测。

前置：SQL Visualizer 工具窗已开；输入/输出区为空（prep 段负责清空）。
"""
import sys
import time

from gifkit import Recorder, click, paste, shot, wait_stable

REGION = (277, 25, 1370, 984)
OUT = "perf/t6_sql2java.mp4"

TAB = (1272, 153)           # SQL → Java (Beta) tab
SQL_INPUT = (1307, 415)     # SQL 输入区中心（宽侧栏下输入区 y 285-520）
RUN = (1038, 207)           # ▶ 转换（工具栏左一）
FMT_DROPDOWN = (1287, 250)  # 形态下拉
FMT_LAMBDA = (1297, 347)    # 下拉项 LambdaQueryWrapper（实体不可用时回落字符串列名）
COPY = (1038, 582)          # 输出区图标行左一（复制生成代码，不含注释）

SQL = """SELECT id, username, age, email
FROM user
WHERE status = 1 AND age >= 18
ORDER BY id DESC"""


def ensure_sqljava_tab(max_tries=4):
    """像素验证 SQL → Java tab 已选中：活动标签暗蓝药丸（region y108-150, x915-1075）
    实测活动 4901 / 非活动 206（规则 b>60 且 b-r>30），阈值取 2000。"""
    import numpy as np
    from PIL import Image as _Image
    for i in range(max_tries):
        path = "perf/_tab_check.png"
        shot(REGION, path)
        img = np.asarray(_Image.open(path).convert("RGB"), dtype=int)
        reg = img[108:150, 915:1075]
        r, g, b = reg[:, :, 0], reg[:, :, 1], reg[:, :, 2]
        n = int(((b > 60) & (b - r > 30)).sum())
        if n > 2000:
            if i:
                print("sqljava tab confirmed on retry", i)
            return True
        click(*TAB, move_dur=0.25)
        time.sleep(1.2)
    raise RuntimeError("SQL -> Java tab not active")


def main():
    ensure_sqljava_tab()
    time.sleep(0.5)
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
