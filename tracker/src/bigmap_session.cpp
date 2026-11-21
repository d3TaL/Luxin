#include "luxin/bigmap_session.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <set>
#include <opencv2/calib3d.hpp>
#include <opencv2/imgproc.hpp>

namespace luxin {
namespace {
using Clock = std::chrono::steady_clock;

// Motion tolerance (experimental). A frame is "moving" above kMotionPx of corner travel between
// consecutive published transforms. After the last measured motion, failures and image-gate misses
// are still treated as part of the pan for kMotionGraceSeconds (blur, duplicated captures, inertia).
constexpr double kMotionPx = 0.6;
constexpr double kMotionGraceSeconds = 1.0;

cv::Mat smallGray(const cv::Mat& frame) {
    cv::Mat gray, small;
    if (frame.type() == CV_8UC3) cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    else gray = frame;
    const double scale = std::min(1.0, 720.0 / frame.rows);
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

// Absolute maps K -> captured pixels. ORB motion maps small anchor pixels -> small current pixels.
BigMapGeometry followed(const BigMapGeometry& absolute, const cv::Mat& transform, cv::Size small, double ts) {
    const double sx = double(small.width) / absolute.frame_width, sy = double(small.height) / absolute.frame_height;
    cv::Mat scale = (cv::Mat_<double>(3, 3) << sx, 0, 0, 0, sy, 0, 0, 0, 1);
    cv::Mat inverse = (cv::Mat_<double>(3, 3) << 1 / sx, 0, 0, 0, 1 / sy, 0, 0, 0, 1);
    cv::Mat motion = cv::Mat::eye(3, 3, CV_64F), anchor = cv::Mat::eye(3, 3, CV_64F);
    transform.copyTo(motion(cv::Rect(0, 0, 3, 2)));
    absolute.matrix.copyTo(anchor(cv::Rect(0, 0, 3, 2)));
    cv::Mat composed = inverse * motion * scale * anchor;
    const double a = (composed.at<double>(0, 0) + composed.at<double>(1, 1)) / 2;
    const double b = (composed.at<double>(1, 0) - composed.at<double>(0, 1)) / 2;
    BigMapGeometry result = absolute;
    result.matrix = (cv::Mat_<double>(2, 3) << a, -b, composed.at<double>(0, 2), b, a, composed.at<double>(1, 2));
    result.timestamp_seconds = ts;
    if (!cv::checkRange(result.matrix) || !std::isfinite(a * a + b * b) || a * a + b * b <= 1e-16) return {};
    return result;
}

// A control ROI is unchanged if it matches the anchor either where it was (screen-fixed UI) or, when
// `map_anchor` (the anchor warped by the current transform) is given, where the map moved it. Map
// content that happens to lie in a ROI therefore does not fail during a pan, while a panel that
// appears (matches neither) still does. Stationary frames pass no `map_anchor` and are unchanged.
bool controlsUnchanged(const cv::Mat& anchor, const cv::Mat& current, BigMapFollowStats& stats,
    const cv::Mat& map_anchor = cv::Mat()) {
    size_t index = 0;
    for (const auto& box : std::vector<cv::Rect>{{2790, 500, 170, 450}, {2020, 540, 120, 350}}) {
        cv::Rect rect(cvRound(box.x * anchor.cols / 3120.0), cvRound(box.y * anchor.rows / 1440.0),
            std::max(1, cvRound(box.width * anchor.cols / 3120.0)), std::max(1, cvRound(box.height * anchor.rows / 1440.0)));
        rect &= cv::Rect(0, 0, anchor.cols, anchor.rows);
        cv::Mat delta;
        cv::absdiff(anchor(rect), current(rect), delta);
        if (!map_anchor.empty()) {
            cv::Mat moved_delta;
            cv::absdiff(map_anchor(rect), current(rect), moved_delta);
            if (cv::mean(moved_delta)[0] < cv::mean(delta)[0]) delta = moved_delta;
        }
        auto& control = stats.controls[index++];
        control.rect = rect;
        control.mean_delta = cv::mean(delta)[0];
        control.changed_pixels = cv::countNonZero(delta > 24);
        control.pixels = static_cast<int>(delta.total());
        if (control.pixels > 0) control.changed_fraction = double(control.changed_pixels) / control.pixels;
        if (control.mean_delta > 6.0 || control.changed_pixels > delta.total() * 0.04) {
            stats.outcome = "controls_change";
            return false;
        }
    }
    return true;
}

bool follow(const cv::Mat& anchor, const std::vector<cv::KeyPoint>& anchor_points,
    const cv::Mat& anchor_descriptors, const cv::Mat& current, cv::Mat& transform, BigMapFollowStats& stats,
    bool debug_points = false) {
    const auto reject = [&stats](const char* reason) { stats.outcome = reason; return false; };
    stats.working_size = current.size();
    stats.anchor_keypoints = static_cast<int>(anchor_points.size());
    stats.anchor_descriptors = anchor_descriptors.rows;
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    features(current, keypoints, descriptors);
    stats.current_keypoints = static_cast<int>(keypoints.size());
    stats.current_descriptors = descriptors.rows;
    if (descriptors.rows < 2 || anchor_descriptors.rows < 2) return reject("insufficient_descriptors");
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
    stats.matches = static_cast<int>(from.size());
    if (from.size() < 24) return reject("insufficient_matches");
    cv::Mat inliers;
    transform = cv::estimateAffinePartial2D(from, to, inliers, cv::RANSAC, 1.5);
    if (transform.empty() || !cv::checkRange(transform)) return reject("invalid_transform");
    const double scale = std::hypot(transform.at<double>(0, 0), transform.at<double>(1, 0));
    const double angle = std::atan2(transform.at<double>(1, 0), transform.at<double>(0, 0));
    stats.scale = scale;
    stats.angle_degrees = angle * 180.0 / CV_PI;
    if (scale < 0.7 || scale > 1.4) return reject("scale_bounds");
    if (std::abs(angle) > CV_PI / 180.0) return reject("rotation_bounds");
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
    stats.inliers = static_cast<int>(kept.size());
    stats.inlier_ratio = double(kept.size()) / from.size();
    stats.grid_cells = static_cast<int>(occupied.size());
    if (!kept.empty()) stats.mean_error_px = error / kept.size();
    if (kept.size() < 24) return reject("insufficient_inliers");
    if (kept.size() < from.size() * 0.65) return reject("inlier_ratio");
    if (occupied.size() < 4) return reject("grid_support");
    if (error / kept.size() > 0.8) return reject("reprojection_error");
    std::vector<cv::Point2f> hull;
    cv::convexHull(kept, hull);
    const double hull_area = cv::contourArea(hull);
    stats.hull_fraction = hull_area / current.total();
    if (hull_area < current.total() * 0.015) return reject("hull_support");
    stats.geometry_accepted = true;

    cv::Mat warped, valid;
    cv::warpAffine(anchor, warped, transform, current.size(), cv::INTER_LINEAR);
    valid = bigMapFollowComparisonMask(current.size(), transform);
    if (valid.empty()) return reject("invalid_comparison_mask");
    stats.overlap_pixels = cv::countNonZero(valid);
    stats.overlap_fraction = double(stats.overlap_pixels) / current.total();
    if (stats.overlap_pixels < current.total() * 0.20) return reject("overlap");
    cv::Mat delta;
    cv::absdiff(warped, current, delta);
    cv::Mat changed = (delta > 25) & valid;
    stats.changed_pixels = cv::countNonZero(changed);
    stats.changed_fraction = double(stats.changed_pixels) / stats.overlap_pixels;
    if (stats.changed_pixels > stats.overlap_pixels * 0.025) return reject("dense_change");
    for (int y = 0; y < current.rows; y += 90) {
        for (int x = 0; x < current.cols; x += 90) {
            const cv::Rect tile(x, y, std::min(90, current.cols - x), std::min(90, current.rows - y));
            const int count = cv::countNonZero(valid(tile));
            if (count >= 200) {
                const int changed_count = cv::countNonZero(changed(tile));
                const double fraction = double(changed_count) / count;
                ++stats.tiles_evaluated;
                if (fraction > stats.worst_tile_changed_fraction) {
                    stats.worst_tile = tile;
                    stats.worst_tile_valid_pixels = count;
                    stats.worst_tile_changed_pixels = changed_count;
                    stats.worst_tile_changed_fraction = fraction;
                }
                if (changed_count > count * 0.12) return reject("tile_change");
            }
        }
    }
    if (!controlsUnchanged(anchor, current, stats)) return false;
    stats.outcome = "accepted";
    if (debug_points) {
        std::array<bool, 8 * 6> sampled{};
        for (auto p : kept) {
            const int x = int(p.x * 8 / current.cols), y = int(p.y * 6 / current.rows);
            if (x < 0 || x >= 8 || y < 0 || y >= 6 || sampled[y * 8 + x]) continue;
            sampled[y * 8 + x] = true;
            stats.debug_points.push_back(p);
        }
    }
    return true;
}
} // namespace

cv::Mat bigMapFollowComparisonMask(cv::Size size, const cv::Mat& anchor_to_current) {
    if (size.width <= 0 || size.height <= 0 || anchor_to_current.size() != cv::Size(3, 2) ||
        anchor_to_current.type() != CV_64FC1 || !cv::checkRange(anchor_to_current)) return {};
    const double det = anchor_to_current.at<double>(0, 0) * anchor_to_current.at<double>(1, 1) -
        anchor_to_current.at<double>(0, 1) * anchor_to_current.at<double>(1, 0);
    if (!std::isfinite(det) || det <= 1e-16) return {};
    cv::Mat map_mask = bigMapGeometryMask(size);
    cv::erode(map_mask, map_mask, cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3)),
        cv::Point(-1, -1), 1, cv::BORDER_CONSTANT, cv::Scalar(0));
    cv::Mat support, warped_support;
    map_mask.convertTo(support, CV_32F, 1.0 / 255.0);
    cv::warpAffine(support, warped_support, anchor_to_current, size, cv::INTER_LINEAR,
        cv::BORDER_CONSTANT, cv::Scalar(0));
    cv::Mat valid = warped_support >= 1.0f;
    cv::bitwise_and(valid, map_mask, valid);
    return valid;
}

