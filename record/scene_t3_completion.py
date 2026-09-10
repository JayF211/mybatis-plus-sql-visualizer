# -*- coding: utf-8 -*-
"""T3 code-completion：selectUserByTrim 的 from user 行尾 Enter 新行 →
键入 "join us" 自动弹出表名补全（含实体类中文描述）→ Enter 采纳 user → 定格。

前置：UserMapper.xml 打开滚动到 line 78 (from user)；无弹窗、无选中。
注意：本机 Ctrl+Space 是微软拼音中英切换热键，禁用之，全部依赖 IDEA 自动弹出。
录后需 undo 清理编辑。
"""
import sys
import time

from gifkit import Recorder, click, goto_line, open_file, pyautogui

REGION = (277, 25, 1370, 984)
OUT = "perf/t3_completion.mp4"

FROM_LINE_END = (1027, 419)  # line 77 "from user" 行中（点击后 End 键到位）


def main():
    # 文件已在前一 take 打开（tab 活跃），跳过 open_file 以免 ctrl+shift+n 翻转 IME
    goto_line(77)

    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    click(*FROM_LINE_END, move_dur=0.3)  # 1. 光标到 from user 行尾
    pyautogui.press('end')
    time.sleep(0.4)
    pyautogui.press('enter')             # 2. 新行
    time.sleep(0.5)
    pyautogui.typewrite('join us', interval=0.09)  # 3. 边打边弹
    time.sleep(1.8)                      # 补全弹窗定格
    pyautogui.keyDown('return'); pyautogui.keyUp('return')  # 4. 采纳 user
    time.sleep(1.6)                      # 结果定格
    for _ in range(4):                   # 5. 撤回编辑（还原现场，循环无缝）
        pyautogui.hotkey('ctrl', 'z')
        time.sleep(0.4)
    time.sleep(0.6)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
