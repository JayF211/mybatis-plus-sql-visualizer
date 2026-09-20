# -*- coding: utf-8 -*-
"""X1 XML 预览「回滚 SQL」动图（20260919-1500 计划 §五 X1）。

成品：public/demo/xml-preview-rollback.gif + .png（features/xml-preview.md「回滚 SQL」节），
1280 宽 / 15fps。封面 xml-preview.gif 不动。

分镜（约 12 秒）：
1. 编辑器停在 `RoleMapper.xml` 的 `<insert id="insertBatch">` 行（行号 27），行左侧 gutter 图标可见
2. 点 gutter 图标 → 「选择要预览的 SQL」候选浮层（该文件有 select/insert 两条候选）
3. 选中 `insert insertBatch (第 27 行)` → OK → 「SQL Visualizer - SQL 预览」对话框，
   展示渲染后的 INSERT 与 foreach 集合参数
4. 点顶部「回滚 SQL」→ 「回滚 SQL」弹窗：警示行 + 执行前自查 COUNT + 逆向 `DELETE FROM role WHERE ...`
5. 点「复制」→ 图标闪变对号
6. 关闭弹窗 → 关闭预览 → 回编辑器，首尾帧一致

## 标定与坑（2026-09-20）

- **必须用 INSERT**：UPDATE 的逆向 SQL 需要「执行前原值」，弹窗只给「该语句无法自动生成回滚
  （原值不可考）」+ 快照/手工粘贴入口；SELECT 则直接判「PlainSelect 语句不支持自动回滚」。
  文档 `features/xml-preview.md:82` 写「UPDATE 还原 SET 原值」会让人以为可自动生成，措辞偏乐观。
- **候选浮层的 OK 取首项**：该文件第一条候选是 `select selectByIncludeFragment`，只点 OK 会打开
  SELECT 的预览；必须先点 `insert insertBatch` 那一行再 OK。
- **gutter 悬停会弹「Ask AI」浮层**（约 235x42）并**拦截后续合成点击**（本轮被它挡住，
  取消/复制等按钮连续点空）→ 移向 gutter 后要立刻点，别停留；录制前也应确认它不在场。
- 预览对话框自身带一条降级告警（内存 PSI），见实施状态记录，本轮如实入镜。
"""
import sys
import time

import pyautogui

import scene_m1_mcp as base          # 复用 focus / _hwnd / grab
from gifkit import Recorder, click, shot

REGION = (277, 25, 1370, 984)
OUT = "perf/x1_xml_rollback.mp4"

GUTTER = (78, 289)          # <insert> 行左侧 gutter 图标（RoleMapper.xml:27）
PICK_INSERT = (560, 419)    # 候选浮层第 2 行 insert insertBatch
PICK_OK = (808, 657)
BTN_ROLLBACK = (1014, 226)  # 预览对话框顶部「回滚 SQL」
BTN_COPY = (887, 706)       # 回滚弹窗「复制」
BTN_CLOSE_RB = (987, 706)   # 回滚弹窗「关闭」
BTN_CANCEL = (1211, 762)    # 预览对话框「取消」
NEUTRAL_CURSOR = (400, 620)

PREVIEW_TITLE = "SQL Visualizer - SQL 预览"
RB_TITLE = "回滚 SQL"
PICK_TITLE = "选择要预览的 SQL"


def _gutter_icon_y():
    """扫 gutter 列定位插件图标行：goto-line 后行在视口内的 y 不固定，坐标写死会点空。

    插件图标画在 gutter 左侧窄列（x≈72~82），折叠箭头在 x≈85~92、行号在更左，故只扫这一列。
    同一文件可能有多条可预览语句（本文件 select 在 16 行、insert 在 27 行），取**最下方**那个。"""
    img = base.grab()
    band = img[150:600, 70:85]
    rr, gg, bb = band[:, :, 0], band[:, :, 1], band[:, :, 2]
    # 插件图标是蓝色系；折叠箭头/行号是灰的——只按蓝像素判，避免扫到灰色杂点
    rows = ((bb - rr > 25) & (bb > 90)).sum(axis=1)
    ys = [150 + i for i, v in enumerate(rows) if v > 0]
    if not ys:
        return None
    groups = []
    for y in ys:
        if groups and y - groups[-1][-1] <= 6:
            groups[-1].append(y)
        else:
            groups.append([y])
    return int(sum(groups[-1]) / len(groups[-1]))


def _dlg(title):
    return base._hwnd(title, "SunAwtDialog")


def _click(x, y, wait=1.0):
    w = base._hwnd("mybatis-plus-sql-visualizer-demo", "SunAwtFrame")
    if w:
        base.focus(w)
    click(REGION[0] + x, REGION[1] + y, move_dur=0.22, pauses=(0.15, 0.2))
    time.sleep(wait)


def _click_in(title, x, y, wait=1.0):
    d = _dlg(title)
    if d:
        base.focus(d)
    click(REGION[0] + x, REGION[1] + y, move_dur=0.22, pauses=(0.15, 0.2))
    time.sleep(wait)


