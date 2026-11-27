#pragma once
#include <opencv2/core.hpp>
#include <cstdint>

namespace luxin::motion {
// Borrowed for this synchronous update only. Java passes a direct slice at the
// crop origin; the last row need not have trailing padding.
inline cv::Mat borrowedRgba(void *data, int64_t bytes, int w, int h, int stride) {
    if (!data || h <= 0 || w <= h || stride < 4LL*w || bytes < (h-1LL)*stride + 4LL*w) return {};
    return cv::Mat(h,w,CV_8UC4,data,size_t(stride));
}
} // namespace luxin::motion
