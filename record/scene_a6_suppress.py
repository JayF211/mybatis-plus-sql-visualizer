# -*- coding: utf-8 -*-
"""A6 SQL 屏蔽（sql-suppress.gif）20261011：
前置：列表有 2 条普通 SQL + 6 条心跳 `SELECT 1`（LogFeederController）、
      「移除重复的 SQL」已取消勾选（否则心跳被折叠成一条、刷屏不可见）、
      无屏蔽规则、无浮层/弹窗残留、Console 页签在前。
流程：右键 `SELECT 1` 条目 → 菜单「屏蔽此 SQL」→ 存量行立即消失 + 右下 balloon
      → 心跳继续灌入（被拦截、列表不动）→ 工具栏漏斗开「管理屏蔽规则」
      → 规则行含「已拦截 N 次」+ 右侧只读 SQL 预览 → 点「删除」规则
      → 仍在列表缓冲中的条目重新显示（循环接缝由此闭合）→ 关闭对话框。

标定（20261011 屏幕坐标，IDE @ 277,25；全部从裁剪放大图量，勿从整图读——整图被显示缩放会差 40px 上下）：
  SELECT 1 条目行      region (1000,475) → screen (1277,500)
  菜单「屏蔽此 SQL」    region (1100,586) → screen (1377,611)
  工具栏漏斗           region (818,182)  → screen (1095,207)
  对话框「删除」        region (485,641)  → screen (762,666)
  对话框「关闭」        region (991,698)  → screen (1268,723)

注：心跳 6 条会被同模板折叠成一条带 `×N` 徽标的条目（列表只显示一行），屏蔽后该行整体消失；
    右键点在哪一行，菜单就从该行 y 向下展开——换行数就要重标「屏蔽此 SQL」那一项。

坑：① 右键菜单项单击即生效（菜单自带焦点），别连点——第二击会落到列表上弹出预览浮层；
    ② 对话框「关闭」正好压在底部 Services 页签栏同一 y 上，**只点一次**；点两次会把 Services
       切到 Health 页签（画面里出现无关切换）；
    ③ 记录态循环接缝不闭合是结构性的：屏蔽后存量行消失，结尾不复原（计划 A6 以「行消失」为本体）。
"""
import sys
import time
import urllib.request

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a6_suppress.mp4"

SEL1_ROW = (1277, 500)
MENU_SUPPRESS = (1377, 611)
TOOLBAR_RULES = (1095, 207)
DLG_DELETE = (762, 666)
DLG_CLOSE = (1268, 723)


def feed(path):
    try:
        with urllib.request.urlopen("http://localhost:8081" + path, timeout=10) as r:
            return r.status
    except Exception as e:  # noqa: BLE001
        return repr(e)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.6)

    # 1. 右键一条心跳条目
    pyautogui.click(*SEL1_ROW, button="right")
    time.sleep(1.5)

    # 2. 「屏蔽此 SQL」→ 存量行消失 + balloon
    pyautogui.click(*MENU_SUPPRESS)
    time.sleep(1.9)

    # 3. 心跳继续灌入 —— 已被规则拦截，列表不再增行
    feed("/logFeed/heartbeat?times=4&intervalMs=300")
    time.sleep(1.8)

    # 4. 工具栏漏斗 → 管理屏蔽规则（规则 + 已拦截次数 + 右侧预览）
    pyautogui.click(*TOOLBAR_RULES)
    time.sleep(2.4)

    # 5. 「删除」规则 → 缓冲中的心跳条目重新显示（闭合循环接缝）
    pyautogui.click(*DLG_DELETE)
    time.sleep(1.9)

    # 6. 关闭（只点一次：该按钮与底部 Services 页签栏同 y，点两次会误切页签）
    pyautogui.click(*DLG_CLOSE)
    time.sleep(1.8)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
