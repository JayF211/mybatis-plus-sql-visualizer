# -*- coding: utf-8 -*-
"""A26 JPA Repository CRUD 写路径（jpa-derived-query.gif）20261012。

素材：`UserWriteServiceImpl`（jpa-hibernate-demo）一个文件里就能把写路径讲全——
      `save` / `deleteById` / `updateStatusByJpql` 三个调用点各自解析出 INSERT / DELETE / UPDATE。
流程：光标压在调用点的方法名 token 上 → Alt+Shift+Q 开预览
      第一幕 `save` → `INSERT INTO user (列…) VALUES (?, …)`，左侧还有「参数列」勾选面板，
      取消勾选 `user.age` 后 INSERT 的列清单实时少一列；
      第二幕 `deleteById` → `DELETE FROM user WHERE id = ?`，并带一行灰注释点明
      「逐实体 EntityManager.remove 语义（非 bulk DELETE）」。

标定（20261012，IDE @ 277,25）：
  编辑器取焦落点      screen (600,500)
  预览浮窗体内（关窗用）screen (760,400)
  「参数列」user.age 复选框 screen (429,713)
  SQL 文本区凝视落点  screen (820,340) / (820,410)

坑：① 跳行对话框残留输入 → goto() 内先 Ctrl+A 清空；
    ② IntelliJ 的 Home 是智能 Home → goto_col() 内按两下到绝对行首；
    ③ 光标必须压在 token 上（落行首缩进 anchor=null 只弹通知）；
    ④ 该浮窗尺寸随内容变（带「参数列」面板的更宽）→ 关窗用「点进窗体 + Esc」。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import close_preview, force_en_us, goto_col, shot, trigger_preview

REGION = (277, 25, 1370, 984)
OUT = "perf/a26_write_path.mp4"

FILE = "UserWriteServiceImpl.java"
RESULT_ROW = (777, 342)
EDITOR_SPOT = (600, 500)
DLG_BODY = (760, 400)
CHK_AGE = (429, 713)
GAZE_A = (820, 340)
GAZE_B = (820, 410)

LINE_SAVE, COL_SAVE = 56, 35          # userWriteRepository.save(user)
LINE_DEL, COL_DEL = 102, 28           # userWriteRepository.deleteById(id)


def main():
    import pyautogui
    import gifkit

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：开文件 + 光标压到 save 调用点 + 开好第一张预览 ----
    close_preview(body_xy=DLG_BODY)
    pyautogui.click(*EDITOR_SPOT)
    time.sleep(0.5)
    pyautogui.hotkey("ctrl", "shift", "n")
    time.sleep(1.4)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.3)
    force_en_us()
    pyautogui.typewrite(FILE, interval=0.05)
    time.sleep(1.8)
    pyautogui.click(*RESULT_ROW)
    time.sleep(0.4)
    pyautogui.click(*RESULT_ROW)
    time.sleep(2.5)
    goto_col(LINE_SAVE, COL_SAVE)
    trigger_preview()

    r = Recorder(REGION, OUT)
    r.start()

    # 1. 起手凝视 INSERT：沿 SQL 行缓慢下移（assemble 会裁静止段，靠移动撑时长）
    pyautogui.moveTo(660, 300, duration=0.5)
    pyautogui.moveTo(*GAZE_A, duration=1.0)
    pyautogui.moveTo(*GAZE_B, duration=1.0)

    # 2. 参数列勾选：取消 user.age → INSERT 的列清单实时少一列
    pyautogui.click(*CHK_AGE)
    time.sleep(1.6)

    # 3. 切到 deleteById：关窗 → 跳行 → 再预览（DELETE + 逐实体 em.remove 语义注释）
    close_preview(body_xy=DLG_BODY, wait=1.1)
    goto_col(LINE_DEL, COL_DEL, wait=0.9)
    trigger_preview(wait=2.6)

    # 4. 收尾凝视：DELETE / WHERE 与灰注释同屏
    pyautogui.moveTo(*GAZE_A, duration=1.0)
    pyautogui.moveTo(760, 430, duration=1.0)
    time.sleep(0.6)

    shot("perf/a26_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
