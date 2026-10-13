# -*- coding: utf-8 -*-
"""A10 JOIN 关系图（join-diagram.gif）20261012。

素材：`UserServiceImpl#selectJoinList`（mybatis-plus-join-demo，L41 起的 MPJ 链）——
      selectAll + selectAs + LEFT JOIN user_address + RIGHT JOIN area + eq + like。
      **必须用带动态条件的链**（eq/like），无条件的链（selectFill）previewModel=null
      会拿不到「JOIN 图」Tab（实测边界，已登记待查）。
流程：光标压在链内 token → 预览（SQL Tab，三表 JOIN 语句 + 动态条件面板）
      → 点「JOIN 图」Tab → 原生 yFiles Diagrams 渲染三表关系图（带列/类型/DB COMMENT）
      → 凝视图区 → 回 SQL Tab 收尾。

标定（20261012，IDE @ 277,25，**整窗截图坐标 + (277,25) 即屏幕坐标**）：
  预览浮窗体内（关窗）  screen (760,400)
  「JOIN 图」Tab        screen (481,328)   ← 由 1:1 strip 实测；**窗口一动即失效**
  Tab 条参考：SQL Tab (412,303) 附近、JOIN 图 (481,328) 附近
  图区凝视落点          screen (1000,500) / (700,600)

坑（本轮全部踩过）：
  ①**坐标换算两次踩坑**：从裁剪图量坐标**别忘了加窗口起点 (277,25)**；且裁剪再缩放的图，
     换算 = crop 原点 + 显示坐标/缩放倍数。为此优先用 **1:1 全宽 crop**（crop=1370:高:y偏移）量。
  ②无动态条件的 JOIN 链不出「JOIN 图」Tab（previewModel=null 跳过 updateDiagramTab）——
     拍摄素材必须带 eq/like 等动态条件。
  ③原生 Diagrams 渲染有加载期（schema 富化后台跑），点 Tab 后等 4~5s 再动。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import close_preview, force_en_us, goto_text, shot, trigger_preview

REGION = (277, 25, 1370, 984)
OUT = "perf/a10_join_diagram.mp4"

TOKEN = '.like(AddressDO::getTel, "1")'      # selectJoinList 链内唯一片段

EDITOR_SPOT = (600, 500)
DLG_BODY = (760, 400)
TAB_JOIN = (481, 328)
TAB_SQL = (412, 303)
GAZE_A = (1000, 500)
GAZE_B = (700, 600)


def main():
    import pyautogui
    import gifkit

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：确保 mpjdemo/UserServiceImpl 已开 + 光标压链 token + 开好预览 ----
    close_preview(body_xy=DLG_BODY)
    pyautogui.click(*EDITOR_SPOT); time.sleep(0.4)
    pyautogui.hotkey("ctrl", "shift", "n"); time.sleep(1.4)
    pyautogui.hotkey("ctrl", "a"); time.sleep(0.3)
    force_en_us()
    pyautogui.typewrite("mpjdemo/UserServiceImpl.java", interval=0.04)
    time.sleep(1.8)
    pyautogui.click(777, 342); time.sleep(0.4)
    pyautogui.click(777, 342); time.sleep(2.5)
    goto_text(TOKEN)
    trigger_preview()

    r = Recorder(REGION, OUT)
    r.start()

    # 1. 起手凝视：SQL Tab——三表 JOIN 语句（LEFT JOIN user_address + RIGHT JOIN area）
    pyautogui.moveTo(1100, 500, duration=0.6)
    pyautogui.moveTo(1100, 620, duration=1.0)

    # 2. 切「JOIN 图」Tab → 等原生渲染
    pyautogui.click(*TAB_JOIN); time.sleep(4.5)

    # 3. 凝视图区：三表节点与关系连线
    pyautogui.moveTo(*GAZE_A, duration=1.0)
    pyautogui.moveTo(*GAZE_B, duration=1.0)
    pyautogui.moveTo(850, 450, duration=1.0)

    # 4. 回 SQL Tab 收尾（图 ↔ SQL 同窗对照）
    pyautogui.click(*TAB_SQL); time.sleep(1.2)
    pyautogui.moveTo(1100, 560, duration=0.9)
    time.sleep(0.8)

    shot("perf/a10_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
