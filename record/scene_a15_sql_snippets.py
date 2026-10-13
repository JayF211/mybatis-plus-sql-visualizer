# -*- coding: utf-8 -*-
"""A15 SQL 快捷模板（sql-snippets.gif）20261013。

素材：`mybatis-plus-join-demo/src/main/resources/db/schema.sql:59` `CREATE TABLE `car_device_info``
      —— 选中 `car_device_info` 这个标识符；该表在**当前绑定库 `mpj_demo`** 里真实存在，
      所以三条模板都能执行成功（原计划的 `t_order` 在 `mp_demo`，用它必报 1146）。

成片一幕：编辑器里已选中表名 → **鼠标右键**弹编辑器菜单 → 菜单**最后一项**是
「SQL 快捷模板 ▸」（`plugin.xml` 以 `anchor="last"` 挂到 `EditorPopupMenu`）→ 悬停展开
→ 三项（EXPLAIN 选中表 / COUNT 选中表 / 抽样 10 行）→ 点「抽样 10 行」→ 生成
`SELECT * FROM car_device_info LIMIT 10` 并在原生 Database 控制台执行出结果。

标定（20261013，IDE @ 277,25，整屏 1920x1080）：
  表名标识符（schema.sql L59）  screen (557,374)   ← 右键点它（必须落在选区**内**）
  菜单最后一项「SQL 快捷模板」   screen (690,1050)  ← 悬停即展开子菜单
  子菜单「抽样 10 行」          screen (997,1049)  ← 另两项在 y≈985(EXPLAIN)/1017(COUNT)
  控制台凝视                    screen (1100,760)

坑（本轮实测）：
  ① **文本输入必须走剪贴板 `gifkit.paste()`**：`typewrite` 会被中文输入法（微软拼音）组字吃掉
     （本轮 Go-to-File 里被打成 `join-demo/db/schema. s.sql` 并弹出拼音候选条，结果为空）。
  ② **右键菜单不吃键盘**：`Shift+F10` 开不出菜单、`End`/`Right` 在菜单里无反应（与 Return 被吞同源），
     键位会落到编辑器里**清掉选区**（清掉后子菜单三项变灰）。菜单导航一律用鼠标。
  ③ 菜单很长、**最后一项在屏幕底部之外**（IDE 窗口只有 984px 高，菜单是弹窗、可超出）——
     抓整屏才看得到；悬停它即可展开子菜单。
  ④ 右键必须点在**选区内部**：点在选区外会移动光标并清掉选区 → 子菜单三项变灰。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder, paste                                         # noqa: E402
from locate import close_preview, ensure_foreground, sandbox_pid, windows   # noqa: E402
from nav import force_en_us, goto_col                                       # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/a15_sql_snippets.mp4"

QUERY = "join-demo/db/schema.sql"
SEL_LINE, SEL_COL, SEL_LEN = 59, 15, 15     # CREATE TABLE `car_device_info`
SERVICES_ICON = (297, 930)

TBL_TOKEN = (545, 386)                       # 第 59 行 `car_device_info`（实测 region y=361 → screen 386；
#                                              落 374 会点到第 58 行注释、选中中文词）
TBL_RIGHTCLICK = (545, 386)                  # 右键必须落在**选区内部**
MENU_LAST = (690, 1050)                      # 「SQL 快捷模板 ▸」
SUB_SAMPLE = (997, 1049)                     # 子菜单「抽样 10 行」
GAZE_CONSOLE = (1100, 760)
PARK = (577, 725)


def title(pid):
    for h, p, t, r in windows():
        if p == pid and (r[2] - r[0]) > 1200:
            return t
    return "?"


def main():
    import pyautogui
    pyautogui.FAILSAFE = False; pyautogui.PAUSE = 0
    force_en_us()

    ide_pid = sandbox_pid()
    hwnd = next(h for h, p, t, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    print("沙盒 pid=%s 已置前" % ide_pid)

    # ---- prep（不入镜）----
    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.5)
    close_preview(ide_pid)

    # 开 schema.sql（输入走剪贴板粘贴）
    pyautogui.hotkey('ctrl', 'shift', 'n'); time.sleep(1.5)
    pyautogui.click(683, 272); time.sleep(0.4)
    pyautogui.hotkey('ctrl', 'a'); time.sleep(0.3)
    pyautogui.keyDown('delete'); pyautogui.keyUp('delete'); time.sleep(0.3)
    force_en_us()
    paste(QUERY); time.sleep(1.8)
    for y in (341, 333, 349, 357):
        pyautogui.click(600, y); time.sleep(1.3)
        if "schema.sql" in title(ide_pid):
            break
    time.sleep(1.4)
    t1 = title(ide_pid)
    if "schema.sql" not in t1:
        raise RuntimeError("开文件失败：标题仍为 %s" % t1[:70])
    print("开文件后标题 = %s" % t1[:64])

    # 打开 Services（控制台要入镜）——用它的快捷键 Alt+8（Find Action 里查到，
    # 别再点左侧工具窗条图标：那里 y=930 是 **Git**，点错会把 Git 窗打开）
    pyautogui.hotkey('alt', '8'); time.sleep(1.8)

    # 选中表名标识符：**必须鼠标双击**（用户定案）——键盘 shift+右 在中文输入法态下
    # 会被 IME 当成中英切换吃掉，选区根本没形成（状态行只有 `59:30` 而没有 `(N chars)` 后缀，
    # 子菜单三项因此恒为禁用）。双击不依赖修饰键、与输入法无关。
    pyautogui.click(600, 400); time.sleep(0.4)
    goto_col(SEL_LINE, SEL_COL)          # 先把第 59 行滚进视野
    time.sleep(0.4)
    pyautogui.doubleClick(*TBL_TOKEN); time.sleep(0.8)   # 双击 → 整个表名高亮

    # ---- 录制 ----
    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.6)
    pyautogui.click(*TBL_RIGHTCLICK, button='right'); time.sleep(1.6)   # 弹菜单
    pyautogui.moveTo(*MENU_LAST, duration=0.6); time.sleep(1.8)         # 悬停末项 → 子菜单
    pyautogui.click(*SUB_SAMPLE); time.sleep(2.0)                       # 点「抽样 10 行」
    time.sleep(3.0)                                                     # 等控制台出结果
    pyautogui.moveTo(*GAZE_CONSOLE, duration=0.7); time.sleep(1.6)
    pyautogui.moveTo(*PARK, duration=0.6); time.sleep(0.8)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
