#include "luxin/bigmap_detector.hpp"
#include "bigmap_templates.hpp"

#include <algorithm>
#include <cmath>
#include <opencv2/imgproc.hpp>

namespace luxin {
namespace {
constexpr int kHeight = 720;
constexpr int kSize = 24;
constexpr int kGap = 160; // 320 px at 1440p, between '+' and '-'

cv::Mat match(const cv::Mat& roi, const uint8_t* data) {
    cv::Mat tpl(kSize, kSize, CV_8U, const_cast<uint8_t*>(data));
    cv::Mat mask(kSize, kSize, CV_8U, cv::Scalar(0));
    for (int y = 0; y < kSize; ++y) {
        for (int x = 0; x < kSize; ++x) {
            // Map icons can overlap corners outside the button's diamond.
            if (std::abs(x - 11.5) + std::abs(y - 11.5) <= 11) mask.at<uint8_t>(y, x) = 255;
        }
    }
    cv::Mat result;
    cv::matchTemplate(roi, tpl, result, cv::TM_CCOEFF_NORMED, mask);
    for (int y = 0; y < result.rows; ++y) {
        auto* row = result.ptr<float>(y);
        for (int x = 0; x < result.cols; ++x) {
            if (!std::isfinite(row[x])) row[x] = -1.0f;
        }
    }
    return result;
}

int columnMax(const cv::Mat& gray, int y, int x) {
    return std::max({gray.at<uint8_t>(y, x - 1), gray.at<uint8_t>(y, x),
                     gray.at<uint8_t>(y, x + 1)});
}

// Match Python round's ties-to-even for the half-pixel track-column offset.
int roundEven(double v) {
    const double lo = std::floor(v);
    if (v - lo == 0.5) return static_cast<int>(lo) % 2 == 0 ? static_cast<int>(lo) : static_cast<int>(lo + 1);
    return static_cast<int>(std::round(v));
}
} // namespace

BigMapSignal detectBigMap(const cv::Mat& input) {
    BigMapSignal signal;
    if (input.empty() || input.depth() != CV_8U ||
        (input.channels() != 1 && input.channels() != 3) ||
        input.cols <= input.rows || input.rows < 240 ||
        input.cols > input.rows * 4LL) return signal;

    // Full-width resizing for exact parity with the offline baseline.
    cv::Mat small, gray;
    cv::resize(input, small, cv::Size(roundEven(input.cols * 720.0 / input.rows), kHeight), 0, 0, cv::INTER_AREA);
    if (small.channels() == 3) cv::cvtColor(small, gray, cv::COLOR_BGR2GRAY);
    else gray = small;
    const int x0 = 0, x1 = 230; // int(0.32 * 720)
    const int y0 = 201, y1 = 518; // int(0.28/0.72 * 720)
    cv::Mat roi = gray(cv::Rect(x0, y0, x1 - x0, y1 - y0));
    auto plus = match(roi, bigmap_templates::plus);
    auto minus = match(roi, bigmap_templates::minus);
    cv::dilate(minus, minus, cv::Mat::ones(3, 3, CV_8U));
    const int rows = plus.rows - kGap;
    cv::Mat joint;
    cv::min(plus.rowRange(0, rows), minus.rowRange(kGap, minus.rows), joint);
    cv::Point best;
    cv::minMaxLoc(joint, nullptr, nullptr, nullptr, &best);
    signal.plus_score = plus.at<float>(best);
    signal.minus_score = minus.at<float>(best.y + kGap, best.x);

    const int cx = best.x + x0 + kSize / 2;
    const int cy = best.y + y0 + kSize / 2;
    const int xl = roundEven(cx - 4.5), xr = roundEven(cx + 4.5);
    const int top = cy + 20, bottom = cy + kGap - 20;
    if (xl < 1 || xr + 2 >= gray.cols || top < 0 || bottom > gray.rows) return signal;
    int count = 0;
    for (int y = top; y < bottom; ++y) {
        double inner = 0.0;
        for (int x = xl + 3; x < xr - 2; ++x) inner += gray.at<uint8_t>(y, x);
        inner /= xr - xl - 5;
        if (columnMax(gray, y, xl) - inner > 12 && columnMax(gray, y, xr) - inner > 12) ++count;
    }
    signal.track_fraction = static_cast<double>(count) / (bottom - top);
    signal.detected = signal.plus_score >= 0.70 && signal.minus_score >= 0.70 && signal.track_fraction >= 0.60;
    return signal;
}
} // namespace luxin
