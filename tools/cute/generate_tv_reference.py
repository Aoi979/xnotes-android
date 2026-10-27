#!/usr/bin/env python3
"""Compare the actual native CuTe TV -> hierarchical shape -> offset -> MN chain.
Uses make_identity_tensor + composition, the same path as print_latex(TiledMMA).
This is deliberately separate from whole-domain offset-only fixtures.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile
import resource
ROOT=Path(__file__).resolve().parents[2]
def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--cutlass',type=Path,required=True); parser.add_argument('--cuda',type=Path,default=Path('/opt/cuda')); a=parser.parse_args()
    src=(ROOT/'tools/cute/generate_reference.py').read_text(); ns={}; exec('import re\n'+src[src.index('def operand('):src.index('\ncases = []')],ns); op=ns['operand']
    cases=[('((4,8),(2,2)):((32,4),(2,1))',16,8),('(2,2):(1,2)',2,2),('(2,2):(2,1)',2,2),('((2,2),(2,2)):((1,2),(4,8))',4,4),('((2,2),(2,2)):((8,4),(2,1))',4,4),('((2,3),(2,2)):((12,4),(2,1))',4,6),('((4,8),(2,2)):((32,1),(16,8))',16,8),('((8,4),8):((8,0),1)',8,8)]
    body=''
    for layout,m,n in cases:
        # get_hier_coord is inverse address mapping; idx2crd is DEFAULT LEFT coordinate unflattening.
        body+='{auto l='+op(layout)+';auto ref=make_identity_tensor(make_shape(Int<'+str(m)+'>{},Int<'+str(n)+'>{}));auto tv=composition(ref,l);\n'
        body+='for(int t=0;t<size<0>(l);++t)for(int v=0;v<size<1>(l);++v){std::cout<<"'+layout+'\\t'+str(m)+'\\t'+str(n)+'\\t"<<t<<"\\t"<<v<<"\\t";auto c=make_coord(idx2crd(t,shape<0>(l)),idx2crd(v,shape<1>(l)));print(c);std::cout<<"\\t"<<crd2idx(c,l.shape())<<"\\t"<<l(t,v)<<"\\t";print(tv(t,v));std::cout<<"\\n";} }\n'
    with tempfile.TemporaryDirectory(prefix='cute-tv-') as d:
        p=Path(d); (p/'a.cpp').write_text('#include <cute/tensor.hpp>\n#include <iostream>\nusing namespace cute;\nint main(){'+body+'}\n')
        def limit():resource.setrlimit(resource.RLIMIT_AS,(2*1024**3,2*1024**3))
        subprocess.run(['g++','-std=c++17','-O0','-I'+str(a.cutlass/'include'),'-I'+str(a.cuda/'include'),'-I'+str(a.cuda/'include/cccl'),'-I/usr/include/cccl',str(p/'a.cpp'),'-o',str(p/'a')],check=True,preexec_fn=limit)
        output=subprocess.check_output([str(p/'a')],text=True)
    sha=subprocess.check_output(['git','-C',str(a.cutlass),'rev-parse','HEAD'],text=True).strip()
    (ROOT/'app/src/test/resources/cute/tv-coordinates.tsv').write_text('# native CuTe '+sha+'; layout M N T V coordinate domain-index offset MN\n'+output)
    print('Wrote',len(output.splitlines()),'direct TV coordinates.')
if __name__=='__main__':main()
