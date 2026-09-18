#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""隐藏不可达 / 静默失效 四轴审计（T-ReachAudit T-RA-07 沉淀）。

用法:
  python dev-support/audit_reachability.py [仓库根] [--axis enum|init|field|topic] [--top N]

默认四轴全跑，输出人读报告到 stdout；**恒以 0 退出，不参与 CI/测试门禁**——
门禁型守卫是 src/test 下的 DialogWrapperInitOrderGuardTest / EnumReachabilityGuardTest，
它们只覆盖前两轴的「确定违规」子集（误报会阻断构建）；本工具是随时可跑的全仓报告。
耗时约 3 秒（297 个 .kt / 310 万字符）。输出用当前控制台编码（Windows GBK 控制台直接运行可读；
重定向到文件时按控制台编码写出，需要 UTF-8 用 `python -X utf8 ...`）。

四轴判据（与守卫同源；审计方法与已排除项见
docs/开发计划/20260918-1604-T-ReachAudit-隐藏不可达与静默失效审计-开发计划.md §一）:

  1. enum  枚举常量可达性（扫描面 src/main，剥离注释后统计每个 `Enum.CONST` 引用的位置）:
     - 消费点 = 被比较（`==` `!=` `<=` `>=`）或用作 when 模式（后随 `->`）；
     - 产出点 = 赋值 / return / when 结果位 / 实参 / 映射值（`to`）/ if-else 分支值 / 块末表达式。
     违规 A（死分支，T-ConsentTruth 形态）：有消费点、零产出点 → 该状态恒不可达；
     违规 B（死常量，F6 `DiffType.CHANGE` 形态）：零产出且零消费（声明后全仓无引用）；
     信息级：枚举类型本身在 src/main 零引用（如 KDoc 明示预留位 `CommentSource`）——
     无消费者即不可能静默失效，只报不计违规。

  2. init  初始化顺序（T-CT-07 形态）：类级 `init {}` 块 → 块内直接调用的同类方法体 →
     引用了「声明在该 init 块之后」的属性，构造期读到的是 JVM 默认值而非声明初值。
     两个豁免维度（属判据本身，不是白名单）：
     - 声明初值与 JVM 默认值等值（false / 0 / null / "" 等）→ 构造期读到的就是声明初值，
       无行为差异（`SqlLogPanel.historyPanelRegistered = false` 即此类）；
     - 构造期范围内该属性先被赋值后被读（`lateinit var modeButton` 在 `toolbar()` 里先赋值即此类）。
     构造期范围**不含 init 块内的嵌套 fun 体**——那是对象/回调的方法体，执行时机在构造之后
     （三个 `*SqlCompletionContributor` 的 `TAG_NAME_AT_CARET` 等即此类）。

  3. field 只写不读的类级字段（F2 死 UI / F3 诊断计数 / F4 残留态形态）。
     写 = 纯赋值 `name = `（排除 `==` 等比较）与语句位自增（`name++`）；
     读 = 其余一切引用，含字符串插值 `${name}` 与表达式位 `name++`（都先读后写）。
     分四档报：零读 / 读点只在其他字段初值里（对象图无出口）/ 只出现在 `name.foo(` 形态里
     （经 API 消费或变更，值出口静态不可判，如 `atomicCounter.incrementAndGet()`）/ 仅测试读。

  4. topic 事件 Topic 缺发布或缺订阅（本轴历史零命中，保留为回潮探针）。

与守卫的口径差异（刻意为之）: 本工具**不套用**守卫的白名单，故轴 1 会列出
`SubmitPhase.SUCCESS`（守卫白名单条目，理由 = KDoc 明示「成功即关窗，不需要写状态行」）；
工具是报告，白名单只属于门禁。

两类扫描器误报的规避（T-ReachAudit 实证，勿回退）:
  ① 扫源码前必须**剥离注释**——否则 KDoc 里的大写词会被当枚举常量
     （`model/BatchModel.kt` 的 VALUES/CONFLICT/KEY 实为文档词），
     `[CommentSource.SCHEMA]` 这类 KDoc 交叉引用也会被当成「消费」；
  ② 读写判定必须认字符串插值 `${...}` 与 `++`/`--`/`+=`（读+写）——否则
     `"c${toggleSeq++}"`、`getUniqueValue() = uniqueValue++` 会被误报成「只写不读」。

