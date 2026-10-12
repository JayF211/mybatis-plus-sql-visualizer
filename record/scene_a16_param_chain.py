# -*- coding: utf-8 -*-
"""A16 JPA 参数链 setParameter（jpa-param-chain.gif）20261011：
前置：demo 的 `JpaParamOrderReproController#queryByChain` 有 6 次 setParameter（5 个唯一参数名，
      locationStatus 写重两次；zoneCode/status/fullDegree 是字面量，positionType 是枚举常量）。
流程：光标落在链上 → Alt+Shift+Q 开预览（参数框已被字面量/枚举常量自动预填、底部带同名写重灰字提示）
      → 往 #warehouseId（形参，非字面量）填值 → SQL 里 `warehouse_id = ?` 变成实值
      → 再把 #zoneCode 从 KQ13 改成 KQ99 → SQL 里 `zone.code = 'KQ99'` 跟着变。

标定（20261011 屏幕坐标，IDE @ 277,25）：
  搜索弹窗首个结果行  screen (777,342)
  编辑器内取焦落点    screen (527,325)
  #warehouseId 输入框 screen (610,424)
  #zoneCode 输入框    screen (610,542)
  预览弹窗「取消」    screen (1485,828)

坑：① 该弹窗的输入区接受剪贴板粘贴（gifkit.paste），比逐键键入稳；
    ② 参数框改动会即时重渲染 SQL，别在同一个框里连点两次——第二击会落成双击选词（无害但难看）。
"""
import sys
import time

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a16_param_chain.mp4"

FILE = "JpaParamOrderReproController.java"
LINE = "79"
RESULT_ROW = (777, 342)
EDITOR_SPOT = (527, 325)
PARAM_WAREHOUSE = (610, 424)
PARAM_ZONECODE = (610, 542)
DLG_CANCEL = (1485, 828)


def main():
    import pyautogui
    import gifkit

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    # ---- prep：确保预览弹窗已开在该文件该行上 ----
    pyautogui.click(*DLG_CANCEL)
    time.sleep(1.0)
    pyautogui.hotkey("ctrl", "shift", "n")
    time.sleep(1.4)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.4)
    pyautogui.typewrite(FILE, interval=0.05)
    time.sleep(1.6)
    pyautogui.click(*RESULT_ROW)
    time.sleep(0.4)
    pyautogui.click(*RESULT_ROW)
    time.sleep(2.2)
    pyautogui.click(*EDITOR_SPOT)
    time.sleep(0.4)
    pyautogui.hotkey("ctrl", "g")
    time.sleep(0.8)
    pyautogui.typewrite(LINE, interval=0.08)
    time.sleep(0.6)
    pyautogui.keyDown("return")
    pyautogui.keyUp("return")
    time.sleep(1.4)
    pyautogui.hotkey("alt", "shift", "q")
    time.sleep(3.0)

    r = Recorder(REGION, OUT)
    r.start()
    # 起手「凝视」：assemble 会把静止段裁到上限，纯 sleep 的定格留不住——
    # 用缓慢移动把这段变成运动帧，让「字面量/枚举已预填 + 同名写重灰字提示」看清。
    pyautogui.moveTo(430, 700, duration=0.3)
    pyautogui.moveTo(540, 560, duration=1.1)
    pyautogui.moveTo(600, 430, duration=1.1)
    time.sleep(0.35)

    # 1. 形参按名回填：warehouse_id = ? → 实值
    pyautogui.click(*PARAM_WAREHOUSE)
    time.sleep(0.5)
    gifkit.paste("7")
    time.sleep(2.0)

    # 2. 改字面量参数：zone.code = 'KQ13' → 'KQ99'
    pyautogui.click(*PARAM_ZONECODE)
    time.sleep(0.5)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.3)
    gifkit.paste("KQ99")
    time.sleep(2.2)

    # 3. 定格：预填的字面量/枚举常量与同名写重灰字提示同屏
    time.sleep(1.4)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