BigMapSessionResult BigMapSession::snapshot() const {
    BigMapSessionResult result;
    result.geometry = geometry_anchor_;
    result.phase = anchor_gray_.empty() ? "acquiring" : "tracking";
    result.absolute_attempted = false;
    result.reset_reason = "none";
    result.anchor_expired = false;
    result.anchor_age_seconds = -1.0;
    result.observation_gap_seconds = -1.0;
    result.since_attempt_seconds = -1.0;
    result.movement_px = -1.0;
    result.stable_frames = stable_frames_;
    result.follow.outcome = "not_attempted";
    result.follow.geometry_accepted = false;
    result.snapshot_revision = snapshot_revision_;
    return result;
}

int BigMapSession::snapshot_revision() const {
    return snapshot_revision_;
}

void BigMapSession::reset() {
    renewal_valid_ = false; // Never wait on an in-flight search here; only the owner can adopt it.
    confirmed_anchor_ = false;
    last_refresh_ = -1;
    anchor_gray_.release();
    anchor_descriptors_.release();
    anchor_keypoints_.clear();
    anchor_ = {};
    geometry_anchor_ = {};
    previous_transform_.release();
    previous_timestamp_ = -1;
    last_attempt_ = -1;
    frame_size_ = {};
    stable_frames_ = 0;
    appearance_suspended_ = false;
    suspended_still_frames_ = 0;
    last_motion_timestamp_ = -1.0;
    not_ok_counter_ = 0;
    acquisition_gray_.release();
    acquisition_keypoints_.clear();
    acquisition_descriptors_.release();
    acquisition_timestamp_ = -1.0;
    acquisition_last_timestamp_ = -1.0;
    acquisition_still_frames_ = 0;
    ++snapshot_revision_;
}

