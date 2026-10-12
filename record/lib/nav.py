"""沙盒 IDE 导航助手：跳行并回读状态栏校验落点（Goto Line 对话框会残留上次输入，必须先全选清空）。"""
import ctypes, time, pyautogui, mss

ctypes.windll.shcore.SetProcessDpiAwareness(2)
pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

WIN = {'left': 277, 'top': 25, 'width': 1370, 'height': 984}
SB  = {'left': 277, 'top': 25 + 944, 'width': 1370, 'height': 40}   # 状态栏条


def shot(path, region=WIN):
    with mss.mss() as sct:
        img = sct.grab(region)
        mss.tools.to_png(img.rgb, img.size, output=path)


def goto(line, wait=1.2, verify_shot=None):
    """Ctrl+G 跳行。goto 对话框残留上次输入 -> 先 Ctrl+A 清空再输入。"""
    pyautogui.hotkey('ctrl', 'g')
    time.sleep(0.7)
    pyautogui.hotkey('ctrl', 'a')
    time.sleep(0.25)
    pyautogui.typewrite(str(line), interval=0.06)
    time.sleep(0.4)
    pyautogui.keyDown('return'); pyautogui.keyUp('return')
    time.sleep(wait)
    if verify_shot:
        shot(verify_shot, SB)


def force_en_us():
    """Alt+Shift 会触 Windows 输入法切换；输入前强制回 en-US，避免 typewrite 被 IME 吃掉。"""
    try:
        hkl = ctypes.windll.user32.LoadKeyboardLayoutW('00000409', 1)
        ctypes.windll.user32.ActivateKeyboardLayout(hkl, 0)
    except Exception:
        pass


def trigger_preview(wait=3.5, use_find_action=False):
    """打开 SQL 预览。默认 Alt+Shift+Q；use_find_action=True 走 Ctrl+Shift+A（文本输入更稳）。"""
    force_en_us()
    if use_find_action:
        pyautogui.hotkey('ctrl', 'shift', 'a')
        time.sleep(0.9)
        pyautogui.typewrite('Convert to SQL', interval=0.05)
        time.sleep(0.9)
        pyautogui.keyDown('return'); pyautogui.keyUp('return')
    else:
        pyautogui.hotkey('alt', 'shift', 'q')
    time.sleep(wait)


def goto_token(line, words=1):
    """跳行并把光标落到**首个 token** 上。

    插件 WrapperLocator 要求光标在 token 上；落行首缩进（col 1）会 anchor=null
    只弹「未在光标处找到可预览的 SQL 源」通知。Ctrl+Right 从缩进跳到首 token 起点。
    """
    goto(line)
    pyautogui.keyDown('home'); pyautogui.keyUp('home'); time.sleep(0.2)
    for _ in range(words):
        pyautogui.keyDown('ctrl'); pyautogui.keyDown('right')
        pyautogui.keyUp('right'); pyautogui.keyUp('ctrl')
        time.sleep(0.15)
    time.sleep(0.3)


def goto_col(line, col, wait=1.0):
    """跳行并把光标精确移到第 col 列（1 基）——落在指定 token 上。

    比 goto_token 可靠：列号由源码文本算出，不受缩进/词长影响。
    """
    goto(line)
    # IntelliJ 的 Home 是「智能 Home」：第一下到首个非空白字符，第二下才到绝对行首。
    # 不按两下的话列号基准会随缩进漂（8 空格缩进实测整体 +8 列）。
    for _ in range(2):
        pyautogui.keyDown('home'); pyautogui.keyUp('home'); time.sleep(0.12)
    for _ in range(max(0, col - 1)):
        pyautogui.keyDown('right'); pyautogui.keyUp('right')
    time.sleep(wait)


def close_preview(cancel_xy=(1485, 828), wait=1.4, body_xy=None):
    """关预览浮窗：优先点「取消」按钮。

    浮窗尺寸随内容变（带「参数列」面板的 INSERT 预览更宽），取消键位会漂；传 body_xy
    则改为「点进浮窗体内 → Esc」——浮窗拿到焦点时 Esc 才关得掉（在编辑器里按 Esc 会去动
    工具窗），这条实测更稳。
    """
    if body_xy:
        pyautogui.click(*body_xy); time.sleep(0.4)
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc')
    else:
        pyautogui.click(*cancel_xy)
    time.sleep(wait)


def goto_text(text, wait=1.0):
    """用 Ctrl+F 查找唯一片段把光标落到该 token 上。

    比 goto_col 稳：不依赖行号，也不依赖对话框时序（`goto` 偶尔会落空——本轮实测
    文件刚打开时 `goto_col(55,27)` 把光标留在了 34:6）。查找串必须是文件内唯一的。
    """
    pyautogui.hotkey('ctrl', 'f')
    time.sleep(0.7)
    pyautogui.hotkey('ctrl', 'a')
    time.sleep(0.2)
    force_en_us()
    pyautogui.typewrite(text, interval=0.04)
    time.sleep(0.8)
    pyautogui.keyDown('return'); pyautogui.keyUp('return')
    time.sleep(0.5)
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc')   # 关查找条，焦点回编辑器
    time.sleep(0.3)
    # 查找命中后片段是**选中**状态，而插件的预览会走「选中文本里找 SQL」那条路 → 失败（实测弹
    # 「未在光标处找到可预览的 SQL 源」）。按一下 Left 把选中塌掉，光标落到命中片段起点＝token 上。
    pyautogui.keyDown('left'); pyautogui.keyUp('left')
    time.sleep(wait)
