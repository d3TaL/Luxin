#include "luxin/bigmap_session.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <set>
#include <opencv2/calib3d.hpp>
#include <opencv2/imgproc.hpp>

namespace luxin {
namespace {
using Clock = std::chrono::steady_clock;

cv::Mat smallGray(const cv::Mat& frame) {
    cv::Mat gray, small;
    if (frame.type() == CV_8UC3) cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    else gray = frame;
    const double scale = std::min(1.0, 720.0 / frame.rows);
    // Exact aspect is restored when composing into original-frame coordinates below.
    cv::resize(gray, small, cv::Size(), scale, scale, cv::INTER_AREA);
    return small;
}

void features(const cv::Mat& gray, std::vector<cv::KeyPoint>& points, cv::Mat& descriptors) {
    cv::ORB::create(1200)->detectAndCompute(gray, bigMapGeometryMask(gray.size()), points, descriptors);
}

double cornerDistance(const cv::Mat& a, const cv::Mat& b, cv::Size size) {
    double distance = 0.0;
    for (const auto& p : std::vector<cv::Point2d>{{0, 0}, {double(size.width), 0},
             {0, double(size.height)}, {double(size.width), double(size.height)}}) {
        const double dx = (a.at<double>(0, 0) - b.at<double>(0, 0)) * p.x +
            (a.at<double>(0, 1) - b.at<double>(0, 1)) * p.y + a.at<double>(0, 2) - b.at<double>(0, 2);
        const double dy = (a.at<double>(1, 0) - b.at<double>(1, 0)) * p.x +
            (a.at<double>(1, 1) - b.at<double>(1, 1)) * p.y + a.at<double>(1, 2) - b.at<double>(1, 2);
        distance = std::max(distance, std::hypot(dx, dy));
    }
    return distance;
}

// Do not carry a surface alignment through a floor-button selection change. Conservative:
// panel changes or moving scenery behind the controls may invalidate as well (safe reacquire).
bool controlsUnchanged(const cv::Mat& anchor, const cv::Mat& current) {
    for (const auto& box : std::vector<cv::Rect>{{2790, 500, 170, 450}, {2020, 540, 120, 350}}) {
        cv::Rect rect(cvRound(box.x * anchor.cols / 3120.0), cvRound(box.y * anchor.rows / 1440.0),
            std::max(1, cvRound(box.width * anchor.cols / 3120.0)), std::max(1, cvRound(box.height * anchor.rows / 1440.0)));
        rect &= cv::Rect(0, 0, anchor.cols, anchor.rows);
        cv::Mat delta;
        cv::absdiff(anchor(rect), current(rect), delta);
        if (cv::mean(delta)[0] > 6.0 || cv::countNonZero(delta > 24) > delta.total() * 0.04) return false;
    }
    return true;
}

bool follow(const cv::Mat& anchor, const std::vector<cv::KeyPoint>& anchor_points,
    const cv::Mat& anchor_descriptors, const cv::Mat& current, cv::Mat& transform) {
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    features(current, keypoints, descriptors);
    if (descriptors.rows < 2 || anchor_descriptors.rows < 2) return false;
    cv::BFMatcher matcher(cv::NORM_HAMMING);
    std::vector<std::vector<cv::DMatch>> pairs;
    matcher.knnMatch(anchor_descriptors, descriptors, pairs, 2);
    std::vector<cv::Point2f> from, to;
    std::set<int> used;
    for (const auto& pair : pairs) {
        if (pair.size() != 2 || pair[0].distance >= 0.75f * pair[1].distance ||
            !used.insert(pair[0].trainIdx).second) continue;
        from.push_back(anchor_points[pair[0].queryIdx].pt);
        to.push_back(keypoints[pair[0].trainIdx].pt);
    }
    if (from.size() < 24) return false;
    cv::Mat inliers;
    transform = cv::estimateAffinePartial2D(from, to, inliers, cv::RANSAC, 1.5);
    if (transform.empty() || !cv::checkRange(transform)) return false;
    const double scale = std::hypot(transform.at<double>(0, 0), transform.at<double>(1, 0));
    const double angle = std::atan2(transform.at<double>(1, 0), transform.at<double>(0, 0));
    if (scale < 0.7 || scale > 1.4 || std::abs(angle) > CV_PI / 180.0) return false;
    std::vector<cv::Point2f> kept;
    std::set<std::pair<int, int>> occupied;
    double error = 0.0;
    for (size_t i = 0; i < from.size(); ++i) {
        if (!inliers.at<uchar>(static_cast<int>(i))) continue;
        kept.push_back(to[i]);
        occupied.emplace(int(to[i].x * 4 / current.cols), int(to[i].y * 4 / current.rows));
        const double x = transform.at<double>(0, 0) * from[i].x + transform.at<double>(0, 1) * from[i].y + transform.at<double>(0, 2);
        const double y = transform.at<double>(1, 0) * from[i].x + transform.at<double>(1, 1) * from[i].y + transform.at<double>(1, 2);
        error += std::hypot(x - to[i].x, y - to[i].y);
    }
    if (kept.size() < 24 || kept.size() < from.size() * 0.65 || occupied.size() < 4 || error / kept.size() > 0.8) return false;
    std::vector<cv::Point2f> hull;
    cv::convexHull(kept, hull);
    if (cv::contourArea(hull) < current.total() * 0.015) return false;

    // Dense appearance check prevents reusing geometry just because a handful of icons or
    // unchanged background features survive a floor/space switch. Masks must overlap.
    cv::Mat warped, valid;
    cv::warpAffine(anchor, warped, transform, current.size(), cv::INTER_LINEAR);
    cv::warpAffine(bigMapGeometryMask(anchor.size()), valid, transform, current.size(), cv::INTER_NEAREST);
    cv::bitwise_and(valid, bigMapGeometryMask(current.size()), valid);
    if (cv::countNonZero(valid) < current.total() * 0.20) return false;
    cv::Mat delta;
    cv::absdiff(warped, current, delta);
    cv::Mat changed = (delta > 25) & valid;
    if (cv::countNonZero(changed) > cv::countNonZero(valid) * 0.025) return false;
    // Local changes cannot be hidden by a large unchanged surface/background.
    for (int y = 0; y < current.rows; y += 90) {
        for (int x = 0; x < current.cols; x += 90) {
            const cv::Rect tile(x, y, std::min(90, current.cols - x), std::min(90, current.rows - y));
            const int count = cv::countNonZero(valid(tile));
            if (count >= 200 && cv::countNonZero(changed(tile)) > count * 0.12) return false;
        }
    }
    return controlsUnchanged(anchor, current);
}
} // namespace

void BigMapSession::reset() {
    anchor_gray_.release();
    anchor_descriptors_.release();
    anchor_keypoints_.clear();
    anchor_ = {};
    previous_transform_.release();
    previous_timestamp_ = -1;
    last_attempt_ = -1;
    frame_size_ = {};
    stable_frames_ = 0;
}

BigMapSessionResult BigMapSession::update(const cv::Mat& frame,
    const std::vector<std::shared_ptr<LayerReference>>& refs, double timestamp) try {
    BigMapSessionResult result;
    if (frame.empty() || frame.cols <= frame.rows || (frame.type() != CV_8UC1 && frame.type() != CV_8UC3) ||
        !std::isfinite(timestamp) || timestamp < 0 || (previous_timestamp_ >= 0 && timestamp <= previous_timestamp_)) {
        reset();
        result.phase = "rejected";
        return result;
    }
    // Absolute acquisition may itself take seconds. It is still only a candidate: a later
    // fresh frame must pass all follow checks, never just receive an updated timestamp.
    if (frame_size_ != frame.size() || (previous_timestamp_ >= 0 && timestamp - previous_timestamp_ > 10.0)) reset();
    frame_size_ = frame.size();
    previous_timestamp_ = timestamp;
    auto current = smallGray(frame);
    if (!anchor_gray_.empty() && timestamp - anchor_.timestamp_seconds <= 10.0) {
        const auto start = Clock::now();
        cv::Mat transform;
        const bool ok = follow(anchor_gray_, anchor_keypoints_, anchor_descriptors_, current, transform);
        result.follow_ms = std::chrono::duration<double, std::milli>(Clock::now() - start).count();
        if (ok) {
            const double movement = previous_transform_.empty() ? 100.0 : cornerDistance(transform, previous_transform_, current.size());
            previous_transform_ = transform.clone();
            stable_frames_ = movement <= 0.6 ? stable_frames_ + 1 : 0;
            result.phase = stable_frames_ >= 2 ? "tracking" : movement <= 0.6 ? "confirming" : "moving";
            if (stable_frames_ >= 2) {
                // small-current <- small-anchor <- original-anchor <- Kongying.
                const double sx = double(current.cols) / frame.cols, sy = double(current.rows) / frame.rows;
                cv::Mat scale = (cv::Mat_<double>(3, 3) << sx, 0, 0, 0, sy, 0, 0, 0, 1);
                cv::Mat inverse = (cv::Mat_<double>(3, 3) << 1 / sx, 0, 0, 0, 1 / sy, 0, 0, 0, 1);
                cv::Mat motion = cv::Mat::eye(3, 3, CV_64F), anchor = cv::Mat::eye(3, 3, CV_64F);
                transform.copyTo(motion(cv::Rect(0, 0, 3, 2)));
                anchor_.matrix.copyTo(anchor(cv::Rect(0, 0, 3, 2)));
                cv::Mat composed = inverse * motion * scale * anchor;
                // Correct the negligible x/y resize rounding into an exact similarity for JNI.
                const double a = (composed.at<double>(0, 0) + composed.at<double>(1, 1)) / 2;
                const double b = (composed.at<double>(1, 0) - composed.at<double>(0, 1)) / 2;
                result.geometry = anchor_;
                result.geometry.matrix = (cv::Mat_<double>(2, 3) << a, -b, composed.at<double>(0, 2), b, a, composed.at<double>(1, 2));
                result.geometry.timestamp_seconds = timestamp;
                if (!cv::checkRange(result.geometry.matrix) || !std::isfinite(a * a + b * b) || a * a + b * b <= 1e-16) {
                    reset();
                    result.geometry = {};
                    result.phase = "rejected";
                }
            }
            return result;
        }
        // No stale fallback: failed follow destroys the anchor before reacquisition.
        anchor_gray_.release();
        previous_transform_.release();
        stable_frames_ = 0;
    }
    if (last_attempt_ >= 0 && timestamp - last_attempt_ < 2.0) {
        result.phase = "acquiring";
        return result;
    }
    result.absolute_attempted = true;
    last_attempt_ = timestamp;
    auto absolute = registerBigMap(frame, refs, timestamp, &result.registration);
    result.phase = absolute.mode;
    if (absolute.mode != "found") {
        result.geometry = absolute;
        return result;
    }
    anchor_ = absolute;
    anchor_gray_ = current;
    features(current, anchor_keypoints_, anchor_descriptors_);
    previous_transform_ = (cv::Mat_<double>(2, 3) << 1, 0, 0, 0, 1, 0);
    stable_frames_ = 0;
    result.phase = "confirming";
    // The absolute result remains an internal candidate even when computation was fast.
    return result;
} catch (...) {
    reset();
    throw;
}
} // namespace luxin