已知漏报面（静态文本扫描的边界，勿当缺陷）:
  - 枚举常量一律按 `EnumName.CONST` 限定形态统计——`import` 后裸用常量名的形态无法静态归属；
  - 反射/`values()`/`valueOf()` 驱动的产出与消费不可见；
  - 无符号表，故同名标识符跨类型不区分（本仓约定：类级字段名唯一度足够高）。
"""
import argparse
import bisect
import re
import sys
from pathlib import Path

# 与 JVM 默认值等值的字面初值：构造期读到它与读到声明初值无行为差异
DEFAULTS = {
    "false", "0", "0L", "0l", "0.0", "0f", "0F", "0.0f", "null", '""',
    "emptyList()", "emptyMap()", "emptySet()", "emptyArray()",
    "listOf()", "mapOf()", "setOf()", "arrayOf()", "Unit",
}
KOTLIN_KEYWORDS = {
    "if", "for", "while", "when", "return", "catch", "synchronized", "require", "check", "error",
    "listOf", "arrayOf", "setOf", "mapOf", "mutableListOf", "also", "let", "run", "apply", "with",
    "use", "repeat", "invokeLater", "invokeAndWait",
}
IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def strip_comments(text):
    """剥离 /* */ 与 // 注释（保留换行以维持行号；行注释截断字符串内 // 只会漏报不会误报）。"""
    no_block = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text, flags=re.S)
    return "\n".join(l[:l.find("//")] if "//" in l else l for l in no_block.split("\n"))


def strip_strings(text):
    """仅用于结构分析（大括号配平）：字符串字面量置空，避免字面量里的 {} 干扰深度。"""
    text = re.sub(r'""".*?"""', '""', text, flags=re.S)
    return re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)


class Repo:
    """源码装载 + 单遍标识符索引（全仓扫描的性能支点：避免每字段一次全量 regex）。"""

    def __init__(self, root):
        self.root = Path(root)
        self.files = []
        for f in sorted((self.root / "src" / "main" / "kotlin").rglob("*.kt")):
            self.files.append((f.relative_to(self.root).as_posix(), strip_comments(f.read_text(encoding="utf-8"))))
        self.all_code = "\n".join(c for _, c in self.files)
        self.line_starts = [0]
        for i, ch in enumerate(self.all_code):
            if ch == "\n":
                self.line_starts.append(i + 1)
        test_dir = self.root / "src" / "test" / "kotlin"
        self.test_code = "\n".join(
            strip_comments(f.read_text(encoding="utf-8")) for f in sorted(test_dir.rglob("*.kt"))
        ) if test_dir.is_dir() else ""

    def line_of(self, pos):
        return bisect.bisect_right(self.line_starts, pos)

    def index(self, wanted):
        """一次遍历给出 wanted 集合中每个名字在 src/main 与 src/test 的出现位置。"""
        main, test = {}, {}
        for mm in IDENT.finditer(self.all_code):
            n = mm.group(0)
            if n in wanted:
                main.setdefault(n, []).append(mm.start())
        for mm in IDENT.finditer(self.test_code):
            n = mm.group(0)
            if n in wanted:
                test[n] = test.get(n, 0) + 1
        return main, test


# ---------------------------------------------------------------- 轴 1：枚举

ENUM_DEF = re.compile(r"\benum\s+class\s+(\w+)")


def match_brace(text, open_idx):
    depth, i = 0, open_idx
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return len(text)


def enum_constants(body):
    """body = 枚举大括号内文本；截到顶层 `;`（常量段），逐项取末个标识符为常量名。"""
    depth, cut = 0, len(body)
    for i, c in enumerate(body):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == ";" and depth == 0:
            cut = i
            break
    out, depth = [], 0
    for c in body[:cut]:
        if c in "({":
            depth += 1
            continue
        if c in ")}":
            depth -= 1
            continue
        if depth == 0:
            out.append(c)
    consts = []
    for chunk in "".join(out).split(","):
        found = re.findall(r"[A-Za-z_][A-Za-z0-9_]*", re.sub(r"@\w+", "", chunk))
        if found and found[-1] not in consts:
            consts.append(found[-1])
    return consts


