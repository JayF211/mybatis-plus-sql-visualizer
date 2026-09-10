# -*- coding: utf-8 -*-
"""T4 wrapper-preview：LambdaQueryWrapper 链 → gutter 点击 → SQL 弹窗 →
取消勾选布尔条件 → SQL 实时重渲染 → 关闭。导出宽 1440（首页共用）。

前置状态（录制前人工就位，一次标定）：
- 沙盒 IDEA 窗口铺满 region，UserServiceImpl.java 打开并滚动到
  listByLambdaQuery（line 35-41），gutter 图标在 GUTTER 坐标
- 无弹窗、无选中
"""
import sys
import time

from gifkit import Recorder, click, keys, wait_stable

REGION = (277, 25, 1370, 984)
OUT = "perf/t4_wrapper.mp4"

GUTTER = (774, 393)      # line 36 插件 gutter 图标（T-GifHeader 两行头部后重标）
SQL_AREA = (1050, 500)   # SQL 面板中部（点击聚焦）
CANCEL = (1480, 822)     # 弹窗「取消」按钮
CHK_STATUS = (424, 495)  # 动态条件 status=? 复选框


def main():
    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.6)

    click(*GUTTER)                       # 1. 点 gutter 图标
    w = wait_stable(REGION)
    print("popup stable wait:", w)
    time.sleep(1.2)                      # 弹窗定格

    click(*SQL_AREA, move_dur=0.28)      # 2. 聚焦 SQL 面板
    keys("ctrl", "end")                  # 3. 滚到 WHERE 段
    time.sleep(0.9)

    click(*CHK_STATUS, move_dur=0.3)     # 4. 取消勾选 status
    time.sleep(1.8)                      # 重渲染定格

    click(*CANCEL, move_dur=0.3)         # 5. 关闭
    time.sleep(0.9)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
