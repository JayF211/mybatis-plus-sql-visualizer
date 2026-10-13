# -*- coding: utf-8 -*-
"""A29 重录 mcp.gif：设置页 MCP 工具门控（工具数 10 → 11）20261012。

旧片（2026-09-13 前后）两处过期：①工具数还是 **10**（本轮已加 `sqlv_history_search`）；
②标题栏是 `Start Free Trial` 试用态。旧片拍的是 IDE 自带 `Tools > MCP Server > Exposed Tools`
页；本片按 `features/mcp.md`「设置页门控」正文口径拍**插件自己的「MCP 工具」子页**
（总开关 + 十一项逐项 + 底部「全部启用/全部禁用」单按钮切换）。

入镜流程：横幅（「11 个 AI 工具已注册」）→ 总开关关（全组置灰）→ 开 → 单工具 `sqlv_rollback_plan`
取消勾选 → 复选 → 收尾。

标定（20261012；IDE @ 277,25；设置对话框已上移并拉高到 1129×981 @ screen (399,24)）：
  横幅 screen (449,147)｜总开关 screen (740,278)
  工具复选列 screen x=740，纵向 step 58，首项 sqlv_render_sql 在 y=330：
    render_sql 330｜schema_context 388｜explain 446｜render_logs 504｜check_sql 562｜
    rollback_plan 620｜perf_advice 678｜crud_sql 736｜reverse_sql 794｜mapper_check 852｜
    history_search 910

坑（本轮实测）：
  ① **该设置页无法滚动**：滚轮（pyautogui.scroll）对 Swing 页面完全无效，PageDown 与拖滚动条也无效
     （只有左树的滚动条拖动有效）。页面高约 750px 而对话框默认可视区只有约 715px → 底部
     「全部启用/全部禁用」按钮会被切掉。**处置：把对话框标题栏上移 + 拖右下角拉高到 981px**，
     十一项即全部入镜（按钮仍差约 30px 只能拍到一部分——故本片以**逐项复选 + 总开关**演示门控）。
  ② 设置对话框是**模态**的：它开着时对其它窗口（如 SQL 预览浮窗）的点击一律无效；要关预览必须先
     关掉对话框。另：**用 Esc 关补全下拉会连对话框一起关**（Esc 是对话框的取消键）。
  ③ 对话框不是进程主窗口，`MainWindowHandle` 看不到——用 `EnumWindows` 按 PID 枚举才拿到边界。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import shot

REGION = (277, 25, 1370, 984)
OUT = "perf/a29_mcp.mp4"

BANNER = (449, 147)
MASTER = (740, 278)
TOOL_ROLLBACK = (740, 620)


def main():
    import pyautogui

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    # 入镜前确认停在插件「MCP 工具」子页（由调用方保证），只把鼠标放好
    pyautogui.moveTo(*BANNER, duration=0.4)
    time.sleep(0.6)

    r = Recorder(REGION, OUT)
    r.start()

    # 1. 横幅：「11 个 AI 工具已注册」
    pyautogui.moveTo(*BANNER, duration=0.5)
    time.sleep(1.0)
    pyautogui.moveTo(*MASTER, duration=0.6)
    time.sleep(0.8)

    # 2. 总开关关 → 十一项整组置灰
    pyautogui.click(*MASTER)
    time.sleep(2.0)

    # 3. 再开 → 恢复
    pyautogui.click(*MASTER)
    time.sleep(1.6)

    # 4. 单工具取消勾选（门控粒度）→ 复选
    pyautogui.click(*TOOL_ROLLBACK)
    time.sleep(1.6)
    pyautogui.moveTo(*BANNER, duration=0.6)
    time.sleep(0.8)
    pyautogui.click(*TOOL_ROLLBACK)
    time.sleep(1.4)

    shot("perf/a29_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