def _close_all():
    """关掉可能残留的三个对话框（Esc 前必须先聚焦各自窗口）。"""
    for _ in range(4):
        for t in (RB_TITLE, PICK_TITLE, PREVIEW_TITLE):
            d = _dlg(t)
            if d:
                base.focus(d)
                pyautogui.press('esc')
                time.sleep(0.8)
        if not any(_dlg(t) for t in (RB_TITLE, PICK_TITLE, PREVIEW_TITLE)):
            return True
    return False


def _open_preview():
    """光标落到 <insert> 语句行 → Alt+Shift+Q → 预览对话框。

    光标在语句内时该快捷键**直接开预览**（不弹候选浮层）——实测本文件两条候选下，
    光标在 insert 内即命中 insert，无需选候选。"""
    import pyautogui as pg
    gy = _gutter_icon_y()
    w = base._hwnd("mybatis-plus-sql-visualizer-demo", "SunAwtFrame")
    if gy is not None:
        if w:
            base.focus(w)
        pg.moveTo(REGION[0] + 400, REGION[1] + gy, duration=0.25)
        pg.click()
        time.sleep(0.8)
    for _ in range(3):
        if w:
            base.focus(w)
        pg.hotkey('alt', 'shift', 'q')
        time.sleep(2.8)
        if _dlg(PREVIEW_TITLE):
            return True
    return False


def _open_picker():
    """打开「选择要预览的 SQL」候选浮层。

    文档写的入口是语句行左侧 gutter 图标，但本沙盒里对该图标的合成点击**不稳定**
    （同一坐标 4 连击全空，实测只有偶发命中），而键盘入口 `Alt+Shift+Q`（光标在语句内）
    每次都成——故录制走键盘入口，画面里 gutter 图标仍在、只是触发方式不同。"""
    import pyautogui as pg
    gy = _gutter_icon_y()
    w = base._hwnd("mybatis-plus-sql-visualizer-demo", "SunAwtFrame")
    if gy is not None:
        if w:
            base.focus(w)
        pg.moveTo(REGION[0] + 400, REGION[1] + gy, duration=0.25)   # 光标落到语句行
        pg.click()
        time.sleep(0.7)
    for _ in range(3):
        if w:
            base.focus(w)
        pg.hotkey('alt', 'shift', 'q')
        time.sleep(2.5)
        if _dlg(PICK_TITLE):
            return True
    return False


def _open_insert_preview():
    return _open_preview()


def prep():
    """就位到录制起点（不进 GIF）：打开 RoleMapper.xml 第 27 行、清干净所有对话框。"""
    w = base._hwnd("mybatis-plus-sql-visualizer-demo", "SunAwtFrame")
    if not w:
        raise RuntimeError("IDE 主窗未找到")
    base.focus(w)
    _close_all()
    pyautogui.hotkey('ctrl', 'shift', 'n')
    time.sleep(1.8)
    base.paste("RoleMapper.xml")
    time.sleep(1.8)
    pyautogui.press('return')
    time.sleep(2.5)
    pyautogui.hotkey('ctrl', 'g')
    time.sleep(1.2)
    base.paste("27")
    time.sleep(0.6)
    pyautogui.press('return')
    time.sleep(1.5)
    _close_all()
    # 自检：能开出预览对话框（开完立刻关掉，只作可行性判定）
    if not _open_insert_preview():
        raise RuntimeError("prep：无法打开 insert 预览（候选浮层或预览对话框未出现）")
    _close_all()
    shot(REGION, "perf/_x1_prep.png")
    print("prep ok: RoleMapper.xml:27 就位，预览链路可开")


def main():
    prep()
    time.sleep(0.4)
    r = Recorder(REGION, OUT)
    time.sleep(0.8)
    r.start()
    time.sleep(0.6)                       # 起始定格：编辑器 + gutter 图标

    # 1. 光标入语句 → Alt+Shift+Q → 预览
    if not _open_preview():
        r.stop()
        raise RuntimeError("预览对话框未出现")
    time.sleep(1.2)                       # 分镜 3 定格：INSERT 预览

    # 2. 回滚 SQL → 逆向 DELETE
    _click_in(PREVIEW_TITLE, BTN_ROLLBACK[0], BTN_ROLLBACK[1], wait=2.2)
    if not _dlg(RB_TITLE):
        r.stop()
        raise RuntimeError("回滚弹窗未出现")
    time.sleep(1.3)                       # 分镜 4 定格：逆向 SQL

    # 3. 复制 → 对号
    _click_in(RB_TITLE, BTN_COPY[0], BTN_COPY[1], wait=1.0)
    time.sleep(0.6)

    # 4. 关弹窗 → 关预览 → 回编辑器
    _click_in(RB_TITLE, BTN_CLOSE_RB[0], BTN_CLOSE_RB[1], wait=0.9)
    if not _close_all():
        r.stop()
        raise RuntimeError("对话框未能全部关闭")
    time.sleep(0.5)

    # 5. 光标归位，首尾帧一致
    pyautogui.moveTo(REGION[0] + NEUTRAL_CURSOR[0], REGION[1] + NEUTRAL_CURSOR[1],
                     duration=0.5)
    time.sleep(1.2)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
