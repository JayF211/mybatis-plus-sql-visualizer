# -*- coding: utf-8 -*-
"""A19+A20 返回类型自由输入（sql-to-java-return-type.gif）20261011 v5（终版）：
前置态：调用类=this（画面可见）。SQL 输入区贴 GROUP BY+HAVING 语句（A20 并入）→
转换 → 实体选择弹窗（FQCN 副标题）双击 cn.song.mybatisplusdemo.entity.User →
生成 LambdaQueryWrapper（groupBy/having/orderByDesc 链）→
返回类型框点击弹类选择器 → 键入 List → 等固定候选渲染 → Enter 选 List<Map<String, Object>>
→ 再转换 → 双击实体 → 调用行 `List<Map<String, Object>> list = this.listMaps(queryWrapper);`。
标定（20261011 屏幕坐标）：转换▶ (994,207)；SQL 输入 (1150,415)；实体弹窗第 2 行 (763,410)；
调用类框 (1048,290)；返回类型框 (1428,291)；输出区 (1130,650)。
坑：IME 须英文态（微软拼音 Shift 切换）；合成滚轮对输出区无效（用 Ctrl+End）。
"""
import sys
import time

from gifkit import Recorder, click, paste, typewrite

REGION = (277, 25, 1370, 984)
OUT = "perf/a19_returntype.mp4"

CONVERT = (994, 207)
SQL_INPUT = (1150, 415)
ENTITY_ROW2 = (763, 410)
CALLER = (1048, 290)
RET_TYPE = (1428, 291)
OUTPUT = (1130, 650)

SQL = "SELECT role_id, count(*) AS cnt, max(age) AS max_age FROM user WHERE deleted = 0 GROUP BY role_id HAVING count(*) > 1 ORDER BY cnt DESC"


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 贴 SQL → 转换 → 双击实体（FQCN 弹窗入镜）
    click(*SQL_INPUT, move_dur=0.3, pauses=(0.12, 0.15))
    paste(SQL)
    time.sleep(0.6)
    click(*CONVERT, move_dur=0.25, pauses=(0.15, 0.15))
    time.sleep(1.8)
    pyautogui.doubleClick(*ENTITY_ROW2)
    time.sleep(1.8)

    # 2. 返回类型：点击弹选择器 → 键入 List → 等固定候选 → Enter
    click(*RET_TYPE, move_dur=0.3, pauses=(0.12, 0.1))
    typewrite('List', interval=0.06)
    time.sleep(2.6)
    pyautogui.keyDown('return'); time.sleep(0.08); pyautogui.keyUp('return')
    time.sleep(1.0)

    # 3. 再转换 → 双击实体 → 调用行 listMaps 形态 → Ctrl+End 展示
    click(*CONVERT, move_dur=0.25, pauses=(0.15, 0.15))
    time.sleep(1.8)
    pyautogui.doubleClick(*ENTITY_ROW2)
    time.sleep(2.0)
    click(*OUTPUT, move_dur=0.3, pauses=(0.1, 0.1))
    pyautogui.hotkey('ctrl', 'end')
    time.sleep(1.2)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
