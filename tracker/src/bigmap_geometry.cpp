#include "luxin/bigmap_geometry.hpp"

#include <algorithm>
#include <cmath>
#include <chrono>
#include <opencv2/imgproc.hpp>

namespace luxin {

bool bigMapGeometryQualityAccepted(int inliers, int matches, double error_px,
                                   double hull_area, int visible_pixels, cv::Size size) {
    return size.width > 0 && size.height > 0 && inliers >= 12 && matches >= inliers &&
        double(inliers) / matches >= 0.30 && std::isfinite(error_px) && error_px >= 0 &&
        error_px <= 2.5 * size.height / 1440.0 && std::isfinite(hull_area) &&
        visible_pixels > 0 && hull_area >= double(size.width) * size.height * 0.005 &&
        hull_area / visible_pixels >= 0.05;
}

namespace {
cv::Mat mapMask(cv::Size size, bool exclude_possible_panel) {
    if (size.width <= 0 || size.height <= 0) return {};
    cv::Mat mask(size, CV_8UC1, cv::Scalar(255));
    // Features exclude the entire possible panel. ROI comparisons must observe that area.
    const cv::Rect boxes[] = {
        {0, 0, 760, 520}, {140, 510, 120, 420}, {1460, 0, 1660, 140},
        {1190, 1270, 1930, 170}, {140, 1270, 140, 140}
    };
    auto exclude = [&](cv::Rect box) {
        const int x0 = static_cast<int>(std::floor(box.x * (size.width / 3120.0)));
        const int y0 = static_cast<int>(std::floor(box.y * (size.height / 1440.0)));
        const int x1 = static_cast<int>(std::ceil((box.x + box.width) * (size.width / 3120.0)));
        const int y1 = static_cast<int>(std::ceil((box.y + box.height) * (size.height / 1440.0)));
        mask(cv::Rect(x0, y0, x1 - x0, y1 - y0) & cv::Rect(0, 0, size.width, size.height)).setTo(0);
    };
    for (const auto& box : boxes) exclude(box);
    if (exclude_possible_panel) exclude({2020, 0, 1100, 1440});
    return mask;
}
} // namespace

cv::Mat bigMapGeometryMask(cv::Size size) { return mapMask(size, true); }
cv::Mat bigMapControlSourceMask(cv::Size size) { return mapMask(size, false); }

namespace {
struct Candidate {
    BigMapGeometry geometry;
    double score;
};

} // namespace

bool equivalentBigMapGeometry(const BigMapGeometry& a, const BigMapGeometry& b) {
    if (a.space.empty() || a.space != b.space || a.frame_width != b.frame_width ||
        a.frame_height != b.frame_height || a.frame_width <= 0 || a.frame_height <= 0) return false;
    for (const auto* m : {&a.matrix, &b.matrix}) {
        if (m->type() != CV_64F || m->size() != cv::Size(3, 2) || !cv::checkRange(*m)) return false;
        const double det = cv::determinant((*m)(cv::Rect(0, 0, 2, 2)));
        if (!std::isfinite(det) || det <= 1e-16) return false;
    }
    cv::Mat inverse;
    cv::invertAffineTransform(a.matrix, inverse);
    // Compare the entire frame, not just its centre (different scales may share a centre).
    for (const auto& p : std::vector<cv::Point2d>{{0, 0}, {double(a.frame_width), 0},
             {0, double(a.frame_height)}, {double(a.frame_width), double(a.frame_height)}}) {
        const double x = inverse.at<double>(0, 0) * p.x + inverse.at<double>(0, 1) * p.y + inverse.at<double>(0, 2);
        const double y = inverse.at<double>(1, 0) * p.x + inverse.at<double>(1, 1) * p.y + inverse.at<double>(1, 2);
        const double bx = b.matrix.at<double>(0, 0) * x + b.matrix.at<double>(0, 1) * y + b.matrix.at<double>(0, 2);
        const double by = b.matrix.at<double>(1, 0) * x + b.matrix.at<double>(1, 1) * y + b.matrix.at<double>(1, 2);
        const double error = std::hypot(bx - p.x, by - p.y);
        if (!std::isfinite(error) || error > 8.0 * a.frame_height / 1440.0) return false;
    }
    return true;
}

BigMapGeometry registerBigMap(const cv::Mat& frame,
    const std::vector<std::shared_ptr<LayerReference>>& references, double timestamp_seconds,
    BigMapRegistrationStats* stats) {
    if (stats) *stats = {};
    using Clock = std::chrono::steady_clock;
    auto milliseconds = [](Clock::time_point start) {
        return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
    };
    const auto featureStart = Clock::now();
    BigMapGeometry rejected;
    if (frame.empty() || frame.cols <= frame.rows || !std::isfinite(timestamp_seconds) || timestamp_seconds < 0 ||
        (frame.type() != CV_8UC1 && frame.type() != CV_8UC3)) return rejected;
    rejected.frame_width = frame.cols;
    rejected.frame_height = frame.rows;
    rejected.timestamp_seconds = timestamp_seconds;
    // Bound feature extraction cost; all returned geometry remains in original frame pixels.
    const double resize = std::min(1.0, 1440.0 / frame.rows);
    cv::Mat gray;
    if (frame.channels() == 3) cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    else gray = frame;
    cv::Mat query;
    cv::resize(gray, query, cv::Size(), resize, resize, cv::INTER_AREA);
    const double kx = double(query.cols) / frame.cols;
    const double ky = double(query.rows) / frame.rows;
    MinimapCrop crop;
    crop.gray = gray;
    crop.mask = bigMapGeometryMask(frame.size());
    crop.lx = frame.cols / 2.0;
    crop.ly = frame.rows / 2.0;
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    cv::SIFT::create(12000, 3, 0.02)->detectAndCompute(query, bigMapGeometryMask(query.size()), keypoints, descriptors);
    if (stats) {
        stats->features_ms = milliseconds(featureStart);
        stats->query_features = static_cast<int>(keypoints.size());
        stats->extraction_width = query.cols;
        stats->extraction_height = query.rows;
    }
    if (descriptors.empty()) return rejected;
    std::vector<cv::Point2f> points;
    for (const auto& kp : keypoints) points.emplace_back(kp.pt.x / kx, kp.pt.y / ky);
    std::vector<Candidate> candidates;
    for (const auto& ref : references) {
        if (!ref || !std::isfinite(ref->originX()) || !std::isfinite(ref->originY())) continue;
        Registration reg;
        const auto matchingStart = Clock::now();
        const bool registered = ref->registerQuery(points, descriptors, crop, frame.rows / 1440.0,
                nullptr, 0, reg, 0.1, 6.0);
        if (stats) stats->matching_ms += milliseconds(matchingStart);
        if (!registered) continue;
        const auto validationStart = Clock::now();
        // Account for early-rejected candidates as well as accepted candidates.
        struct ValidationTimer {
            BigMapRegistrationStats* stats;
            Clock::time_point start;
            ~ValidationTimer() {
                if (stats) stats->validation_ms += std::chrono::duration<double, std::milli>(Clock::now() - start).count();
            }
        } validationTimer{stats, validationStart};
        // Reject cheap failures before allocating/warping a full-frame context mask.
        // The complete shared gate below remains authoritative for spatial quality.
        if (!bigMapGeometryQualityAccepted(reg.inliers, reg.matches, reg.error_px,
                double(frame.cols) * frame.rows, 1, frame.size())) continue;
        std::vector<cv::Point2f> hull;
        cv::convexHull(reg.inlier_points, hull);
        const double area = cv::contourArea(hull);
        cv::Mat visible;
        cv::warpAffine(ref->contextMask(), visible, reg.matrix, frame.size(), cv::INTER_NEAREST | cv::WARP_INVERSE_MAP);
        cv::bitwise_and(visible, crop.mask, visible);
        const int visiblePixels = cv::countNonZero(visible);
        if (!bigMapGeometryQualityAccepted(reg.inliers, reg.matches, reg.error_px,
                area, visiblePixels, frame.size())) continue;
        int supportedInliers = 0;
        for (const auto& point : reg.inlier_points) {
            const int x = cvRound(point.x), y = cvRound(point.y);
            if (x >= 0 && y >= 0 && x < visible.cols && y < visible.rows && visible.at<uchar>(y, x))
                ++supportedInliers;
        }
        if (supportedInliers < reg.inliers * 0.8) continue;
        const double support = std::min(1.0, area / visiblePixels);
        cv::Mat inverse;
        cv::invertAffineTransform(reg.matrix, inverse); // reference px -> original frame px
        BigMapGeometry g = rejected;
        g.mode = "found";
        g.space = ref->space();
        g.reference = ref->name(); // Diagnostic only: does not identify the selected floor.
        g.matrix = inverse.clone();
        for (int row = 0; row < 2; ++row) {
            g.matrix.at<double>(row, 0) = inverse.at<double>(row, 0) / ref->unitsPerPx();
            g.matrix.at<double>(row, 1) = inverse.at<double>(row, 1) / ref->unitsPerPx();
            g.matrix.at<double>(row, 2) = (inverse.at<double>(row, 2) -
                inverse.at<double>(row, 0) * ref->originX() / ref->unitsPerPx() -
                inverse.at<double>(row, 1) * ref->originY() / ref->unitsPerPx());
        }
        if (!cv::checkRange(g.matrix)) continue;
        g.inliers = reg.inliers;
        g.matches = reg.matches;
        g.error_px = reg.error_px;
        g.support = support;
        candidates.push_back({g, reg.inliers * std::sqrt(support) / (1.0 + reg.error_px)});
    }
    if (candidates.empty()) return rejected;
    std::sort(candidates.begin(), candidates.end(), [](const Candidate& a, const Candidate& b) { return a.score > b.score; });
    for (size_t i = 1; i < candidates.size(); ++i) {
        if (candidates[i].score >= candidates[0].score * 0.8 &&
            !equivalentBigMapGeometry(candidates[0].geometry, candidates[i].geometry)) {
            rejected.mode = "ambiguous";
            return rejected;
        }
    }
    return candidates[0].geometry;
}
} // namespace luxin