void BigMapSession::renew(const cv::Mat& frame, const cv::Mat& small,
    const std::vector<std::shared_ptr<LayerReference>>& refs, BigMapSessionResult& result, bool debug_points) {
    if (!refresh_search_) return;
    const double now = previous_timestamp_;
    // At most one speculative absolute search process-wide, even across rapid Stop/Start.
    static std::atomic<bool> worker_busy{false};
    if (renewal_.valid()) {
        if (renewal_.wait_for(std::chrono::seconds(0)) != std::future_status::ready) return;
        Renewal candidate;
        try { candidate = renewal_.get(); } catch (const std::exception&) { renewal_valid_ = false; }
        const bool valid = renewal_valid_;
        renewal_valid_ = false;
        result.refresh = "discarded";
        if (!valid) return;
        const auto& g = candidate.absolute;
        // This was a complete absolute search on a frame the old anchor also verified.
        // A conflicting/ambiguous result is evidence against the old fix, not a fallback.
        auto conflict = [&] {
            reset();
            result.geometry = {};
            result.measured_motion = false;
            result.follow.debug_points.clear();
            result.phase = "acquiring";
            result.refresh = "conflict";
        };
        if (g.mode != "found" || g.timestamp_seconds != candidate.expected.timestamp_seconds ||
            !equivalentBigMapGeometry(candidate.expected, g)) { conflict(); return; }
        if (candidate.gray.empty() || !renewal_can_adopt_ || result.phase != "tracking" || now <= g.timestamp_seconds ||
            now - g.timestamp_seconds > 10.0) return;
        BigMapFollowStats check;
        cv::Mat step;
        if (!follow(candidate.gray, candidate.points, candidate.descriptors, small, step, check, debug_points)) return;
        auto fresh = followed(g, step, small.size(), now);
        if (fresh.mode != "found" || !equivalentBigMapGeometry(result.geometry, fresh)) {
            conflict();
            return;
        }
        // Two independent absolute origins agreed on this fresh frame. Keep the NEW
        // origin timestamp; it is neither the completion time nor the publication time.
        anchor_ = geometry_anchor_ = g;
        anchor_gray_ = std::move(candidate.gray);
        anchor_keypoints_ = std::move(candidate.points);
        anchor_descriptors_ = std::move(candidate.descriptors);
        previous_transform_ = step;
        result.geometry = std::move(fresh);
        result.follow.debug_points = std::move(check.debug_points);
        result.refresh = "adopted";
        return;
    }
    // Budget for the observed 1.5–2.4 s SIFT and a fresh confirmation before the 10 s cutoff.
    if (result.phase != "tracking" || result.anchor_age_seconds < 6.0 || result.anchor_age_seconds > 8.0 ||
        (last_refresh_ >= 0 && now - last_refresh_ < 2.0)) return;
    last_refresh_ = now;
    try {
        if (worker_busy.exchange(true)) return;
        // RAII releases on completion, frame allocation failure or async launch failure.
        auto release = [](std::atomic<bool>* busy) { busy->store(false); };
        std::unique_ptr<std::atomic<bool>, decltype(release)> ticket(&worker_busy, release);
        // One owned frame, one asynchronous job, no backlog. No worker access to this,
        // the Java Bitmap, JNI handles or live session state. Future destruction joins.
        renewal_ = std::async(std::launch::async, [search = refresh_search_, image = frame.clone(),
            gray = small, refs, expected = result.geometry, ticket = std::move(ticket)]() mutable {
            auto running = std::move(ticket); // release at function exit, even if future isn't collected yet
            Renewal r;
            r.expected = expected;
            r.absolute = search(image, refs, expected.timestamp_seconds, nullptr);
            if (r.absolute.mode == "found") {
                // Even if preparing the next ORB anchor fails, return the absolute
                // decision so the owner still sees contradictions on this lineage.
                try {
                    features(gray, r.points, r.descriptors);
                    r.gray = gray;
                } catch (const std::exception&) { r.gray.release(); }
            }
            return r;
        });
        renewal_valid_ = true;
        renewal_can_adopt_ = true;
        result.refresh = "started";
    } catch (const std::exception&) { result.refresh = "discarded"; }
}

