#include "luxin/bigmap_session.hpp"

#include <array>
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <utility>
#include <opencv2/imgproc.hpp>

using namespace luxin;

namespace {

// Independent 3x3 erosion oracle: outside the image is untrusted, not replicated.
cv::Mat trustedMapPixels(const cv::Mat& base) {
    cv::Mat trusted(base.size(), CV_8UC1, cv::Scalar(0));
    for (int y = 1; y + 1 < base.rows; ++y) {
        auto* out = trusted.ptr<uchar>(y);
        for (int x = 1; x + 1 < base.cols; ++x) {
            bool supported = true;
            for (int dy = -1; dy <= 1; ++dy) {
                const auto* row = base.ptr<uchar>(y + dy);
                for (int dx = -1; dx <= 1; ++dx) {
                    if (row[x + dx] == 0) supported = false;
                }
            }
            if (supported) out[x] = 255;
        }
    }
    return trusted;
}

cv::Mat expectedSupport(const cv::Mat& trusted, const cv::Mat& anchor_to_current) {
    cv::Mat source_support, warped_support;
    trusted.convertTo(source_support, CV_32F, 1.0 / 255.0);
    cv::warpAffine(source_support, warped_support, anchor_to_current, trusted.size(),
        cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0));
    cv::Mat expected = warped_support >= 1.0f;
    cv::bitwise_and(expected, trusted, expected);
    return expected;
}

// Exercise every exposed edge of the known geometry-mask UI rectangles. Some
// rectangle edges overlap other UI or the image border and have no map-side edge.
void assertKnownUiEdges(const cv::Mat& base, const cv::Mat& trusted) {
    const auto x0 = [&](int x) { return static_cast<int>(std::floor(x * (base.cols / 3120.0))); };
    const auto x1 = [&](int x) { return static_cast<int>(std::ceil(x * (base.cols / 3120.0))); };
    const auto y0 = [&](int y) { return static_cast<int>(std::floor(y * (base.rows / 1440.0))); };
    const auto y1 = [&](int y) { return static_cast<int>(std::ceil(y * (base.rows / 1440.0))); };
    // Pairs are (UI pixel, adjacent map pixel), in small-image coordinates.
    const std::array<std::pair<cv::Point, cv::Point>, 14> edges{{
        {{x1(760) - 1, y0(200)}, {x1(760), y0(200)}}, // banner right
        {{x0(400), y1(520) - 1}, {x0(400), y1(520)}}, // banner bottom
        {{x0(140), y0(700)}, {x0(140) - 1, y0(700)}}, // slider left
        {{x1(260) - 1, y0(700)}, {x1(260), y0(700)}}, // slider right
        {{x0(200), y1(930) - 1}, {x0(200), y1(930)}}, // slider bottom
        {{x0(1460), y0(80)}, {x0(1460) - 1, y0(80)}}, // resources left
        {{x0(1800), y1(140) - 1}, {x0(1800), y1(140)}}, // resources bottom
        {{x0(2020), y0(800)}, {x0(2020) - 1, y0(800)}}, // panel/floors left
        {{x0(1190), y0(1340)}, {x0(1190) - 1, y0(1340)}}, // lower label left
        {{x0(1600), y0(1270)}, {x0(1600), y0(1270) - 1}}, // lower label top
        {{x0(140), y0(1340)}, {x0(140) - 1, y0(1340)}}, // lower button left
        {{x1(280) - 1, y0(1340)}, {x1(280), y0(1340)}}, // lower button right
        {{x0(200), y0(1270)}, {x0(200), y0(1270) - 1}}, // lower button top
        {{x0(200), y1(1410) - 1}, {x0(200), y1(1410)}} // lower button bottom
    }};
    for (const auto& edge : edges) {
        const cv::Point inside_map = edge.second + (edge.second - edge.first);
        assert(base.at<uchar>(edge.first) == 0);
        assert(base.at<uchar>(edge.second) == 255);
        assert(trusted.at<uchar>(edge.first) == 0);
        assert(trusted.at<uchar>(edge.second) == 0); // fixed one-pixel guard
        assert(trusted.at<uchar>(inside_map) == 255); // not a broad exclusion
    }
}

