# -*- coding: utf-8 -*-
"""A11 EXPLAIN 真实执行计划（explain-plan.gif）20261012。

素材：`DeviceInfoServiceImpl#joinListEntity`（mybatis-plus-join-demo，car_device_info
      LEFT JOIN station_info 的 MPJ 链）。
流程：光标压链 token → 预览 → 点「执行计划」→ 选 `mpj_demo@localhost`（弹层第二行）
      → 等 EXPLAIN 提交原生数据库控制台执行 → 结构化计划表与静态性能规则同屏 → 凝视。

标定（20261012，IDE @ 277,25，整窗截图坐标 + (277,25)）：
  编辑器取焦 (600,500)｜预览浮窗体内（关窗）(760,400)
  「执行计划」按钮 screen (1295,251)（实测；旧值 (1300,253) 同点）
  数据源弹层第二行 mpj_demo@localhost screen (1276,237)
  结果计划表 screen y≈720，x 737~1517

坑（本轮实测，全部已修）：
  ① **原目标跑不通**：`UserServiceImpl#selectJoinList` 主表是 UserDO →
     `@TableName("\"user\"")`（达梦风格双引号，见同模块 schema-dm.sql）。MySQL 默认把 `"`
     当字符串字面量 → EXPLAIN 必报 `[42000][1064] ... near '"user" t' at line 11`。
     换成本脚本的 DeviceInfoServiceImpl 目标（表名都不是保留字）后一次通过。
     **这是 demo 侧的数据问题、不是插件缺陷**：插件忠实透传注解里的表名（SqlGenerator
     .renderTableName → dialect.quoteIfKeyword，MySQL 方言本用反引号；注解里已带引号故原样输出）。
  ② 打开文件必须**点结果行且扫掠**：Return 打不开「文件」搜索弹窗的结果行（JBR 吞键，
     见 probe_openfile.py），且行位在 region y 350~366 之间浮动。原脚本单点 (777,342)
     落在扫掠窗口之外 → 弹窗关不掉，后续 Ctrl+G 与 "49" 全被搜索框吃掉。
  ③ 落链 token 用 goto_col(行,列)：goto_text 的 Ctrl+F 路径实测没命中（光标停在 53:7），
     插件报「未在光标处找到可预览的 SQL 源」。
  ④ 数据源弹层行位随弹层摆位变：旧值 (860,251) 落在弹层之外（会点到后面的预览面板）。
  ⑤ 此前「执行中…」挂死的根因＝沙盒缺 IDE 密钥链（c.kdbx/c.pwd），已从真机拷入并经用户
     手工激活授权——本轮实测 EXPLAIN 正常出计划，未再挂死。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import force_en_us, goto_col, shot, trigger_preview
from locate import sandbox_pid, window_rect

REGION = (277, 25, 1370, 984)
OUT = "perf/a11_explain.mp4"

FILE_QUERY = "mpjdemo/DeviceInfoServiceImpl.java"
CHAIN_LINE = 110                             # .eq(DeviceInfoEntity::getShareSchemeId, 1)
CHAIN_COL = 18                               # 'eq' 首字符列（1 基）

EDITOR_SPOT = (600, 500)
CANCEL_XY = (1485, 828)
ROW_X = 777
ROW_YS = (350, 358, 366)
BTN_EXPLAIN = (1295, 251)
DS_MPJ_ROW = (1276, 237)
# ⚠️ 凝视路径必须**全程避开 LEFT JOIN 那一行**（region y≈528，x 508~1370）：
# 光标扫过它会在 ON 子句上弹一个 `1..n<->0..1:` 关系提示，且**移开鼠标也不消失**
# （与「列文档浮层粘住」同源），会把 SQL 正文和末帧一起毁掉。所以：
#   · 起手两点都留在 JOIN 行**上方**（警告行 → 列清单区）；
#   · 点完数据源回左栏时先走一个左栏上方航点，让整段路径的 x 始终 < 508。
GAZE_A = (527, 262)       # 左上「ORDER BY 无 LIMIT…建议添加 LIMIT」静态规则警示行
GAZE_B = (977, 420)       # SQL 列清单区（JOIN 行上方）
WAYPOINT_LEFT = (500, 300)  # 左栏上方航点：把回程路径压在 JOIN 行左侧
GAZE_PLAN_A = (500, 880)  # 底部 mpsql-exec.sql 控制台（真正执行的那条 EXPLAIN）
GAZE_PLAN_B = (577, 725)  # 左侧面板正中空白（远离控件与结果网格，无浮层）


def close_preview_hard(pyautogui):
    """关掉 SQL 预览弹窗并**校验真的关了**（EnumWindows 里窗口消失）。

    为什么不点「取消」：弹窗是**无边框窗口**，窗口矩形比可见边界大约 50px，按钮行目测/旧标定
    都会落空（旧坐标 (1485,828) 本轮实测点空）——落空的代价不是「没关」这么轻：旧弹窗会留着
    上一轮的结果页签，下一轮的「执行计划」被塞进同一个弹窗成 `Result 2`，内嵌结果网格列被截断。
    改「点弹窗体内 → Esc」（弹窗拿到焦点时 Esc 才关得掉），并以窗口消失为判据。
    """
    pid = sandbox_pid()
    main = window_rect(pid=pid, pick="largest", min_size=(800, 600))
    pop = window_rect(pid=pid, pick="smallest", min_size=(600, 400))
    if not pop or pop == main:
        return True
    pyautogui.click(pop[0] + 120, pop[1] + 600)   # 弹窗左栏空白，拿焦点
    time.sleep(0.4)
    pyautogui.keyDown("esc")
    pyautogui.keyUp("esc")
    time.sleep(1.0)
    pop2 = window_rect(pid=pid, pick="smallest", min_size=(600, 400))
    return not pop2 or pop2 == main


def main():
    import pyautogui

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：关预览/弹窗 → 开文件 → 光标压 token → 开好预览 ----
    print("关旧预览弹窗:", "已关闭" if close_preview_hard(pyautogui) else "!! 没关掉")
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.6)
    pyautogui.click(*EDITOR_SPOT); time.sleep(0.4)
    pyautogui.hotkey("ctrl", "shift", "n"); time.sleep(1.4)
    pyautogui.hotkey("ctrl", "a"); time.sleep(0.5)
    force_en_us()
    pyautogui.typewrite(FILE_QUERY, interval=0.05); time.sleep(1.6)
    for y in ROW_YS:
        pyautogui.click(ROW_X, y); time.sleep(0.4)
    time.sleep(2.4)
    goto_col(CHAIN_LINE, CHAIN_COL)
    trigger_preview()
    # trigger 后把光标移离代码区：压在链 token 上会弹 documentation 悬浮窗挡预览画面
    #（take1 报废的直接原因）；SQL 面板中部安全。
    pyautogui.moveTo(1100, 480, duration=0.4)
    time.sleep(0.6)

    r = Recorder(REGION, OUT)
    r.start()

    # 1. 起手凝视：SQL 与静态性能规则警示同屏
    pyautogui.moveTo(*GAZE_A, duration=0.6)
    pyautogui.moveTo(*GAZE_B, duration=1.0)

    # 2. 点「执行计划」→ 弹数据源选择
    pyautogui.click(*BTN_EXPLAIN); time.sleep(2.2)

    # 3. 选 mpj_demo@localhost → 提交 EXPLAIN（首连可能 5~15s）
    pyautogui.click(*DS_MPJ_ROW)
    time.sleep(2.5)          # 先给「执行中…」一拍入镜
    time.sleep(10.0)         # 等结果渲染（本轮实测计划表在 16s 内到位）

    # 4. 凝视计划结果：结构化 EXPLAIN 与静态规则同屏对照
    pyautogui.moveTo(*WAYPOINT_LEFT, duration=0.5)   # 左栏上方航点：避开 JOIN 行横带
    pyautogui.moveTo(*GAZE_PLAN_A, duration=1.0)
    pyautogui.moveTo(*GAZE_PLAN_B, duration=1.0)
    time.sleep(0.8)

    shot("perf/a11_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
