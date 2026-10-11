# -*- coding: utf-8 -*-
"""A11 EXPLAIN 真实执行计划（explain-plan.gif）20261012。

素材：`UserServiceImpl#selectJoinList`（mybatis-plus-join-demo，MPJ 三表联表链）。
流程：光标压链 token → 预览 → 点「执行计划」→ 选 `mpj_demo@localhost`（弹层第二行）
      → 等 EXPLAIN 提交原生数据库控制台执行 → 结构化计划与静态性能规则同屏 → 凝视。

标定（20261012，IDE @ 277,25，整窗截图坐标 + (277,25)）：
  编辑器取焦 (600,500)｜预览浮窗体内（关窗）(760,400)
  「执行计划」按钮 screen (1300,253)
  数据源弹层第二行 mpj_demo@localhost screen (860,251)
  状态行「执行中…」screen (300,760)

坑：
  ①此前「执行中…」8 分钟挂死的根因＝沙盒缺 IDE 密钥链（c.kdbx/c.pwd），已从真机拷入
    并经用户手工激活授权——本脚本假定密钥链在位、许可已过，若再挂死先查这两点。
  ②EXPLAIN 属免确认类操作（无 DML 二次确认），但首次对某数据源执行会做一次连通，
    耗时可能 5~15s，凝视节奏放慢。
  ③数据源弹层行位以实测为准（本脚本写死 mpj_demo 行 y≈251），弹层高度随源个数变化。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import close_preview, force_en_us, goto_text, shot, trigger_preview

REGION = (277, 25, 1370, 984)
OUT = "perf/a11_explain.mp4"

TOKEN = '.like(AddressDO::getTel, "1")'      # selectJoinList 链内唯一片段

EDITOR_SPOT = (600, 500)
DLG_BODY = (760, 400)
BTN_EXPLAIN = (1300, 253)
DS_MPJ_ROW = (860, 251)
GAZE_A = (1100, 500)
GAZE_B = (1100, 620)


def main():
    import pyautogui
    import gifkit

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：关遗留弹窗 → 开文件 → 光标压 token → 开好预览 ----
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
    # trigger 后把光标移离代码区：压在链 token 上会弹 documentation 悬浮窗挡预览画面
    #（take1 报废的直接原因）；SQL 面板中部安全。
    pyautogui.moveTo(1100, 480, duration=0.4)
    time.sleep(0.6)

    r = Recorder(REGION, OUT)
    r.start()

    # 1. 起手凝视：SQL 与静态性能规则警示同屏
    pyautogui.moveTo(*GAZE_A, duration=0.6)
    pyautogui.moveTo(*GAZE_B, duration=1.0)

    # 2. 点「执行计划」→ 弹数据源选择
    pyautogui.click(*BTN_EXPLAIN); time.sleep(2.2)

    # 3. 选 mpj_demo@localhost → 提交 EXPLAIN（首连可能 5~15s）
    pyautogui.click(*DS_MPJ_ROW)
    time.sleep(2.5)          # 先给「执行中…」一拍入镜
    time.sleep(8.0)          # 等结果渲染

    # 4. 凝视计划结果：结构化 EXPLAIN 与静态规则同屏对照
    pyautogui.moveTo(*GAZE_A, duration=1.0)
    pyautogui.moveTo(700, 560, duration=1.0)
    time.sleep(0.8)

    shot("perf/a11_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
