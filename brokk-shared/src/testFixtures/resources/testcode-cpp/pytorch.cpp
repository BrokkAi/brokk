namespace at::native {

DEFINE_DISPATCH(qadaptive_avg_pool2d_nhwc_stub);
DEFINE_DISPATCH(qadaptive_avg_pool3d_ndhwc_stub);

namespace {

inline int start_index(int out_idx, int out_len, int in_len) {
  /*
   * out_idx: the current index of output matrix
   * out_len: the dimension_size of output matrix
   * in_len: the dimension_size of input matrix
   * Basically, in_len / out_len gives the number of
   * elements in each average computation.
   * This function computes the start index on input matrix.
   */
  // NOLINTNEXTLINE(cppcoreguidelines-narrowing-conversions,bugprone-narrowing-conversions)
  return (int)std::floor((float)(out_idx * in_len) / out_len);
}
}
}
