# -*- coding: utf-8 -*-
"""A13 执行 SQL（临时 .sql + 结果）20261012。

素材：`DeviceInfoServiceImpl#joinListEntity`（`car_device_info LEFT JOIN station_info`，
mpj_demo 库，与 A11 同一目标——该链的 SQL 在 mpj_demo@localhost 上**实测可执行**；
先前用 `WrapperAdvancedCoverageServiceImpl`（mp_demo 模块）会报
`[42S22][1054] Unknown column 'password' in 'field list'`，属该模块表结构与库不符）。
流程：光标压链 → 预览 → 点「执行 SQL」→ 插件把语句写进项目内临时文件
`.idea/sql-visualizer/exec/mpsql-exec.sql` 提交原生数据库控制台执行 → 下方结果网格出数据。

标定（20261012，IDE @ 277,25）：
  链 token 第 110 行第 18 列（`.eq(DeviceInfoEntity::getShareSchemeId, 1)`）
  「执行 SQL」按钮 **x 固定 979（窗口坐标），y 由 find_button_row() 按像素剖面动态求**——
  弹出面板高度随 SQL 内容变，按钮行位置随之上下浮动，**目测写死必错**（本轮实测目测值偏低约 48px，
  连点三次都没命中）。

坑：① 设置对话框是模态的，开着时点预览按钮无效 → prep 先 Cancel 掉；
    ② 裸屏捕获下鼠标移动不产生像素变化，会被静段裁剪吃掉 → 靠「临时文件执行 + 结果网格」撑长度；
    ③ 首连数据源可能 5~15s。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import mss  # noqa: E402
import numpy as np  # noqa: E402
import PIL.Image  # noqa: E402

from gifkit import Recorder  # noqa: E402
from nav import force_en_us, goto_col, trigger_preview  # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/a13_execute_sql.mp4"

FILE_QUERY = "mpjdemo/DeviceInfoServiceImpl.java"
CHAIN_LINE = 110
CHAIN_COL = 18

SETTINGS_CANCEL = (1329, 942)
POP_CANCEL = (1489, 781)
BTN_EXEC_X = 979          # 窗口坐标；screen x = 277 + 979
ROW_X = 777
ROW_YS = (350, 358, 366)

GAZE_SQL = (1100, 500)


def shot(path, region=REGION):
    with mss.mss() as sct:
        img = sct.grab({'left': region[0], 'top': region[1], 'width': region[2], 'height': region[3]})
        mss.tools.to_png(img.rgb, img.size, output=path)


def popup_rect():
    """用 EnumWindows 取预览弹窗的屏幕矩形（它不是进程主窗口，MainWindowHandle 看不到）。

    返回 (left, top, right, bottom)；找不到返回 None。
    """
    import re
    import subprocess
    out = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                          "-File", "F:/tmp/find_dlg.ps1"],
                         capture_output=True, text=True).stdout
    best = None
    for line in out.splitlines():
        m = re.search(r"\((-?\d+),(-?\d+)\)-\((-?\d+),(-?\d+)\) (\d+)x(\d+)", line)
        if not m:
            continue
        l, t, r, b, w, h = (int(x) for x in m.groups())
        if 300 < w < 1350 and 300 < h < 980:      # 排除主窗口（1370x984）
            if best is None or w * h > best[4]:
                best = (l, t, r, b, w * h)
    return best[:4] if best else None


def find_button_row(debug_png="perf/_a13_btnrow.png"):
    """求「执行 SQL」按钮行的屏幕 y。

    实测定则：按钮行恒在预览弹窗底边上方约 39px（弹窗高度随 SQL 内容变，故不能用固定值）。
    先用弹窗矩形算；取不到时回退到像素剖面（右半区最亮连续段）。
    """
    rect = popup_rect()
    if rect:
        return rect[3] - 39
    shot(debug_png)
    a = np.asarray(PIL.Image.open(debug_png).convert("L"), dtype=float)
    prof = [(y, float(a[y, 900:1260].mean())) for y in range(600, 900)]
    bright = [y for y, v in prof if v > 35]
    if not bright:
        raise RuntimeError("未找到按钮行（预览面板可能未打开）")
    runs, start = [], None
    for y in range(600, 900):
        on = y in bright
        if on and start is None:
            start = y
        if not on and start is not None:
            runs.append((start, y - 1)); start = None
    if start is not None:
        runs.append((start, 899))
    y0, y1 = max(runs, key=lambda r: r[1] - r[0])
    return 25 + (y0 + y1) // 2


def main():
    import pyautogui

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）----
    pyautogui.click(*SETTINGS_CANCEL); time.sleep(1.5)
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.6)
    pyautogui.click(*POP_CANCEL); time.sleep(1.2)
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.6)
    pyautogui.click(600, 500); time.sleep(0.4)
    pyautogui.hotkey("ctrl", "shift", "n"); time.sleep(1.4)
    pyautogui.hotkey("ctrl", "a"); time.sleep(0.5)
    force_en_us()
    pyautogui.typewrite(FILE_QUERY, interval=0.05); time.sleep(1.6)
    # 开文件用坐标扫掠（probe_openfile.py 验证过的路径；Return 打不开结果行——JBR 吞键）。
    # ⚠ 本轮未解阻塞：弹窗关掉后扫掠的余点会落到编辑器代码上，触发插件给列名提供的
    # documentation 浮层，且它会**粘住不退**（Esc 可关，但 take 中再压到代码会再弹），
    # 末帧右侧被「Data Source/Schema/Table/Column + alter table …」整块挡掉。
    # 试过改用键盘路径（↓ + Return）——会把 `settings-1.1.0.xsd` 当首条结果打开，**开错文件**。
    # 下一步方向：把扫掠 y 标定到该文件的**空白行**（各文件排版不同，350/358/366 只对旧目标有效），
    # 或在设置里关掉「Show quick documentation on mouse move」后再录。
    for y in ROW_YS:
        pyautogui.click(ROW_X, y); time.sleep(0.4)
    time.sleep(2.4)
    goto_col(CHAIN_LINE, CHAIN_COL)
    trigger_preview()
    pyautogui.moveTo(*GAZE_SQL, duration=0.4)
    time.sleep(1.0)

    by = find_button_row()
    btn = (277 + BTN_EXEC_X, by)
    print("按钮行 screen y =", by, "→ 点击 screen", btn)

    r = Recorder(REGION, OUT)
    r.start()

    pyautogui.moveTo(*GAZE_SQL, duration=0.6)
    time.sleep(1.2)
    pyautogui.click(*btn)
    time.sleep(3.0)
    time.sleep(10.0)
    # 收尾停到预览左栏（不压在代码/网格上：压代码会弹 documentation 浮层挡画面，
    # 压结果网格会弹列类型浮层——两处都在本轮踩过）
    pyautogui.moveTo(577, 725, duration=0.8)
    time.sleep(1.2)

    shot("perf/a13_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
