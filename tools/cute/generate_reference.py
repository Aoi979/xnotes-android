#!/usr/bin/env python3
"""Generate full-domain golden mappings with the user's host CuTe. One small TU at a time."""
import argparse
import json
import os
from pathlib import Path
import re
import resource
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--cutlass', type=Path, required=True)
p.add_argument('--cuda', type=Path, default=Path('/opt/cuda'))
p.add_argument('--cxx', default='g++')
args = p.parse_args()

# Only expression syntax is translated here; every algebra operation and mapping is CuTe itself.
def operand(text, layout=False):
    tokens = re.findall(r'tile|_?\d+|_|[(),:]', text)
    at = 0
    def parse():
        nonlocal at
        if tokens[at] == 'tile': at += 1
        if tokens[at] == '(':
            at += 1
            node = [parse()]
            while tokens[at] == ',':
                at += 1
                node.append(parse())
            assert tokens[at] == ')'
            at += 1
        elif tokens[at] == '_':
            node = '_'
            at += 1
        else:
            node = int(tokens[at].lstrip('_'))
            at += 1
        if at < len(tokens) and tokens[at] == ':':
            at += 1
            node = ('layout', node, parse())
        return node
    def cpp(node):
        if isinstance(node, int): return f'Int<{node}>{{}}'
        if node == '_': return '_'
        if isinstance(node, tuple): return f'make_layout({cpp(node[1])},{cpp(node[2])})'
        return 'make_tuple(' + ','.join(map(cpp, node)) + ')'
    n = parse()
    assert at == len(tokens)
    expr = cpp(n)
    if layout and not isinstance(n, tuple): expr = 'make_layout(' + expr + ')'
    return expr

cases = []
def add(op, a, b=''):
    cases.append(dict(op=op, a=a, b=b))
base = ['1:0', '1:7', '4:0', '(2,3):(1,2)', '(2,3):(3,1)',
        '(2,(1,6)):(1,(6,2))', '((2,2),(2,3)):((4,1),(2,8))',
        '(2,3):(4,12)', '(4,1):(1,0)', '(2,1,2):(1,9,2)', '(4,3):(0,1)',
        '((2,2),2):((1,4),8)']
for a in base:
    for op in ['coalesce', 'filter', 'right_inverse', 'left_inverse']:
        add(op, a)
    add('complement', a, '64')
    add('complement', a)
for a,b in [('(6,2):(8,2)','(4,3):(3,1)'), ('20:2','(5,4):(4,1)'),
            ('(4,1):(1,0)','8:1'), ('(2,3):(3,1)','2:0'),
            ('((4,2),8):((1,4),8)', 'tile(4,2)'), ('(2,3):(3,1)','1:1'),
            ('(3,6,2,8):(1,3,18,36)','16:9')]:
    add('composition',a,b)
for a,b in [('(8,8):(8,1)','tile(4,2)'), ('(8,8,2):(8,1,64)','tile(4,2)'),
            ('(4,2,3):(2,1,8)','4:2'), ('((8,4),4):((1,8),32)','tile(tile(2,2),2)'),
            ('32:1', '4'), ('(5,4):(4,1)', 'tile(2,2)')]:
    for group in ['logical','zipped','tiled','flat']:
        add(group+'_divide',a,b)
for a,b in [('(2,2):(4,1)','6:1'), ('(2,2):(4,1)','(4,2):(2,1)'),
            ('(2,3):(3,1)','tile(2,2)'), ('(2,3,2):(3,1,6)','tile(2,2)'),
            ('4:0','3:1'), ('((2,2),2):((1,2),4)','tile(tile(2,2),2)')]:
    for group in ['logical','zipped','tiled','flat']:
        add(group+'_product',a,b)
for a,b in [('(2,5):(5,1)','(3,4):(1,3)'), ('(2,2):(1,2)','(4,2):(2,1)'), ('4:1','(2,3):(1,2)')]:
    for op in ['blocked_product','raked_product']: add(op,a,b)
add('coalesce', '(2,(1,6)):(1,(6,2))', '(1,1)')
add('coalesce', '((2,2),(1,4)):((1,2),(99,4))', '((1,1),1)')
for a in ['((4,8),(2,2)):((32,1),(16,8))', '((4,8),(2,2,2)):((32,1),(16,8,128))', '((4,8),(2,2)):((16,1),(8,64))']:
    add('make_layout', a)

for a,b in [('4:2','(5,7)'), ('(2,3):(4,12)','(5,7)'), ('4:0','(5,7)')]:
    add('complement',a,b)

# Bound compiler address space and never compile batches concurrently.
def limit():
    resource.setrlimit(resource.RLIMIT_AS, (2 * 1024**3, 2 * 1024**3))

def expression(c):
    a = operand(c['a'], layout=True)
    if c['op'] == 'make_layout': return a
    b = ''
    if c['b']:
        b = ',' + operand(c['b'], layout=c['op'] in ['blocked_product','raked_product'])
    return f"{c['op']}({a}{b})"

sha = subprocess.check_output(['git','-C',str(args.cutlass),'rev-parse','HEAD'], text=True).strip()
rows = []
with tempfile.TemporaryDirectory(prefix='xnotes-cute-oracle-') as tmp:
    tmp = Path(tmp)
    for start in range(0,len(cases),12):
        chunk = cases[start:start+12]
        source = ['#include <cute/layout.hpp>', '#include <cute/util/print.hpp>', '#include <iostream>', 'using namespace cute;', 'int main(){']
        for i,c in enumerate(chunk,start):
            source.append('{ auto r = '+expression(c)+'; std::cout << "'+str(i)+'\\t"; print(r); std::cout << "\\t"; for(int j=0;j<size(r);++j){if(j)std::cout<<",";std::cout<<static_cast<long long>(r(j));} std::cout<<"\\n"; }')
        source.append('}')
        (tmp/'oracle.cpp').write_text('\n'.join(source))
        command = [args.cxx,'-std=c++17','-O0', '-I'+str(args.cutlass/'include'), '-I'+str(args.cuda/'include'), '-I'+str(args.cuda/'include/cccl'), '-I/usr/include/cccl', str(tmp/'oracle.cpp'), '-o',str(tmp/'oracle')]
        proc = subprocess.run(command, text=True, capture_output=True, preexec_fn=limit)
        if proc.returncode:
            print(proc.stderr[-12000:])
            raise SystemExit(f'CuTe compiler rejected batch {start}; fixtures must be corrected, never substituted.')
        output = subprocess.check_output([str(tmp/'oracle')],text=True)
        for line in output.splitlines():
            i, layout, mapping = line.split('\t')
            c = cases[int(i)]
            rows.append('\t'.join([c['op'],c['a'],c['b'],layout.replace('_',''),mapping]))
        print(f'CuTe reference: {min(start+12,len(cases))}/{len(cases)}',flush=True)

out = ROOT/'app/src/test/resources/cute/reference.tsv'
out.write_text('# NVIDIA CUTLASS '+sha+'\n# op\ta\tb\tCuTe result\tCuTe full-domain offsets\n'+'\n'.join(rows)+'\n')
print(f'{len(rows)} verified CuTe fixtures -> {out}')