def enum_decls(repo):
    """枚举声明清单：文件 / 行号 / 名 / 常量表。"""
    decls = []
    for rel, code in repo.files:
        for m in ENUM_DEF.finditer(code):
            brace = code.find("{", m.end())
            if brace < 0:
                continue
            decls.append(dict(file=rel, line=code[:m.start()].count("\n") + 1,
                              enum=m.group(1), consts=enum_constants(code[brace + 1:match_brace(code, brace) - 1])))
    return decls


def occurrence_kind(code, start, end):
    """按引用点上下文判定 产出（prod）/ 消费（cons）；无法判定按消费计（保守）。"""
    i = start
    while i > 0 and (code[i - 1].isalnum() or code[i - 1] in "_.]"):
        i -= 1
    before = code[max(0, i - 40):i].rstrip()
    after = code[end:end + 40]
    if re.match(r"\s*->", after) or re.search(r"(===|!==|==|!=|<=|>=)\s*$", before) or re.search(r"when\s*\(\s*$", before):
        return "cons"
    if re.search(r"(=|return|->|\(|,|\)|\bto|\bin|\bby|\belse|\?:|\+=|&&|\|\|)\s*$", before):
        return "prod"
    if re.match(r"\s*[}\);,]", after) or re.match(r"\s*(to|in)\b", after):
        return "prod"
    return "cons"


def axis_enum(repo):
    decls = enum_decls(repo)
    wanted = {d["enum"] for d in decls} | {c for d in decls for c in d["consts"]}
    main, _ = repo.index(wanted)
    rows = []
    for d in decls:
        enum = d["enum"]
        # 活跃 = 排除「enum class X」声明位后，全仓仍有 X 的代码引用
        live = False
        for pos in main.get(enum, []):
            if not re.search(r"\bclass\s+$", repo.all_code[max(0, pos - 12):pos]):
                live = True
                break
        for const in d["consts"]:
            prod, cons, where = 0, 0, []
            for pos in main.get(enum, []):
                end = pos + len(enum)
                if repo.all_code[end:end + 1] != ".":
                    continue
                mm = IDENT.match(repo.all_code, end + 1)
                if not mm or mm.group(0) != const:
                    continue
                if occurrence_kind(repo.all_code, pos, mm.end()) == "prod":
                    prod += 1
                else:
                    cons += 1
                    where.append(repo.line_of(pos))
            rows.append(dict(file=d["file"], line=d["line"], enum=enum, const=const,
                             prod=prod, cons=cons, live=live, where=where))
    return rows


# ---------------------------------------------------------------- 轴 2：init 顺序

DECL = re.compile(r"^( *)(?:(?:private|internal|protected|public|open|override|lateinit|const|@\w+(?:\([^)]*\))?)\s+)*(val|var)\s+(\w+)")
INIT_BLOCK = re.compile(r"^( *)init\s*\{")
FUN_DEF = re.compile(
    r"^( *)(?:(?:private|internal|protected|public|open|override|suspend|inline|tailrec|operator|abstract)\s+)*"
    r"fun\s+(?:<[^>]+>\s+)?(\w+)\s*\("
)
PLAIN_WRITE = re.compile(r"^[A-Za-z_]\w*\s*=(?!=)")


def line_depths(lines):
    out, d = [], 0
    for l in lines:
        out.append(d)
        d += l.count("{") - l.count("}")
    return out


def block_end(lines, dep, start):
    d, i = dep[start], start + 1
    while i < len(lines) and dep[i] > d:
        i += 1
    return i


