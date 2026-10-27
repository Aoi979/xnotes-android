#!/usr/bin/env python3
"""Host CuTe oracle: major orders, composed swizzles, MMA-copy, retile.
TMA rows validate the CuTe byte swizzle only, not execution of a TMA instruction.
Serial translation units; 2 GiB memory cap per compiler.
"""
import argparse
from pathlib import Path
import resource
import subprocess
import tempfile
from generate_hardware_reference import COPY
ROOT=Path(__file__).resolve().parents[2]

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--cutlass',type=Path,required=True)
    parser.add_argument('--cuda',type=Path,default=Path('/opt/cuda'))
    args=parser.parse_args()
    source=(ROOT/'tools/cute/generate_reference.py').read_text()
    scope={}; exec('import re\n'+source[source.index('def operand('):source.index('\ncases = []')],scope)
    op=scope['operand']
    def limit(): resource.setrlimit(resource.RLIMIT_AS,(2*1024**3,2*1024**3))
    def run(body):
        with tempfile.TemporaryDirectory(prefix='cute-advanced-') as d:
            p=Path(d)
            p.joinpath('a.cpp').write_text('#include <cute/tensor.hpp>\n#include <cute/atom/mma_atom.hpp>\n#include <cute/atom/copy_atom.hpp>\n#include <iostream>\nusing namespace cute;\nint main(){'+body+'}\n')
            subprocess.run(['g++','-std=c++17','-O0','-I'+str(args.cutlass/'include'),'-I'+str(args.cuda/'include'),'-I'+str(args.cuda/'include/cccl'),'-I/usr/include/cccl',str(p/'a.cpp'),'-o',str(p/'a')],check=True,preexec_fn=limit)
            return subprocess.check_output([str(p/'a')],text=True).strip().replace('_','')
    rows=[]
    def add(kind,x,y,z,w,body,layout=True):
        printed='print(r);' if layout else 'std::cout<<"mapped";'
        result=run(body+printed+'std::cout<<"\\t";for(int i=0;i<size(r);++i){if(i)std::cout<<",";std::cout<<r(i);}std::cout<<"\\n";')
        rows.append('\t'.join([kind,x,y,z,w,result])); print(kind,x,y,flush=True)
    for shape in ['(2,(1,3),4)','(4,2)','(1,1)','((2,3),(4,2))']:
        for major in ['Left','Right']:
            add('major',shape,major,'','',f'auto r=make_layout({op(shape)},Layout{major}{{}});')
    for params,layout in [('3,3,3,0','(8,64):(64,1)'),('2,3,-2,5','(8,16):(1,8)'),('0,0,0,7','(4,8):(8,1)'),('2,4,3,128','(32,8):(2,64)'),('1,4,3,256','(16,8):(2,32)')]:
        b,m,s,o=params.split(',')
        add('swizzle',params,layout,'','',f'auto r=composition(Swizzle<{b},{m},{s}>{{}},Int<{o}>{{}},{op(layout)});',False)
    for ident,part in [('ldsm_n4','A'),('ldsm_n2','B'),('universal32','C')]:
        _,typ,val,_=next(x for x in COPY if x[0]==ident)
        thr='(2,2,1):(1,2,0)'; perm='(_,_,_)'
        body=f'auto mma=make_tiled_mma(SM80_16x8x16_F32F16F16F32_TN{{}},{op(thr)},{op(perm)});auto c=make_tiled_copy_{part}(Copy_Atom<{typ},{val}>{{}},mma);'
        for side in 'SD': add('mma_copy',ident,part,thr,side,body+f'auto r=c.get_layout{side}_TV();')
    for ident,thr,val,fragment in [('ldsm_n4','(8,4):(4,1)','(1,8):(0,1)','(8,2,2):(1,8,16)'),('ldsm_n2','(8,4):(4,1)','(1,4):(0,1)','(2,2,4):(1,2,4)'),('universal32','(4,8):(8,1)','(2,2):(2,1)','(2,4,4):(1,2,8)')]:
        _,typ,vt,_=next(x for x in COPY if x[0]==ident)
        body=f'auto c=make_tiled_copy(Copy_Atom<{typ},{vt}>{{}},{op(thr)},{op(val)});auto f=make_tensor(counting_iterator<int>{{}},{op(fragment)});auto t=c.get_slice(0).retile_D(f);auto r=t.layout();'
        add('retile',ident,thr,val,fragment,body)
    sha=subprocess.check_output(['git','-C',str(args.cutlass),'rev-parse','HEAD'],text=True).strip()
    (ROOT/'app/src/test/resources/cute/advanced.tsv').write_text('# Host CuTe '+sha+'\n'+'\n'.join(rows)+'\n')
if __name__=='__main__': main()
