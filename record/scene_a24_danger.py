# -*- coding: utf-8 -*-
"""A24 安全警示（danger-warning.gif）20261011：
前置：编辑器停在 UserServiceImpl.java 的 LambdaUpdateWrapper 链上（`updateByLambdaUpdateWrapper`，链 103~110 行）。
流程：Alt+Shift+Q 开预览（原始 SQL 带 `WHERE version = ?`，乐观锁自动条件）→ 选中全部替换成
      无 WHERE 的 `UPDATE user SET status = 1` → 点「执行 SQL」→ 弹「确认执行」无界写警示
      （逐类列出「无 WHERE 条件——语句将作用于全表」）→ 点 No 取消 → 点「刷新」还原生成 SQL
      （闭合循环接缝）。

标定（20261011 屏幕坐标，IDE @ 277,25；全部从裁剪放大图量）：
  wrapper 链行（第 108 行） region (400,233)  → screen (677,258)
  弹窗 SQL 文本区           region (520,292)  → screen (797,317)
  「执行 SQL」              region (993,803)  → screen (1270,828)
  警示框「No」              region (846,578)  → screen (1123,603)
  「刷新」                 region (1075,227) → screen (1352,252)
  弹窗「取消」              region (1208,803) → screen (1485,828)

坑：① 全不选动态条件**不足以**得到无 WHERE 形态——MP 乐观锁会补 `WHERE version = ?`，
    要真去掉 WHERE 得编辑 SQL（弹窗编辑器可用「点两下取焦 → Ctrl+A → 剪贴板粘贴」替换）；
  ② 该弹窗文本区在 x>620 处是空白，裁图核对要裁左侧（我裁错过一次，白读一张）。
"""
import sys
import time

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a24_danger.mp4"

CHAIN_LINE = (677, 258)
SQL_AREA = (797, 317)
EXEC_SQL = (1270, 828)
CONFIRM_NO = (1123, 603)
REFRESH = (1352, 252)
DLG_CANCEL = (1485, 828)

DANGEROUS_SQL = "UPDATE user SET status = 1"


def main():
    import pyautogui
    import gifkit

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    # ---- prep（录制段外）：复位成「预览弹窗刚打开、SQL 为生成态」----
    pyautogui.click(*CONFIRM_NO)
    time.sleep(1.0)
    pyautogui.click(*DLG_CANCEL)
    time.sleep(1.2)
    pyautogui.click(*CHAIN_LINE)
    time.sleep(0.6)
    pyautogui.hotkey("alt", "shift", "q")
    time.sleep(2.6)

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.7)

    # 1. 编辑 SQL：替换成无 WHERE 的全表更新
    pyautogui.click(*SQL_AREA)
    time.sleep(0.35)
    pyautogui.click(*SQL_AREA)
    time.sleep(0.6)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.6)
    gifkit.paste(DANGEROUS_SQL)
    time.sleep(1.7)

    # 2. 执行 SQL → 无界写警示
    pyautogui.click(*EXEC_SQL)
    time.sleep(2.3)

    # 3. No → 不执行，回到预览
    pyautogui.click(*CONFIRM_NO)
    time.sleep(1.9)

    # 4. 刷新 → 还原生成 SQL（循环接缝闭合）
    pyautogui.click(*REFRESH)
    time.sleep(2.1)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