cv::Mat uiOnlyBrightness(const cv::Mat& base) {
    // Float brightness avoids rounding away a small nonzero interpolation weight.
    // Every excluded UI pixel has arbitrary positive brightness; map pixels are zero.
    cv::Mat payload(base.size(), CV_32FC1, cv::Scalar(0));
    for (int y = 0; y < base.rows; ++y) {
        const auto* mask = base.ptr<uchar>(y);
        auto* row = payload.ptr<float>(y);
        for (int x = 0; x < base.cols; ++x) {
            if (mask[x] == 0) row[x] = static_cast<float>(32 + (37 * x + 73 * y + x * y) % 224);
        }
    }
    assert(cv::countNonZero(payload) == base.rows * base.cols - cv::countNonZero(base));
    cv::Mat map_payload;
    cv::bitwise_and(payload > 0.0f, base, map_payload);
    assert(cv::countNonZero(map_payload) == 0);
    return payload;
}

int uiLeakCount(const cv::Mat& warped_ui, const cv::Mat& mask) {
    cv::Mat leaks;
    cv::bitwise_and(warped_ui > 0.0f, mask, leaks);
    return cv::countNonZero(leaks);
}

void assertLegitimateChangesRetained(const cv::Mat& valid) {
    // One interior tile and one small tile near the banner's map-side edge.
    // These are genuine changed map pixels, not UI; residuals must not mask them.
    const cv::Rect interior(valid.cols * 45 / 100, valid.rows * 55 / 100, 9, 9);
    const int banner_right = static_cast<int>(std::ceil(760 * (valid.cols / 3120.0)));
    const cv::Rect near_ui(banner_right + 24, valid.rows * 15 / 100, 5, 5);
    assert(cv::countNonZero(valid(interior)) == interior.area());
    assert(cv::countNonZero(valid(near_ui)) == near_ui.area());
    cv::Mat changed(valid.size(), CV_8UC1, cv::Scalar(0));
    changed(interior).setTo(255);
    changed(near_ui).setTo(255);
    cv::Mat retained;
    cv::bitwise_and(changed, valid, retained);
    assert(cv::countNonZero(retained) == interior.area() + near_ui.area());
}

struct Fixture {
    const char* name;
    double scale;
    double dx;
    double dy;
};

cv::Mat transformFor(cv::Size size, const Fixture& fixture) {
    // Forward transform: small anchor -> small current, translations in pixels.
    const double tx = (1.0 - fixture.scale) * (size.width - 1) / 2.0 + fixture.dx;
    const double ty = (1.0 - fixture.scale) * (size.height - 1) / 2.0 + fixture.dy;
    return (cv::Mat_<double>(2, 3) << fixture.scale, 0, tx, 0, fixture.scale, ty);
}

void testSize(cv::Size size) {
    const cv::Mat base = bigMapGeometryMask(size);
    const cv::Mat trusted = trustedMapPixels(base);
    assertKnownUiEdges(base, trusted);
    const cv::Mat ui = uiOnlyBrightness(base);
    const std::array<Fixture, 15> fixtures{{
        {"identity", 1.0, 0, 0},
        {"x+0.25", 1.0, 0.25, 0}, {"x-0.25", 1.0, -0.25, 0},
        {"x+0.75", 1.0, 0.75, 0}, {"x-0.75", 1.0, -0.75, 0},
        {"y+0.25", 1.0, 0, 0.25}, {"y-0.25", 1.0, 0, -0.25},
        {"y+0.75", 1.0, 0, 0.75}, {"y-0.75", 1.0, 0, -0.75},
        {"xy++", 1.0, 0.25, 0.25}, {"xy+-", 1.0, 0.25, -0.25},
        {"xy-+", 1.0, -0.25, 0.25}, {"xy--", 1.0, -0.25, -0.25},
        {"zoom-in+translation", 1.03, 1.25, -2.25},
        {"zoom-out+translation", 0.97, -1.75, 2.25}
    }};
    for (const auto& fixture : fixtures) {
        const cv::Mat transform = transformFor(size, fixture);
        const cv::Mat valid = bigMapFollowComparisonMask(size, transform);
        assert(!valid.empty());
        assert(valid.size() == size && valid.type() == CV_8UC1);
        const cv::Mat expected = expectedSupport(trusted, transform);
        // Whole-image equality includes every source/destination UI and image edge.
        const int different = cv::countNonZero(valid != expected);
        if (different != 0) {
            std::cerr << size << ' ' << fixture.name << ": support mismatch at "
                      << different << " pixels\n";
        }
        assert(different == 0);
        assert(cv::countNonZero(valid) > 0);
        assert(cv::countNonZero((valid != 0) & (valid != 255)) == 0);
        assert(cv::countNonZero(valid.row(0)) == 0);
        assert(cv::countNonZero(valid.row(size.height - 1)) == 0);
        assert(cv::countNonZero(valid.col(0)) == 0);
        assert(cv::countNonZero(valid.col(size.width - 1)) == 0);
        cv::Mat current_ui_valid;
        cv::bitwise_and(base == 0, valid, current_ui_valid);
        assert(cv::countNonZero(current_ui_valid) == 0);

        cv::Mat warped_ui;
        cv::warpAffine(ui, warped_ui, transform, size, cv::INTER_LINEAR,
            cv::BORDER_CONSTANT, cv::Scalar(0));
        assert(cv::countNonZero(warped_ui) > 0); // payload is not vacuous
        assert(uiLeakCount(warped_ui, valid) == 0);
        assertLegitimateChangesRetained(valid);
        if (fixture.scale == 1.0 && fixture.dx == 0 && fixture.dy == 0) {
            assert(cv::countNonZero(valid != trusted) == 0);
        }
    }

    // Reproduce the original defect: nearest-neighbour validity admits pixels
    // whose grayscale interpolation footprint contains excluded UI brightness.
    const cv::Mat subpixel = transformFor(size, {"unsafe-nearest", 1.0, 0.25, 0.25});
    cv::Mat unsafe, warped_ui;
    cv::warpAffine(base, unsafe, subpixel, size, cv::INTER_NEAREST,
        cv::BORDER_CONSTANT, cv::Scalar(0));
    cv::bitwise_and(unsafe, base, unsafe);
    cv::warpAffine(ui, warped_ui, subpixel, size, cv::INTER_LINEAR,
        cv::BORDER_CONSTANT, cv::Scalar(0));
    assert(uiLeakCount(warped_ui, unsafe) > 0);
    assert(uiLeakCount(warped_ui, bigMapFollowComparisonMask(size, subpixel)) == 0);
}

