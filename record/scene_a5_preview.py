# -*- coding: utf-8 -*-
"""A5 单击预览与双击弹窗（sql-click-preview.gif）20261011 v2：
前置：SQL 日志列表有已捕获条目（本会话 3 条）、捕获已开、无浮层/弹窗残留。
流程：单击 #1 → 行下方轻量浮层（可编辑）→ 点「复制」（闪对号）→ 点「关闭」
      → 双击 #1 → 完整预览弹窗 → 切「关键字换行」（SQL 由逐列竖排改为按关键字分行）
      → 点「取消」回到起始列表态。

标定（20261011 屏幕坐标，IDE 窗口 @ 277,25；region→screen 加 (277,25)）：
  #1 条目头行     region (1000,225) → screen (1277,250)
  浮层「复制」     region (1088,369) → screen (1365,394)
  浮层「关闭」     region (1191,369) → screen (1468,394)
  弹窗标题栏(取焦) region (170,164)  → screen (447,189)
  弹窗「关键字换行」region (818,227)  → screen (1095,252)
  弹窗「取消」     region (1208,811) → screen (1485,836)

坑：① 列表在主窗口内，单击即可弹浮层——别连点两次，间隔 0.35s 会被系统并成双击而直接开弹窗；
    ② 浮层弹出自带焦点，其按钮单击即生效；
    ③ 弹窗首个子窗口点击会被「聚焦激活」吞掉，故先点标题栏取焦再点目标按钮；
    ④ 关闭一律走按钮，不用 Esc——Esc 会把 SQL Visualizer 工具窗整个隐藏掉（实测踩过）。
"""
import sys
import time

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a5_preview.mp4"

ENTRY1 = (1277, 250)
POP_COPY = (1365, 394)
POP_CLOSE = (1468, 394)
DLG_TITLE = (447, 189)
DLG_KWWRAP = (1095, 252)
DLG_CANCEL = (1485, 836)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.6)

    # 1. 单击条目 → 轻量浮层
    pyautogui.click(*ENTRY1)
    time.sleep(2.4)

    # 2. 点浮层头部「复制」→ 图标闪一下对号
    pyautogui.click(*POP_COPY)
    time.sleep(1.5)

    # 3. 关闭浮层
    pyautogui.click(*POP_CLOSE)
    time.sleep(1.4)

    # 4. 双击条目 → 完整预览弹窗（含参数回填）
    pyautogui.doubleClick(*ENTRY1)
    time.sleep(2.6)

    # 5. 先点标题栏取焦（弹窗首个点击会被聚焦吞掉），再切「关键字换行」
    pyautogui.click(*DLG_TITLE)
    time.sleep(0.4)
    pyautogui.click(*DLG_KWWRAP)
    time.sleep(2.0)

    # 6. 「取消」关闭 → 回到起始列表态（循环接缝）
    pyautogui.click(*DLG_CANCEL)
    time.sleep(1.6)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
