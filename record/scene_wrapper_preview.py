# -*- coding: utf-8 -*-
"""批次 5：重录 wrapper-preview.gif（features/wrapper-preview hero + getting-started 共 4 处引用）20261012。

旧片（2026-09-12 / 1440×1034 / 7.67s）两处过时，本片按当前面板重出：
  ① 预览面板**没有「预设」行**（现面板有「预设」/「默认预设 / 管理预设」）；
  ② 标题栏是 `Start Free Trial` 试用态（沙盒现已激活，标题栏为工程名）。
素材：`WrapperAdvancedCoverageServiceImpl#baseActiveUserWrapper`——
      `Wrappers.<User>lambdaQuery().eq(User::getStatus, 1).isNotNull(User::getEmail)`，
      与文档「快速开始」代码块同形（eq + isNotNull），且 User 是 `@TableName("user")` 普通写法
      → 表名走 MySQL 反引号（`` `user` ``），SQL 可执行。

流程：光标压链 token（编辑器静止一拍）→ Alt+Shift+Q 唤起预览 → SQL 逐列展开 → 扫过左侧
      「预设 / 动态条件」区（展示新增的预设行）→ 收尾。

标定（20261012，IDE @ 277,25）：
  链 token 在第 44 行第 18 列（`.eq(User::getStatus, 1)`）
  预览 SQL 区 screen (1100,500) / (1100,620)
  左侧「预设」行 screen (450,322)｜「动态条件」行 screen (450,411)
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import force_en_us, goto_col, shot

REGION = (277, 25, 1370, 984)
OUT = "perf/wrapper_preview.mp4"

FILE_QUERY = "mybatisplusdemo/WrapperAdvancedCoverageServiceImpl.java"
CHAIN_LINE = 44          # .eq(User::getStatus, 1)
CHAIN_COL = 18

CANCEL_XY = (1485, 828)
ROW_X = 777
ROW_YS = (350, 358, 366)

GAZE_CHAIN = (600, 344)     # 编辑器里的链
GAZE_SQL_A = (1100, 500)    # 预览 SQL 区
GAZE_SQL_B = (1100, 620)
GAZE_PRESET = (450, 365)    # 展开后的「默认预设 / 管理预设」行
GAZE_COND = (450, 411)      # 左侧「动态条件」行
PRESET_TOGGLE = (407, 322)  # 左侧「预设」行折叠箭头（▸ → ▾）


def preset_expanded() -> bool:
    """判定预览面板「预设」行是否处于展开态。

    展开态会**跨预览保留**（探针里展开过，正式 take 打开时就已经是展开的，那一下点击
    反而把它收起 → 末帧仍是收起态）。所以 take 前必须归一到「收起」。
    判据：展开时该行有下拉框与「管理预设...」按钮（边框+文字），灰度标准差明显更高。
    实测（Islands Dark 主题）：收起 13.09 / 展开 34.95 —— 阈值取 22。**别照搬「空白≈0」的直觉**：
    暗色主题下面板底色灰度均值只有 28，收起态也有 13 的本底噪声（阈值 8 会误判成展开，
    归一化循环会连点三次反而把状态弄反）。
    """
    import numpy as np
    import PIL.Image
    tmp = "perf/_preset_state.png"
    shot(tmp)
    img = np.asarray(PIL.Image.open(tmp).convert("L"), dtype=float)
    band = img[340:362, 185:295]      # 展开后「默认预设 / 管理预设」所占条带（窗口坐标）
    return bool(band.std() > 22.0)


def main():
    import pyautogui

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：关预览/弹窗 → 开文件 → 光标压链 token（**不唤起预览**，留给入镜） ----
    pyautogui.click(*CANCEL_XY); time.sleep(1.0)
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.6)
    pyautogui.click(600, 500); time.sleep(0.4)
    pyautogui.hotkey("ctrl", "shift", "n"); time.sleep(1.4)
    pyautogui.hotkey("ctrl", "a"); time.sleep(0.5)
    force_en_us()
    pyautogui.typewrite(FILE_QUERY, interval=0.05); time.sleep(1.6)
    for y in ROW_YS:
        pyautogui.click(ROW_X, y); time.sleep(0.4)
    time.sleep(2.4)
    goto_col(CHAIN_LINE, CHAIN_COL)

    # 预设行归一：先唤起一次预览看状态，展开就点箭头收起，再关掉预览让入镜从收起态开始
    pyautogui.hotkey("alt", "shift", "q"); time.sleep(2.8)
    for _ in range(3):
        if not preset_expanded():
            break
        pyautogui.click(*PRESET_TOGGLE); time.sleep(1.2)
    print("预设行展开态 =", preset_expanded())
    pyautogui.click(*CANCEL_XY); time.sleep(1.2)
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.6)
    pyautogui.moveTo(*GAZE_CHAIN, duration=0.4); time.sleep(0.6)

    r = Recorder(REGION, OUT)
    r.start()

    # 1. 编辑器里链上静止一拍（让观众看清「光标压在链上」）
    pyautogui.moveTo(*GAZE_CHAIN, duration=0.6)
    time.sleep(1.0)

    # 2. Alt+Shift+Q 唤起预览
    pyautogui.hotkey("alt", "shift", "q")
    time.sleep(2.8)

    # 3. 看 SQL
    pyautogui.moveTo(*GAZE_SQL_A, duration=0.8)
    pyautogui.moveTo(*GAZE_SQL_B, duration=1.0)
    time.sleep(0.4)

    # 4. 展开左侧「预设」行（本片相对旧片的主要新增；也是给片子真实 UI 变化——
    #    裸屏捕获下鼠标移动不产生像素变化，会被静段裁剪吃掉，片子会短到 3.6s）
    pyautogui.click(*PRESET_TOGGLE)
    time.sleep(1.6)
    pyautogui.moveTo(*GAZE_PRESET, duration=0.8)
    time.sleep(1.0)
    pyautogui.moveTo(*GAZE_COND, duration=0.8)
    time.sleep(0.9)

    shot("perf/wrapper_preview_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