void testInvalidInputs() {
    const cv::Size size(1560, 720);
    const cv::Mat identity = (cv::Mat_<double>(2, 3) << 1, 0, 0, 0, 1, 0);
    const std::array<cv::Size, 6> invalid_sizes{{
        {0, 720}, {-1, 720}, {1560, 0}, {1560, -1}, {0, 0}, {-1, -1}
    }};
    for (const auto& invalid_size : invalid_sizes) {
        assert(bigMapFollowComparisonMask(invalid_size, identity).empty());
    }
    const std::array<cv::Mat, 8> wrong_matrices{{
        cv::Mat(), cv::Mat::eye(2, 3, CV_32F), cv::Mat::eye(2, 3, CV_8U),
        cv::Mat::zeros(2, 3, CV_64FC2), cv::Mat::eye(2, 2, CV_64F),
        cv::Mat::eye(3, 3, CV_64F), cv::Mat::eye(3, 2, CV_64F),
        cv::Mat::zeros(1, 6, CV_64F)
    }};
    for (const auto& matrix : wrong_matrices) {
        assert(bigMapFollowComparisonMask(size, matrix).empty());
    }
    for (const double nonfinite : {std::numeric_limits<double>::quiet_NaN(),
             std::numeric_limits<double>::infinity(), -std::numeric_limits<double>::infinity()}) {
        for (int row = 0; row < 2; ++row) {
            for (int col = 0; col < 3; ++col) {
                cv::Mat matrix = identity.clone();
                matrix.at<double>(row, col) = nonfinite;
                assert(bigMapFollowComparisonMask(size, matrix).empty());
            }
        }
    }
    const std::array<cv::Mat, 6> invalid_determinants{{
        cv::Mat::zeros(2, 3, CV_64F),
        (cv::Mat_<double>(2, 3) << 1, 2, 4, 2, 4, 5), // singular
        (cv::Mat_<double>(2, 3) << -1, 0, 0, 0, 1, 0), // reflection
        (cv::Mat_<double>(2, 3) << 1, 0, 0, 0, 1e-17, 0),
        (cv::Mat_<double>(2, 3) << 1, 0, 0, 0, 1e-16, 0), // boundary is rejected
        (cv::Mat_<double>(2, 3) << 1e200, 0, 0, 0, 1e200, 0) // determinant overflow
    }};
    for (const auto& matrix : invalid_determinants) {
        assert(bigMapFollowComparisonMask(size, matrix).empty());
    }
}

} // namespace

int main() {
    cv::setNumThreads(1);
    for (const auto& size : std::array<cv::Size, 3>{{{1560, 720}, {1040, 480}, {780, 360}}}) {
        testSize(size);
    }
    testInvalidInputs();
    std::cout << "big-map follow comparison mask tests passed\n";
}
