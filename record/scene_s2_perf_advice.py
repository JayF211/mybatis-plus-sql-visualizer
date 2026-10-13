# -*- coding: utf-8 -*-
"""S2 静态性能建议分段（perf-advice.gif）20261012。

素材：`DeviceInfoServiceImpl#joinListEntity`（mybatis-plus-join-demo，L110 列 18）
      —— 实测该链在预览面板顶部元信息行后渲染**两条**性能建议：
        `⚠ LIKE '%xxx' 含前导通配符 —— 无法利用 B-Tree 索引，建议改用全文搜索或后缀匹配`
        `⚠ ORDER BY 无 LIMIT —— 可能返回大量结果集，建议添加 LIMIT`
成片一幕：**在 take 内**唤起预览（Alt+Shift+Q）→ 浮窗连建议行一起出现 →
凝视两条建议行与 SQL 文本（体现「预览即出建议」）。

为什么把「唤起预览」放进 take 而不是 prep：本张要展示的就是**建议行随预览出现**，
若 prep 里先开好弹窗，take 内只有鼠标移动——裸屏捕获不含光标，静默凝视会被静段裁剪
整段吃掉（实测成片只剩 3.6s）。

⚠️ 素材口径偏差（如实记录）：计划原写「一条同时含 `SELECT *` + `LIKE '%x'` + 无 WHERE 的
SQL」，但 **XML 语句那条路实测一条建议都不出**（`OrderMapper.xml` 的
`selectOrdersByCondition`：`SELECT * FROM t_order` + `LIKE CONCAT('%', ?, '%')` +
`ORDER BY create_time DESC`，预览头部只有「表: t_order XML select: …」、无任何 ⚠/ℹ 行，
与 MPJ 链同样条件却出两条形成对照）——已另行登记待查；本张先用**能稳定出两条建议**的
MPJ 链出片，`select_star` 那条规则在 MP/MPJ 链下也触发不了（链会展开成显式列、不产生
字面 `SELECT *`）。

标定（20261012，IDE @ 277,25，弹窗 (367,166,1557,868)）：
  建议行 1（LIKE 前导）  screen (700,262)   ← region 423,237
  建议行 2（ORDER BY）   screen (700,280)   ← region 423,255
  SQL 文本区             screen (1100,420)
  左栏空白收尾           screen (577,725)
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder                                                # noqa: E402
from locate import close_preview, ensure_foreground, sandbox_pid, windows   # noqa: E402
from nav import force_en_us, goto_col, trigger_preview                      # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/s2_perf_advice.mp4"

FILE_QUERY = "WrapperAdvancedCoverageServiceImpl.java"
FILE_TITLE_KEY = "WrapperAdvancedCoverageServiceImpl"
CHAIN_LINE, CHAIN_COL = 202, 18          # .like(User::getUsername, "张")（加注释块后行号会漂，改素材必重算）
ROW_X = 600
FILE_ROW_YS = (341, 333, 349, 357)

ADV_A = (700, 258)          # 建议行（可见截断态）——实测 region y≈233 → screen 258
ADV_HOVER = (700, 258)      # 停住读全文 → tooltip（落点偏 27px 就不弹）
GAZE_TOOLTIP = (700, 330)   # tooltip 区域
GAZE_SQL = (1100, 460)
PARK = (577, 725)


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

    ide_pid = sandbox_pid()
    hwnd, _ = ide_hwnd_and_title(ide_pid)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    print("沙盒 pid=%s 已置前" % ide_pid)

    # ---- prep（不入镜）：开文件、光标压链 token，但**不开**预览 ----
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
    t1 = ide_hwnd_and_title(ide_pid)[1]
    if FILE_TITLE_KEY not in t1:
        raise RuntimeError("开文件失败：标题仍为 %s" % t1[:70])
    print("开文件后标题 = %s" % t1[:62])
    goto_col(CHAIN_LINE, CHAIN_COL)
    pyautogui.moveTo(*PARK, duration=0.4); time.sleep(0.6)

    # ---- 录制：take 内唤起预览（建议行随预览出现）→ 凝视建议行 → 悬停看全文 ----
    # 建议分段按**宽度预算**裁剪（可见 label 只放得下第一条、末尾补「…」，第二条被裁掉），
    # **全文在 tooltip 里**——所以特意把光标停在建议行上停住，让 tooltip 显示多条建议全文
    # （这正是计划 S2 里写的「截断加省略号 + 悬停看全文」）。
    r = Recorder(REGION, OUT)
    r.start()
    pyautogui.moveTo(900, 500, duration=0.5); time.sleep(0.4)
    trigger_preview(wait=1.2)          # 浮窗连建议行一起出现（真实 UI 变化）
    time.sleep(1.8)
    pyautogui.moveTo(*ADV_A, duration=0.7); time.sleep(1.0)   # 建议行（可见截断态）
    pyautogui.moveTo(*ADV_HOVER, duration=0.4)               # 停住 → tooltip 出全文
    time.sleep(2.8)
    pyautogui.moveTo(*GAZE_TOOLTIP, duration=0.6); time.sleep(1.6)
    pyautogui.moveTo(*GAZE_SQL, duration=0.8); time.sleep(1.2)
    # 收尾**停在建议行上**（不 park 到空白）：本张要展示的就是悬停全文 tooltip，
    # 末帧/poster 就该是它——移到别处 tooltip 会消失，poster 上什么都看不到。
    pyautogui.moveTo(*ADV_HOVER, duration=0.6); time.sleep(2.0)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
