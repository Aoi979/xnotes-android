// Reproduction of the 2026-10-27 report, using NVIDIA's own TV diagram printer.
// g++ -std=c++17 -O0 -I/path/to/cutlass/include -I/opt/cuda/include \
//   -I/opt/cuda/include/cccl -I/usr/include/cccl verify_user_tv_example.cpp -o /tmp/cute-tv
// /tmp/cute-tv > /tmp/cute-tv.tex
#include <cute/tensor.hpp>
#include <cute/util/print_latex.hpp>
using namespace cute;
int main() {
  auto layout = make_layout(make_shape(make_shape(Int<4>{},Int<8>{}),
                                      make_shape(Int<2>{},Int<2>{})), LayoutRight{});
  // layout is exactly ((4,8),(2,2)):((32,4),(2,1)).
  auto tile = make_shape(Int<16>{},Int<8>{});
  auto coordinates = composition(make_identity_tensor(tile),layout);
  print_latex_tv(coordinates,tile);
}