def axis_init(repo):
    hits, stats = [], dict(init_blocks=0, candidates=0, exempt_default=0, exempt_written=0, classes=0)
    for rel, raw in repo.files:
        code = strip_strings(raw)
        lines = code.split("\n")
        dep = line_depths(lines)
        for idx, line in enumerate(lines):
            if not INIT_BLOCK.match(line):
                continue
            stats["init_blocks"] += 1
            d = dep[idx]
            end = block_end(lines, dep, idx)
            # 构造期范围 = init 块去掉嵌套 fun 体（那是对象/回调方法，构造后才执行）
            skip = set()
            for i in range(idx + 1, end):
                fm = FUN_DEF.match(lines[i])
                if fm:
                    skip.update(range(i, block_end(lines, dep, i)))
            scope = "\n".join(lines[i] for i in range(idx + 1, end) if i not in skip)
            names = {mm.group(1) for mm in re.finditer(r"(?:[\w.]+\s*\.\s*)?(\w+)\s*\(", scope)
                     if mm.group(1) not in KOTLIN_KEYWORDS}
            if "init" in names:
                names.add("createActions")  # 平台契约：DialogWrapper.init() 内部回调 createActions()
            decls, i = {}, end
            while i < len(lines) and dep[i] >= d:
                dm = DECL.match(lines[i])
                if dm and dep[i] == d:
                    decls.setdefault(dm.group(3), i)
                i += 1
            if not decls:
                continue
            bodies = {}
            for i in range(len(lines)):
                fm = FUN_DEF.match(lines[i])
                if fm and fm.group(2) in names and dep[i] >= d:
                    bodies[fm.group(2)] = "\n".join(lines[i:block_end(lines, dep, i)])
            if not bodies:
                continue
            stats["classes"] += 1
            for name, dline in decls.items():
                users = [k for k, b in bodies.items() if re.search(r"\b%s\b" % re.escape(name), b)]
                if not users:
                    continue
                stats["candidates"] += 1
                dl = lines[dline]
                init_m = re.search(r"(?<![=!<>+\-*/])=(?!=)", dl)
                init_val = dl[init_m.end():].strip() if init_m else "<无初值>"
                # 读点 = 该引用点之前（同一方法体内）没有纯赋值
                read_users = []
                for k in users:
                    b, first_write = bodies[k], None
                    for mm in re.finditer(r"\b%s\b" % re.escape(name), b):
                        if PLAIN_WRITE.match(b[mm.start():]):
                            first_write = mm.start() if first_write is None else first_write
                            continue
                        if first_write is None:
                            read_users.append(k)
                            break
                if not read_users:
                    stats["exempt_written"] += 1
                    continue
                lateinit = "lateinit" in dl
                delegated = re.search(r"\bby\s+\w", dl) is not None
                if not lateinit and not delegated and (init_m is None or init_val in DEFAULTS):
                    # 无初值（自定义访问器，无背后字段）或 初值 = JVM 默认值
                    stats["exempt_default"] += 1
                    continue
                hits.append(dict(file=rel, line=dline + 1, name=name, init=init_val[:60],
                                 users=read_users, lateinit=lateinit))
    return hits, stats


# ---------------------------------------------------------------- 轴 3：只写不读字段

PLAIN_ASSIGN = re.compile(r"[A-Za-z_]\w*\s*=(?!=)")
UPDATE_EXPR = re.compile(r"[A-Za-z_]\w*\s*(\+\+|--|\+=|-=|\*=|/=)")
API_CALL = re.compile(r"\.\s*[A-Za-z_]\w*\s*\(")
STMT_END = re.compile(r"^[ \t]*[;}]?[ \t]*$")


def update_is_statement(code, pos):
    """`x++` / `x += e` 形态：更新表达式之后若即语句收尾（行末/`;`/`}`），则只写不读。"""
    m = UPDATE_EXPR.match(code, pos)
    if not m:
        return False
    rest_start = m.end() if m.group(1) in ("++", "--") else code.find("\n", m.end())
    if rest_start < 0:
        return True
    return STMT_END.match(code[rest_start:rest_start + 200].split("\n", 1)[0]) is not None


def in_interpolation(code, pos):
    """引用点是否在字符串插值 `${...}` 内（插值即读）。"""
    line_start = code.rfind("\n", 0, pos) + 1
    seg = code[line_start:pos]
    return seg.rfind("${") > seg.rfind("}")


