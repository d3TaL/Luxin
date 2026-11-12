#pragma once

#include <opencv2/core.hpp>

namespace luxin {

struct BigMapSignal {
    bool detected = false;
    double plus_score = 0.0;
    double minus_score = 0.0;
    double track_fraction = 0.0;
};

// Height-normalized mobile UI; not a map-localization algorithm.
// Full-screen landscape BGR or grayscale. Unsupported input produces no detection.
BigMapSignal detectBigMap(const cv::Mat& bgr_or_gray);

} // namespace luxin