BigMapSessionResult BigMapSession::update(const cv::Mat& frame,
    const std::vector<std::shared_ptr<LayerReference>>& refs, double timestamp,
    BigMapSessionDiagnosticSink before_absolute, void* diagnostic_context, bool debug_points) try {
    BigMapSessionResult result;
    if (std::isfinite(timestamp)) {
        if (!anchor_gray_.empty()) result.anchor_age_seconds = timestamp - anchor_.timestamp_seconds;
        if (previous_timestamp_ >= 0) result.observation_gap_seconds = timestamp - previous_timestamp_;
        if (last_attempt_ >= 0) result.since_attempt_seconds = timestamp - last_attempt_;
    }
    if (frame.empty() || frame.cols <= frame.rows || (frame.type() != CV_8UC1 && frame.type() != CV_8UC3) ||
        !std::isfinite(timestamp) || timestamp < 0 || (previous_timestamp_ >= 0 && timestamp <= previous_timestamp_)) {
        reset();
        result.reset_reason = "invalid_input_or_timestamp";
        result.phase = "rejected";
        return result;
    }
    if (frame_size_ != frame.size() || (previous_timestamp_ >= 0 && timestamp - previous_timestamp_ > 10.0)) {
        result.reset_reason = frame_size_ != frame.size() ? "frame_size" : "observation_gap";
        reset();
    }
    frame_size_ = frame.size();
    previous_timestamp_ = timestamp;
    auto current = smallGray(frame);

    // Track-anchor follow always measures against the FIXED absolute-registration frame (anchor_),
    // never against the previous frame. This prevents rolling-reference drift and lets the
    // session recover against the same anchor after a pan stops.
    if (!anchor_gray_.empty() && timestamp - anchor_.timestamp_seconds <= 10.0 &&
        !anchor_.matrix.empty()) {
        const auto start = Clock::now();
        cv::Mat transform;
        const bool ok = follow(anchor_gray_, anchor_keypoints_, anchor_descriptors_,
            current, transform, result.follow, debug_points);
        result.follow_ms = std::chrono::duration<double, std::milli>(Clock::now() - start).count();
        const bool movement_measured = result.follow.geometry_accepted && !previous_transform_.empty();
        const double movement = movement_measured ?
            cornerDistance(transform, previous_transform_, current.size()) : 100.0;
        if (movement_measured) result.movement_px = movement;

        // Image-only gates run AFTER the sparse geometry was accepted, so a pixel failure always
        // carries a valid `transform`. Controls are evaluated last and stay fatal.
        const bool pixel_failure = std::strcmp(result.follow.outcome, "dense_change") == 0 ||
            std::strcmp(result.follow.outcome, "tile_change") == 0;
        const bool appearance_failure = pixel_failure ||
            std::strcmp(result.follow.outcome, "controls_change") == 0;
        const bool recent_motion = last_motion_timestamp_ >= 0 &&
            timestamp - last_motion_timestamp_ <= kMotionGraceSeconds;

        if (ok && !appearance_failure) {
            // Only a complete follow against the absolute anchor can measure current motion.
            previous_transform_ = transform.clone();
            if (movement > kMotionPx) last_motion_timestamp_ = timestamp;
            stable_frames_ = movement <= 0.6 ? stable_frames_ + 1 : 0;
            result.movement_px = movement;
            result.stable_frames = stable_frames_;
            suspended_still_frames_ = 0;
            not_ok_counter_ = 0;
            appearance_suspended_ = false;
            result.appearance_suspended = false;
            result.phase = stable_frames_ >= 2 ? "tracking" : movement <= 0.6 ? "confirming" : "moving";
            result.geometry = followed(geometry_anchor_, transform, current.size(), timestamp);
            if (result.geometry.mode != "found") {
                reset();
                result.reset_reason = "composed_transform";
                result.geometry = {};
                result.phase = "rejected";
                return result;
            }
            if (result.phase == "tracking") {
                confirmed_anchor_ = true;
            } else {
                renewal_can_adopt_ = false;
                result.measured_motion = confirmed_anchor_ && result.phase == "moving";
            }
            // Movement disqualifies adoption, not contradictory absolute evidence about this anchor.
            renew(frame, current, refs, result, debug_points);
            ++snapshot_revision_;
            return result;
        }

        // Motion tolerance (experimental). During a pan the game redraws tiles, loads detail and the
        // world edge shimmers, so the pixel gates fail while the sparse geometry is excellent
        // (logs 19-20 Nov: 480-706 inliers, 0.4-0.75 px error on every rejected moving frame).
        // Skip ONLY the dense/tile gates, and only when all of this holds: the anchor was confirmed
        // on stationary frames, the sparse gates above passed in full, the map is measured as moving
        // (or the last measured motion was < kMotionGraceSeconds ago), and the screen-fixed controls
        // are unchanged. A stationary frame is judged exactly as before. Phase stays "moving", so a
        // tolerated frame can never reach "tracking" or confirm an anchor.
        bool tolerate = false;
        if (pixel_failure && confirmed_anchor_ && movement_measured && (movement > kMotionPx || recent_motion)) {
            result.follow.pixel_outcome = result.follow.outcome; // controlsUnchanged() may overwrite outcome
            cv::Mat map_anchor;
            cv::warpAffine(anchor_gray_, map_anchor, transform, current.size(), cv::INTER_LINEAR);
            tolerate = controlsUnchanged(anchor_gray_, current, result.follow, map_anchor);
        }
        if (tolerate) {
            previous_transform_ = transform.clone();
            if (movement > kMotionPx) last_motion_timestamp_ = timestamp;
            stable_frames_ = 0;
            suspended_still_frames_ = 0;
            not_ok_counter_ = 0;
            appearance_suspended_ = false;
            result.appearance_suspended = false;
            result.appearance_tolerated = true;
            result.stable_frames = 0;
            result.phase = "moving";
            result.geometry = followed(geometry_anchor_, transform, current.size(), timestamp);
            if (result.geometry.mode != "found") {
                reset();
                result.reset_reason = "composed_transform";
                result.geometry = {};
                result.appearance_tolerated = false;
                result.phase = "rejected";
                return result;
            }
            renewal_can_adopt_ = false;
            result.measured_motion = true; // confirmed_anchor_ is required above
            renew(frame, current, refs, result, debug_points);
            ++snapshot_revision_;
            return result;
        }

        // A failed follow may retain its private anchor for retry, never current-frame geometry.
        // Only a failed image comparison on a geometry-accepted frame casts doubt on the anchor's
        // appearance; a sparse-match failure is blur/occlusion and says nothing about it.
        if (appearance_failure) confirmed_anchor_ = false;
        renewal_can_adopt_ = false;
        // Right after a measured pan, blur and duplicated captures are expected and do not spend a
        // strike. Control changes (menu, floor switch) and stationary failures always count.
        const bool controls_failure = std::strcmp(result.follow.outcome, "controls_change") == 0;
        if (!(recent_motion && !controls_failure)) not_ok_counter_++;
        if (not_ok_counter_ >= MAX_NOT_OK) {
            renewal_valid_ = false;
            // Discard anchor but preserve the SIFT cooldown and observation timestamp.
            // A full reset() would forget last_attempt_ and let SIFT re-run too soon.
            anchor_gray_.release();
            anchor_descriptors_.release();
            anchor_keypoints_.clear();
            anchor_ = {};
            geometry_anchor_ = {};
            previous_transform_.release();
            stable_frames_ = 0;
            appearance_suspended_ = false;
            suspended_still_frames_ = 0;
            last_motion_timestamp_ = -1.0;
            not_ok_counter_ = 0;
            ++snapshot_revision_;
            result.reset_reason = "follow_failed";
            result.phase = "acquiring";
            return result;
        }
        stable_frames_ = 0;
        appearance_suspended_ = true;
        result.appearance_suspended = true;
        result.suspended_still_frames = suspended_still_frames_;
        result.reset_reason = "follow_failed";
        result.phase = movement > 0.6 ? "moving" : "confirming";
        return result;
    }

    // No valid anchor: expiry tracking.
    renewal_valid_ = false;
    confirmed_anchor_ = false;
    if (renewal_.valid()) {
        // Do not run a synchronous fallback SIFT alongside an obsolete refresh.
        if (renewal_.wait_for(std::chrono::seconds(0)) != std::future_status::ready) {
            result.anchor_expired = !anchor_gray_.empty() && timestamp - anchor_.timestamp_seconds > 10.0;
            return result;
        }
        try { renewal_.get(); } catch (const std::exception&) { /* normal reacquisition below */ }
    }
    result.anchor_expired = !anchor_gray_.empty() && timestamp - anchor_.timestamp_seconds > 10.0;

    bool settled = false;
    if (!acquisition_gray_.empty() && timestamp - acquisition_last_timestamp_ <= 1.0) {
        cv::Mat step;
        const auto start = Clock::now();
        const bool accepted = follow(acquisition_gray_, acquisition_keypoints_, acquisition_descriptors_,
            current, step, result.acquisition_follow);
        result.acquisition_follow_ms = std::chrono::duration<double, std::milli>(Clock::now() - start).count();
        if (result.acquisition_follow.geometry_accepted) {
            const cv::Mat identity = (cv::Mat_<double>(2, 3) << 1, 0, 0, 0, 1, 0);
            result.acquisition_movement_px = cornerDistance(step, identity, current.size());
        }
        settled = accepted && result.acquisition_movement_px <= 0.6;
    }
    if (settled) {
        acquisition_still_frames_ = std::min(2, acquisition_still_frames_ + 1);
        result.acquisition_still_seconds = timestamp - acquisition_timestamp_;
    } else {
        acquisition_gray_ = current;
        features(current, acquisition_keypoints_, acquisition_descriptors_);
        acquisition_timestamp_ = timestamp;
        acquisition_still_frames_ = 0;
    }
    acquisition_last_timestamp_ = timestamp;
    result.acquisition_still_frames = acquisition_still_frames_;
    result.absolute_throttled = last_attempt_ >= 0 && timestamp - last_attempt_ < 2.0;
    if (acquisition_still_frames_ < 2 || result.acquisition_still_seconds < 0.5 || result.absolute_throttled) {
        result.phase = "acquiring";
        return result;
    }
    result.absolute_attempted = true;
    last_attempt_ = timestamp;
    if (before_absolute) before_absolute(result, diagnostic_context);
    auto absolute = registerBigMap(frame, refs, timestamp, &result.registration);
    result.phase = absolute.mode;
    if (absolute.mode != "found") {
        result.geometry = absolute;
        return result;
    }
    anchor_ = absolute;
    geometry_anchor_ = absolute;
    anchor_gray_ = current;
    features(current, anchor_keypoints_, anchor_descriptors_);
    previous_transform_ = (cv::Mat_<double>(2, 3) << 1, 0, 0, 0, 1, 0);
    stable_frames_ = 0;
    appearance_suspended_ = false;
    suspended_still_frames_ = 0;
    last_motion_timestamp_ = -1.0;
    not_ok_counter_ = 0;
    acquisition_gray_.release();
    acquisition_keypoints_.clear();
    acquisition_descriptors_.release();
    acquisition_timestamp_ = -1.0;
    acquisition_last_timestamp_ = -1.0;
    acquisition_still_frames_ = 0;
    result.phase = "confirming";
    ++snapshot_revision_;
    return result;
} catch (...) {
    reset();
    throw;
}
} // namespace luxin
