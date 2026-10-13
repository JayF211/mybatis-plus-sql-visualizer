# -*- coding: utf-8 -*-
"""A28 数据源字段补全（completion-datasource.gif）20261013。

素材：`mybatis-plus-join-demo/src/main/resources/db/schema.sql` 第 105 行
      `INSERT INTO `car_device_info` (`id`, `station_id`, ...) VALUES` 的**列清单里**——
      在 SQL 编辑器里敲一个字母，补全候选项列出**数据源里的真实列**：
      实测 `share_scheme_id (car_device_info) INT` / `station_id (car_device_info) INT`
      （列名 + 归属表 + **数据库真实类型尾注**），与关键字/函数候选并列。

⚠️ 补全注册语言只有 **SQL / XML / MpSqlMapper**（`plugin.xml:276/282/291`），**没有 Java**——
所以本张必须在 SQL 编辑器（本片用 `.sql`）或 mapper XML 里录；在 Java 字符串里敲字母不会弹补全（实测）。

成片一幕：光标落在列清单 `(` 之后 → 敲 `s` → 补全弹层出现（真实列 + 类型）→ 凝视候选项 →
收尾**停在补全弹层上**（末帧即 poster）。take 之后在片外清理：Esc 关弹层 + Ctrl+Z 撤销那个字母。

标定（20261013，IDE @ 277,25）：
  SQL 编辑区（列清单）  screen (600,400)
  补全弹层           region (437-1067, 225-610) → screen (714-1344, 250-635)
  凝视点              screen (900,300) / (900,380)
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder                                                # noqa: E402
from locate import ensure_foreground, sandbox_pid, windows                  # noqa: E402
from nav import force_en_us, goto_col                                       # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/a28_completion_datasource.mp4"

TAB_SCHEMA = (707, 102)          # 标签栏里的 schema.sql
INS_LINE, INS_COL = 105, 32      # INSERT INTO `car_device_info` ( ← 括号后
GAZE_POPUP_A = (900, 300)
GAZE_POPUP_B = (900, 380)
PARK = (600, 700)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False; pyautogui.PAUSE = 0
    force_en_us()

    ide_pid = sandbox_pid()
    hwnd = next(h for h, p, t, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    print("沙盒 pid=%s 已置前" % ide_pid)

    # prep（不入镜）
    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.5)
    pyautogui.click(*TAB_SCHEMA); time.sleep(1.5)     # 切到 schema.sql（SQL 语言，补全才注册）
    t = next((x for h, p, x, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200), '')
    if 'schema.sql' not in t:
        raise RuntimeError("未切到 schema.sql：%s" % t[:60])
    pyautogui.click(600, 400); time.sleep(0.4)
    goto_col(INS_LINE, INS_COL)
    time.sleep(0.5)

    r = Recorder(REGION, OUT)
    r.start()
    pyautogui.typewrite('s', interval=0.06)           # 敲字母 → 打字即补全
    time.sleep(2.0)
    pyautogui.moveTo(*GAZE_POPUP_A, duration=0.7); time.sleep(1.5)
    pyautogui.moveTo(*GAZE_POPUP_B, duration=0.7); time.sleep(1.5)
    pyautogui.moveTo(*GAZE_POPUP_A, duration=0.6); time.sleep(1.2)
    print("frames:", r.stop())

    # 片外清理：关补全弹层 + 撤销刚敲的字母（别弄脏 demo 文件）
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.6)
    pyautogui.hotkey('ctrl', 'z'); time.sleep(1.0)
    print("已清理（Esc + Ctrl+Z）")


if __name__ == "__main__":
    sys.exit(main())