def axis_field(repo):
    decls = []
    for rel, code in repo.files:
        for i, line in enumerate(code.split("\n")):
            dm = DECL.match(line)
            if dm and len(dm.group(1)) == 4:  # 类级（非局部变量、非嵌套类成员）
                decls.append((rel, i + 1, dm.group(3)))
    main, test = repo.index({n for _, _, n in decls})
    zero, decl_only, test_only, api_only = [], [], [], []
    for rel, line, name in decls:
        writes, reads, decl_reads, api_calls = 0, 0, 0, 0
        for pos in main.get(name, []):
            occ_line_start = repo.all_code.rfind("\n", 0, pos) + 1
            occ_line_end = repo.all_code.find("\n", pos)
            occ_line = repo.all_code[occ_line_start:occ_line_end if occ_line_end > 0 else len(repo.all_code)]
            dm = DECL.match(occ_line)
            decl_indent4 = dm is not None and len(dm.group(1)) == 4
            bare = repo.all_code[pos - 1:pos] != "."
            # 声明位自身（含带类型标注的 `var x: T = v`——PLAIN_ASSIGN 认不出中间的 `: T`）
            if decl_indent4 and dm.group(3) == name and bare:
                writes += 1
                continue
            if PLAIN_ASSIGN.match(repo.all_code, pos):
                writes += 1
                continue
            if UPDATE_EXPR.match(repo.all_code, pos):
                before = repo.all_code[occ_line_start:pos].rstrip()
                if in_interpolation(repo.all_code, pos) or before.endswith("=") or before.endswith("return"):
                    reads += 1
                elif update_is_statement(repo.all_code, pos):
                    writes += 1
                else:
                    reads += 1
                continue
            if API_CALL.match(repo.all_code, pos + len(name)):
                api_calls += 1  # `name.foo(...)`：经 API 消费/变更，值出口静态不可判（单列）
                continue
            # 「只出现在同类其他字段初值里」的引用单列：对象图本身无出口（限定名不算——
            # `Foo.NAME` 指向别处声明；局部 val/var（8 空格）也不是字段声明）
            if bare and decl_indent4:
                decl_reads += 1
            else:
                reads += 1
        if reads == 0:
            row = dict(file=rel, line=line, name=name, writes=writes, decl_reads=decl_reads,
                       api_calls=api_calls, in_test=test.get(name, 0) > 0)
            if api_calls > 0:
                api_only.append(row)
            elif row["in_test"]:
                test_only.append(row)
            elif decl_reads > 0:
                decl_only.append(row)
            else:
                zero.append(row)
    return zero, decl_only, test_only, api_only


# ---------------------------------------------------------------- 轴 4：Topic

TOPIC_DECL = re.compile(r"\b(?:val|const\s+val)\s+([A-Z][A-Z0-9_]*_TOPIC)\b")


def axis_topic(repo):
    decls = []
    for rel, code in repo.files:
        for m in TOPIC_DECL.finditer(code):
            decls.append((rel, code[:m.start()].count("\n") + 1, m.group(1)))
    main, _ = repo.index({t for _, _, t in decls})
    rows = []
    for rel, line, topic in decls:
        pub = sub = 0
        for pos in main.get(topic, []):
            end = pos + len(topic)
            if re.search(r"\bclass\s+$", repo.all_code[max(0, pos - 12):pos]):
                continue  # 声明位
            window = repo.all_code[max(0, pos - 60):end + 80]
            if re.search(r"(notify|syncPublisher|asyncPublisher)", window):
                pub += 1
            if re.search(r"subscribe\s*\([^)]*$", repo.all_code[max(0, pos - 200):pos]) or re.search(r"\bsubscribe\s*\(", window):
                sub += 1
        rows.append(dict(file=rel, line=line, topic=topic, pub=pub, sub=sub))
    return rows


# ---------------------------------------------------------------- 报告

