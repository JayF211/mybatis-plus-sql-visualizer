# -*- coding: utf-8 -*-
"""A21 批量操作参数面板：列级勾选 + 冲突子句联动（batch-columns.gif）20261012。

素材：`WrapperExtServiceImpl#iserviceMore`（mybatis-plus-demo）里的
      `userService.saveOrUpdateBatch(Collections.singletonList(user))`（L165 列 53）
      → 预览走**批量参数面板**：`INSERT INTO \`user\` (id, password, age, email, …) VALUES (?, …)`
        + MySQL 冲突子句 `ON DUPLICATE KEY UPDATE password = VALUES(password), age = VALUES(age), …`
成片两幕：
  ① 起手凝视 SQL：列清单与冲突子句同屏（UPSERT 全貌）；
  ② 在左栏「参数列」取消勾选 `email` → INSERT 的列清单与 `ON DUPLICATE KEY UPDATE` 段
     同步少一列（列级勾选与冲突子句联动的直接证据）。

标定（20261012，IDE @ 277,25，弹窗 (367,166,1557,868)）：
  「参数列」email 复选框 screen (427,588)（region 150,563）
  SQL 文本区凝视      screen (1100,300) / (1100,420)
  左栏空白收尾        screen (577,725)

坑：见 locate.close_preview / ensure_foreground 的注释（无边框弹窗点「取消」必错、
    ensure_foreground 默认取焦落点会压到运行配置下拉）。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder                                                # noqa: E402
from locate import close_preview, ensure_foreground, sandbox_pid, windows   # noqa: E402
from nav import force_en_us, goto_col, trigger_preview                      # noqa: E402
from verify import Guard                                                    # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/a21_batch_columns.mp4"

FILE_QUERY = "WrapperExtServiceImpl.java"
FILE_TITLE_KEY = "WrapperExtServiceImpl"
CHAIN_LINE, CHAIN_COL = 165, 53          # userService.saveOrUpdateBatch(...)
ROW_X = 600
FILE_ROW_YS = (341, 333, 349, 357)

CHK_EMAIL = (427, 588)                   # 「参数列」→ Collections.singletonList(user).email
GAZE_SQL_A = (1100, 300)                 # INSERT 列清单行
GAZE_SQL_B = (1100, 430)                 # ON DUPLICATE KEY UPDATE 段
PARK = (577, 725)
SQL_WATCH = (737, 325, 640, 380)         # 点勾选框后 SQL 文本次处应变化


def ide_hwnd_and_title(pid):
    for h, p, t, r in windows():
        if p == pid and (r[2] - r[0]) > 1200:
            return h, t
    return None, ""


def main():
    import pyautogui

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()
    g = Guard()

    ide_pid = sandbox_pid()
    hwnd, _ = ide_hwnd_and_title(ide_pid)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    print("沙盒 pid=%s 已置前" % ide_pid)

    # ---- prep（不入镜）----
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.7)
    print("关旧预览:", close_preview(ide_pid))
    pyautogui.click(600, 500); time.sleep(0.5)
    pyautogui.hotkey("ctrl", "shift", "n"); time.sleep(1.4)
    pyautogui.hotkey("ctrl", "a"); time.sleep(0.4)
    force_en_us()
    pyautogui.typewrite(FILE_QUERY, interval=0.05); time.sleep(1.8)
    for y in FILE_ROW_YS:
        pyautogui.click(ROW_X, y); time.sleep(1.3)
        if FILE_TITLE_KEY in ide_hwnd_and_title(ide_pid)[1]:
            break
    time.sleep(1.6)
    title1 = ide_hwnd_and_title(ide_pid)[1]
    if FILE_TITLE_KEY not in title1:
        raise RuntimeError("开文件失败：标题仍为 %s" % title1[:70])
    print("开文件后标题 = %s" % title1[:62])

    goto_col(CHAIN_LINE, CHAIN_COL)
    trigger_preview()
    pyautogui.moveTo(*PARK, duration=0.4); time.sleep(1.0)
    print("预览已就绪")

    # ---- 录制：凝视 UPSERT 全貌 → 取消勾选 email → 再看列清单与冲突子句
    r = Recorder(REGION, OUT)
    r.start()
    pyautogui.moveTo(*GAZE_SQL_A, duration=0.6); time.sleep(1.0)
    pyautogui.moveTo(*GAZE_SQL_B, duration=0.8); time.sleep(1.0)
    g.after_click(CHK_EMAIL, region=SQL_WATCH, wait=1.0, retries=1,
                  msg="点「参数列 email」后 SQL 文本无变化（落空）")
    time.sleep(1.2)
    pyautogui.moveTo(*GAZE_SQL_B, duration=0.8); time.sleep(1.0)
    pyautogui.moveTo(*GAZE_SQL_A, duration=0.8); time.sleep(1.2)
    pyautogui.moveTo(*PARK, duration=0.6); time.sleep(0.8)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
