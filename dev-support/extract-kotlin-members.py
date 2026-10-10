#!/usr/bin/env python3
"""把 Kotlin 类的一组内聚成员抽成**同包扩展函数**，从而把巨型单类文件拆小。

T-M53-II（巨型文件拆分第二轮）D2 用。为什么用扩展函数：
  扩展函数体内的 `field` / `other()` 会隐式解析到接收者，**方法体一字不用改**，
  原类内的调用点也不变（成员被搬走后自动落到同包扩展）。唯一代价是：被触及的
  `private` 成员必须放宽为 `internal`（脚本自动扫描并放宽）。

用法：
  python dev-support/extract-kotlin-members.py --inspect <file> [--class <名>]
  python dev-support/extract-kotlin-members.py --apply <file> --class <名> \
      --members a,b,c --new-file <新文件> --doc "一句话说明"

成员按「序号」指定（--inspect 输出）。只支持搬 `fun`（扩展函数不能承载属性）。
"""
import argparse
import re
import sys

_MODS = (r'(private|internal|protected|public|override|open|abstract|inline|suspend|operator|'
         r'tailrec|vararg|const|lateinit|inner|data|sealed|enum|annotation|external|value|@)')
DECL = re.compile(r'^ {4}(' + _MODS + r'\s*)*(fun|val|var|class|object|interface|companion|init)\b')


def read(path):
    text = open(path, 'rb').read().decode('utf-8')
    nl = '\r\n' if '\r\n' in text else '\n'
    return text.split(nl), nl, text.endswith(nl)


def class_span(lines, cls):
    ci = next(i for i, l in enumerate(lines) if re.match(r'^[\w ]*class ' + re.escape(cls) + r'\b', l))
    cs = next(i for i in range(ci, len(lines)) if '{' in lines[i])
    d = 0
    for i in range(cs, len(lines)):
        d += lines[i].count('{') - lines[i].count('}')
        if d == 0:
            return cs, i
    raise SystemExit('类体未闭合')


def members(lines, cs, ce):
    out = []
    for i in range(cs + 1, ce):
        if DECL.match(lines[i]):
            out.append(i)
    rows = []
    for k, dd in enumerate(out):
        e = out[k + 1] if k + 1 < len(out) else ce
        nm = None
        for j in range(dd, min(e, dd + 6)):
            m = re.search(r'\bfun\s+`([^`]+)`|\bfun\s+([A-Za-z_]\w*)', lines[j])
            if m:
                nm = m.group(1) or m.group(2)
                break
        if nm is None:
            for j in range(dd, min(e, dd + 6)):
                m = re.search(r'\b(?:val|var|class|object|interface)\s+([A-Za-z_]\w*)', lines[j])
                if m:
                    nm = m.group(1)
                    break
        rows.append({'k': k, 'lo': dd, 'hi': e, 'name': nm or '?', 'line': dd + 1,
                     'size': e - dd, 'decl': lines[dd].strip()})
    return rows


def inspect(path, cls):
    lines, nl, tr = read(path)
    cs, ce = class_span(lines, cls)
    print(f'file={path}  ({len(lines)} 行)  class={cls}  类体 {cs + 1}..{ce + 1} ({ce - cs + 1} 行)')
    for r in sorted(members(lines, cs, ce), key=lambda x: -x['size'])[:40]:
        print(f"  [{r['k']:3d}] {r['line']:5d} {r['size']:5d}行  {r['name'][:38]:40s} {r['decl'][:46]}")


def apply(path, cls, idxs, new_file, doc):
    lines, nl, tr = read(path)
    cs, ce = class_span(lines, cls)
    rows = {r['k']: r for r in members(lines, cs, ce)}
    picked = [rows[i] for i in idxs]
    body_text = '\n'.join('\n'.join(lines[r['lo']:r['hi']]) for r in picked)
    # 原类私有成员里，被搬走代码引用到的 → 放宽为 internal
    priv = []
    for r in members(lines, cs, ce):
        if r['name'] in ('?',) or r['k'] in idxs:
            continue
        if not r['decl'].startswith(('private ', 'protected ')):
            continue
        if re.search(r'\b' + re.escape(r['name']) + r'\b', body_text):
            priv.append(r)
    for r in priv:
        src = lines[r['lo']]
        lines[r['lo']] = re.sub(r'^( {4})(private|protected) ', r'\1internal ', src)
    # companion object 等更深缩进的私有成员：按名全文件找声明行放宽
    for i in range(cs, ce):
        if not re.match(r'^ +(private|protected) ', lines[i]):
            continue
        m = re.search(r'\b(?:fun|val|var|const val|class|object)\s+([A-Za-z_]\w*)', lines[i])
        if m and re.search(r'\b' + re.escape(m.group(1)) + r'\b', body_text):
            lines[i] = re.sub(r'^( +)(private|protected) ', r'\1internal ', lines[i])
            priv.append({'name': m.group(1) + '（深层/companion）'})
    # 生成扩展文件
    ext = []
    for r in picked:
        blk = list(lines[r['lo']:r['hi']])
        for i, l in enumerate(blk):
            m = re.match(r'^ {4}(?:(?:private|internal|protected|public|override|open)\s+)*fun\s+', l)
            if m:
                rest = l[m.end():]
                blk[i] = f'internal fun {cls}.' + rest
                for j in range(i + 1, len(blk)):
                    if blk[j].startswith('    '):
                        blk[j] = blk[j][4:]
                break
        ext += blk + ['']
    # 从原文件删除（倒序）
    for r in sorted(picked, key=lambda x: -x['lo']):
        del lines[r['lo']:r['hi']]
    last_imp = max(i for i, l in enumerate(lines) if l.startswith('import '))
    head = lines[:last_imp + 1]
    tail_text = (['', f'// T-M53-II（2026-10-10）：以下为 {cls} 的「{doc}」内聚成员，',
                  '// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。', ''] + ext)
    while tail_text and tail_text[-1].strip() == '':
        tail_text.pop()
    open(path, 'w', encoding='utf-8', newline='').write(nl.join(lines) + nl)
    open(new_file, 'w', encoding='utf-8', newline='').write(nl.join(head + tail_text) + nl)
    print('搬出成员:', [r['name'] for r in picked])
    print('放宽为 internal 的私有成员:', [r['name'] for r in priv])
    print(f'原文件 {len(lines)} 行 | 新文件 {len(head + tail_text)} 行')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--inspect'); ap.add_argument('--apply')
    ap.add_argument('--class', dest='cls', required=True, help='目标类名')
    ap.add_argument('--members', default='')
    ap.add_argument('--new-file'); ap.add_argument('--doc', default='内聚成员')
    a = ap.parse_args()
    if a.inspect:
        inspect(a.inspect, a.cls)
    elif a.apply:
        idxs = [int(x) for x in a.members.split(',') if x.strip()]
        if not idxs or not a.new_file:
            ap.error('--apply 需要 --members 与 --new-file')
        apply(a.apply, a.cls, idxs, a.new_file, a.doc)
    else:
        ap.error('需要 --inspect 或 --apply')


if __name__ == '__main__':
    main()
