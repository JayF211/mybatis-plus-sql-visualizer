# -*- coding: utf-8 -*-
"""T6 sql-to-java：工具窗口「SQL → Java」tab → 粘贴 SQL → 转换 →
形态下拉切 LambdaQueryWrapper → 代码重生成 → 复制收尾。

前置：SQL Visualizer 工具窗口已打开且停在「SQL → Java (Beta)」tab；
SQL 输入区与输出区均为空。
"""
import sys
import time

from gifkit import Recorder, click, paste, shot, wait_stable

REGION = (277, 25, 1370, 984)
OUT = "perf/t6_sql2java.mp4"

SQL_INPUT = (1317, 447)     # SQL 输入区（T-GifRerecord 重标）
RUN = (1162, 302)           # ▶ 转换（与捕捉共用工具栏）
FMT_DROPDOWN = (1342, 356)  # 形态下拉
FMT_LAMBDA = (1347, 472)    # LambdaQueryWrapper 选项
COPY = (1200, 517)          # 复制按钮

SQL = """SELECT id, username, age, email
FROM user
WHERE status = 1 AND age >= 18
ORDER BY id DESC"""


def ensure_sqljava_tab(max_tries=4):
    """像素验证 SQL → Java tab 已选中（蓝色高亮），未选中则点击重试。

    tab 可能单行/换行两行布局，固定坐标点击不保证命中；命中与否用
    tab 区域的蓝色高亮判定（region (985,151) 小条带）。
    """
    import numpy as np
    for i in range(max_tries):
        # 判定依据：SQL → Java 页的「形态下拉」蓝色边框（region (985,320)-(1225,355)，
        # 活动时约 1100+ 蓝像素，非活动约 60——比 tab 文字颜色可靠）
        path = "perf/_tab_check.png"
        shot(REGION, path)               # 全分辨率区域帧（grab 的 4x 降采样会稀释细蓝边）
        from PIL import Image as _Image
        img = np.asarray(_Image.open(path).convert("RGB"), dtype=int)
        reg = img[320:355, 985:1225]
        r, g, b = reg[:, :, 0], reg[:, :, 1], reg[:, :, 2]
        n = int(((b > 120) & (b - r > 40)).sum())
        if n > 300:
            if i:
                print("sqljava tab confirmed on retry", i)
            return True
        click(1257, 181, move_dur=0.25)   # 两行布局下的 SQL → Java tab
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