def report(repo, axes, top):
    out = []
    if "enum" in axes:
        rows = axis_enum(repo)
        dead_branch = [r for r in rows if r["prod"] == 0 and r["cons"] > 0 and r["live"]]
        dead_const = [r for r in rows if r["prod"] == 0 and r["cons"] == 0 and r["live"]]
        reserved = [r for r in rows if not r["live"]]
        out.append("[轴 1 枚举可达性] 枚举 %d / 常量 %d" % (len({(r["file"], r["line"]) for r in rows}), len(rows)))
        out.append("  违规 A 死分支（有消费、零产出；T-ConsentTruth 形态）: %d" % len(dead_branch))
        for r in dead_branch:
            out.append("    %s:%d %s.%s（消费点行 %s）" % (r["file"], r["line"], r["enum"], r["const"], r["where"][:top]))
        out.append("  违规 B 死常量（零引用；F6 DiffType.CHANGE 形态）: %d" % len(dead_const))
        for r in dead_const:
            out.append("    %s:%d %s.%s" % (r["file"], r["line"], r["enum"], r["const"]))
        out.append("  信息级 未接线/预留类型（类型本身在 src/main 零引用，无消费者故不会静默失效）: %d" % len(reserved))
        for r in reserved[:top]:
            out.append("    %s:%d %s.%s" % (r["file"], r["line"], r["enum"], r["const"]))
    if "init" in axes:
        hits, s = axis_init(repo)
        out.append("[轴 2 初始化顺序] init 块 %d / 被引用候选 %d / 豁免（初值=JVM 默认值）%d / 豁免（构造期先写后读）%d"
                   % (s["init_blocks"], s["candidates"], s["exempt_default"], s["exempt_written"]))
        out.append("  违规（构造期读到 JVM 默认值而非声明初值）: %d" % len(hits))
        for h in hits:
            out.append("    %s:%d %s = %s（经 %s 在 init 期被读）"
                       % (h["file"], h["line"], h["name"], h["init"], ",".join(h["users"])))
    if "field" in axes:
        zero, decl_only, test_only, api_only = axis_field(repo)
        out.append("[轴 3 只写不读字段] 零读（全仓无读点）%d / 仅被其他字段初值引用 %d / 仅经方法调用 %d / 仅测试读 %d"
                   % (len(zero), len(decl_only), len(api_only), len(test_only)))
        for r in zero:
            out.append("    %s:%d %s（写 %d 次，全仓零读）" % (r["file"], r["line"], r["name"], r["writes"]))
        for r in decl_only:
            out.append("    %s:%d %s（写 %d 次，读点只在其他字段初值里——对象图本身无出口）"
                       % (r["file"], r["line"], r["name"], r["writes"]))
        for r in api_only:
            out.append("    %s:%d %s（写 %d 次，只出现在 `%s.foo(` 形态里——值出口需人工判定%s）"
                       % (r["file"], r["line"], r["name"], r["writes"], r["name"],
                          "；测试亦引用" if r["in_test"] else ""))
        for r in test_only:
            out.append("    %s:%d %s（写 %d 次，仅测试读）" % (r["file"], r["line"], r["name"], r["writes"]))
    if "topic" in axes:
        rows = axis_topic(repo)
        bad = [r for r in rows if r["pub"] == 0 or r["sub"] == 0]
        out.append("[轴 4 Topic 发布/订阅] Topic %d / 缺一侧 %d" % (len(rows), len(bad)))
        for r in bad:
            out.append("    %s:%d %s（发布 %d / 订阅 %d）" % (r["file"], r["line"], r["topic"], r["pub"], r["sub"]))
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser(description="隐藏不可达 / 静默失效四轴审计（只读、报告型、恒 0 退出）")
    ap.add_argument("root", nargs="?", default=".", help="仓库根（含 src/main/kotlin），默认当前目录")
    ap.add_argument("--axis", action="append", choices=["enum", "init", "field", "topic"], help="只跑指定轴，可重复")
    ap.add_argument("--top", type=int, default=40, help="每节最多列多少条（默认 40）")
    args = ap.parse_args()
    repo = Repo(args.root)
    print("审计面：%s（src/main/kotlin %d 个 .kt，%.1f 万字符）"
          % (repo.root.resolve(), len(repo.files), len(repo.all_code) / 10000))
    print(report(repo, args.axis or ["enum", "init", "field", "topic"], args.top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
