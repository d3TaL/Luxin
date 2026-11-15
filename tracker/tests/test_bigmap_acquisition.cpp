#include "luxin/bigmap_session.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <memory>
#include <string>
#include <vector>
#include <opencv2/imgproc.hpp>

using namespace luxin;

namespace {

// Keep checks active even if this standalone target is built with NDEBUG.
#define REQUIRE(condition) do { \
    if (!(condition)) { \
        std::cerr << __func__ << ':' << __LINE__ << ": " #condition "\n"; \
        std::abort(); \
    } \
} while (false)

cv::Mat identity() {
    return (cv::Mat_<double>(2, 3) << 1, 0, 0, 0, 1, 0);
}

struct Fixture {
    const cv::Size size{1560, 720};
    const double units_per_px = 2.0;
    const double origin_x = 1000.0;
    const double origin_y = -400.0;
    cv::Mat texture;
    cv::Mat map;
    std::shared_ptr<LayerReference> reference;
    std::vector<std::shared_ptr<LayerReference>> refs;

    Fixture() : texture(720, 1100, CV_8UC1) {
        cv::RNG random(42);
        random.fill(texture, cv::RNG::UNIFORM, 0, 255);
        cv::GaussianBlur(texture, texture, cv::Size(5, 5), 1);
        cv::Mat mask(texture.size(), CV_8UC1, cv::Scalar(255));
        reference = std::make_shared<LayerReference>("acquisition", "synthetic-space", "surface",
            texture, mask, mask, units_per_px, origin_x, origin_y);
        refs = {reference};
        const cv::Mat pose = (cv::Mat_<double>(2, 3) << 0.9, 0, 180, 0, 0.9, 40);
        cv::warpAffine(texture, map, pose, size);
    }

    cv::Mat frame(const cv::Mat& motion = identity()) const {
        cv::Mat image;
        cv::warpAffine(map, image, motion, size);
        // Paint screen-fixed UI AFTER moving only the map. Neither controls nor the
        // slider may accidentally provide stationary features on a moving texture.
        image(cv::Rect(0, 0, 380, 260)).setTo(40);
        image(cv::Rect(70, 255, 60, 210)).setTo(240);
        image(cv::Rect(730, 0, 830, 70)).setTo(40);
        image(cv::Rect(1010, 0, 550, 720)).setTo(30);
        image(cv::Rect(595, 635, 965, 85)).setTo(40);
        image(cv::Rect(70, 635, 70, 70)).setTo(40);
        return image;
    }
};

void requireHidden(const BigMapSessionResult& result) {
    REQUIRE(result.geometry.matrix.empty());
    REQUIRE(result.geometry.mode != "found");
}

void requireNoSift(const BigMapSessionResult& result) {
    requireHidden(result);
    REQUIRE(!result.absolute_attempted);
    REQUIRE(result.registration.query_features == 0);
    REQUIRE(result.registration.extraction_width == 0);
    REQUIRE(result.registration.extraction_height == 0);
}

void requireAcquiring(const BigMapSessionResult& result, int still_frames) {
    requireNoSift(result);
    REQUIRE(result.phase == "acquiring");
    REQUIRE(result.acquisition_still_frames == still_frames);
    REQUIRE(std::isfinite(result.acquisition_still_seconds) && result.acquisition_still_seconds >= 0.0);
    if (still_frames == 0) REQUIRE(result.acquisition_still_seconds == 0.0);
}

void requireFullFollow(const BigMapFollowStats& follow) {
    REQUIRE(follow.geometry_accepted);
    REQUIRE(std::string(follow.outcome) == "accepted");
    REQUIRE(follow.matches >= 24 && follow.inliers >= 24);
    REQUIRE(follow.inlier_ratio >= 0.65);
    REQUIRE(follow.mean_error_px >= 0.0 && follow.mean_error_px <= 0.8);
    REQUIRE(follow.overlap_fraction >= 0.20);
    REQUIRE(follow.changed_fraction >= 0.0 && follow.changed_fraction <= 0.025);
    REQUIRE(follow.tiles_evaluated > 0);
    REQUIRE(follow.worst_tile_changed_fraction >= 0.0 && follow.worst_tile_changed_fraction <= 0.12);
    for (const auto& control : follow.controls) {
        REQUIRE(control.pixels > 0);
        REQUIRE(control.mean_delta >= 0.0 && control.mean_delta <= 6.0);
        REQUIRE(control.changed_fraction >= 0.0 && control.changed_fraction <= 0.04);
    }
}

void requireStillStep(const BigMapSessionResult& result, int still_frames, double seconds = -1.0) {
    REQUIRE(result.acquisition_still_frames == still_frames);
    requireFullFollow(result.acquisition_follow);
    REQUIRE(std::isfinite(result.acquisition_movement_px));
    REQUIRE(result.acquisition_movement_px >= 0.0 && result.acquisition_movement_px <= 0.6);
    REQUIRE(std::isfinite(result.acquisition_follow_ms) && result.acquisition_follow_ms >= 0.0);
    if (seconds >= 0.0) REQUIRE(std::abs(result.acquisition_still_seconds - seconds) < 1e-9);
}

void requireCandidate(const BigMapSessionResult& result) {
    requireHidden(result);
    REQUIRE(result.absolute_attempted);
    REQUIRE(!result.absolute_throttled);
    REQUIRE(result.phase == "confirming");
    REQUIRE(result.registration.query_features > 0);
    REQUIRE(result.stable_frames == 0);
    REQUIRE(std::isfinite(result.acquisition_still_seconds) && result.acquisition_still_seconds >= 0.5);
    requireStillStep(result, 2);
}

// Check actual points, not just a plausible scale: Kongying units -> reference px
// -> base map px -> captured frame px. Origin and units/px are deliberately nonzero.
void requireGeometry(const BigMapSessionResult& result, const Fixture& fixture,
    const cv::Mat& motion, double timestamp, const std::string& space = "synthetic-space",
    double units_per_px = 2.0, double origin_x = 1000.0, double origin_y = -400.0) {
    REQUIRE(result.phase == "tracking" && result.geometry.mode == "found");
    REQUIRE(!result.absolute_attempted);
    REQUIRE(result.stable_frames >= 2);
    REQUIRE(result.geometry.space == space);
    REQUIRE(result.geometry.frame_width == fixture.size.width);
    REQUIRE(result.geometry.frame_height == fixture.size.height);
    REQUIRE(result.geometry.timestamp_seconds == timestamp);
    REQUIRE(result.geometry.matrix.size() == cv::Size(3, 2));
    REQUIRE(result.geometry.matrix.type() == CV_64FC1);
    REQUIRE(cv::checkRange(result.geometry.matrix));
    requireFullFollow(result.follow);
    for (const auto& pixel : std::vector<cv::Point2d>{{250, 300}, {700, 300}, {450, 600}, {850, 600}}) {
        const double world_x = origin_x + pixel.x * units_per_px;
        const double world_y = origin_y + pixel.y * units_per_px;
        const double base_x = 0.9 * pixel.x + 180;
        const double base_y = 0.9 * pixel.y + 40;
        const double expected_x = motion.at<double>(0, 0) * base_x + motion.at<double>(0, 1) * base_y + motion.at<double>(0, 2);
        const double expected_y = motion.at<double>(1, 0) * base_x + motion.at<double>(1, 1) * base_y + motion.at<double>(1, 2);
        const auto& matrix = result.geometry.matrix;
        const double actual_x = matrix.at<double>(0, 0) * world_x + matrix.at<double>(0, 1) * world_y + matrix.at<double>(0, 2);
        const double actual_y = matrix.at<double>(1, 0) * world_x + matrix.at<double>(1, 1) * world_y + matrix.at<double>(1, 2);
        REQUIRE(std::hypot(actual_x - expected_x, actual_y - expected_y) < 2.0);
    }
}

void requireTwoAnchorChecks(BigMapSession& session, const Fixture& fixture, const cv::Mat& frame,
    const cv::Mat& motion, double timestamp) {
    const auto first = session.update(frame, fixture.refs, timestamp);
    requireNoSift(first);
    REQUIRE(first.phase == "confirming" && first.stable_frames == 1);
    requireFullFollow(first.follow);
    const auto second = session.update(frame, fixture.refs, timestamp + 0.1);
    requireGeometry(second, fixture, motion, timestamp + 0.1);
}

// Only fixture setup for tests whose subject is a subsequent reset/expiry. The
// first-acquisition and motion tests below spell out each observation independently.
void establishCandidate(BigMapSession& session, const Fixture& fixture, const cv::Mat& frame, double time) {
    const auto initial = session.update(frame, fixture.refs, time);
    requireAcquiring(initial, 0);
    REQUIRE(initial.acquisition_movement_px == -1.0);
    const auto partial = session.update(frame, fixture.refs, time + 0.1);
    requireAcquiring(partial, 1);
    requireStillStep(partial, 1, 0.1);
    const auto candidate = session.update(frame, fixture.refs, time + 0.5);
    requireCandidate(candidate);
}

void testStationaryCadence(const Fixture& fixture, int fps) {
    BigMapSession session;
    const auto frame = fixture.frame();
    const auto first = session.update(frame, fixture.refs, 1.0);
    requireAcquiring(first, 0);
    REQUIRE(std::string(first.acquisition_follow.outcome) == "not_attempted");
    REQUIRE(first.acquisition_movement_px == -1.0);
    REQUIRE(first.acquisition_still_seconds == 0.0);
    const int candidate_step = (fps + 1) / 2;
    for (int step = 1; step <= candidate_step; ++step) {
        const double elapsed = double(step) / fps;
        const auto result = session.update(frame, fixture.refs, 1.0 + elapsed);
        const int count = step == 1 ? 1 : 2;
        requireStillStep(result, count, elapsed);
        if (elapsed < 0.5) {
            requireAcquiring(result, count);
            REQUIRE(result.acquisition_still_seconds < 0.5);
        } else {
            requireCandidate(result);
        }
    }
    // At 60fps, checks #1/#2 are only 16.7/33.3ms and #29 is 483ms:
    // all remain hidden without SIFT despite the saturated two-pass counter.
    // At 5fps, two passes at 400ms are likewise insufficient; the third at 600ms wins.
    requireTwoAnchorChecks(session, fixture, frame, identity(), 1.1 + double(candidate_step) / fps);
}

void testExactDwellBoundary(const Fixture& fixture) {
    BigMapSession session;
    const auto frame = fixture.frame();
    requireAcquiring(session.update(frame, fixture.refs, 2.0), 0);
    const auto first_pass = session.update(frame, fixture.refs, 2.125);
    requireAcquiring(first_pass, 1);
    requireStillStep(first_pass, 1, 0.125);
    const auto before = session.update(frame, fixture.refs, std::nextafter(2.5, 0.0));
    requireAcquiring(before, 2);
    requireStillStep(before, 2);
    REQUIRE(before.acquisition_still_seconds < 0.5);
    const auto boundary = session.update(frame, fixture.refs, 2.5);
    requireCandidate(boundary);
    REQUIRE(boundary.acquisition_still_seconds == 0.5);
    requireTwoAnchorChecks(session, fixture, frame, identity(), 2.6);
}

void testContinuousMotion(const Fixture& fixture, bool zoom) {
    BigMapSession session;
    cv::Mat frame, motion;
    int sparse_accepted = 0;
    for (int step = 0; step <= 8; ++step) {
        const double scale = zoom ? 1.0 + step * 0.015 : 1.0;
        motion = (cv::Mat_<double>(2, 3) << scale, 0, zoom ? 780 * (1 - scale) : step * 8,
            0, scale, zoom ? 360 * (1 - scale) : 0);
        frame = fixture.frame(motion);
        const auto result = session.update(frame, fixture.refs, 2.0 + step * 0.4);
        requireAcquiring(result, 0); // continuous motion for >2s is not a cooldown-only success
        REQUIRE(!result.absolute_throttled);
        if (step && result.acquisition_follow.geometry_accepted) {
            ++sparse_accepted;
            REQUIRE(result.acquisition_movement_px > 0.6);
        }
    }
    REQUIRE(sparse_accepted >= 2); // exercise actual valid sparse motion, not only failed matches
    const auto still1 = session.update(frame, fixture.refs, 5.3);
    requireAcquiring(still1, 1);
    requireStillStep(still1, 1, 0.1);
    const auto still2 = session.update(frame, fixture.refs, 5.4);
    requireAcquiring(still2, 2);
    requireStillStep(still2, 2, 0.2);
    requireCandidate(session.update(frame, fixture.refs, 5.8));
    requireTwoAnchorChecks(session, fixture, frame, motion, 5.9);
}

void testSlowCumulativeMotion(const Fixture& fixture, bool zoom) {
    BigMapSession session;
    const double interval = 1.0 / 60.0;
    const double radial_corner_px = std::hypot(780.0, 360.0);
    const double scale_step = 0.0003;
    const double pan_step_px = 0.25;
    REQUIRE((zoom ? radial_corner_px * scale_step : pan_step_px) < 0.6);
    int origin_step = 0;
    int measured_total_resets = 0;
    int passed_before_reset = 0;
    int resets_after_pass = 0;
    int full_passes = 0;
    for (int step = 0; step <= 64; ++step) {
        const double scale = zoom ? 1.0 + step * scale_step : 1.0;
        const cv::Mat motion = (cv::Mat_<double>(2, 3) << scale, 0,
            zoom ? 780 * (1 - scale) : step * pan_step_px, 0, scale, zoom ? 360 * (1 - scale) : 0);
        const auto result = session.update(fixture.frame(motion), fixture.refs, 4.0 + step * interval);
        requireNoSift(result);
        REQUIRE(result.phase == "acquiring" && !result.absolute_throttled);
        REQUIRE(result.acquisition_still_seconds < 0.5);
        if (result.acquisition_still_frames == 0) {
            REQUIRE(result.acquisition_still_seconds == 0.0);
            // Require real FULL accepted follow with measured excessive TOTAL motion.
            // Sparse/descriptor/appearance failures alone cannot satisfy this regression.
            const double known_total = zoom ? radial_corner_px *
                (scale / (1.0 + origin_step * scale_step) - 1.0) : (step - origin_step) * pan_step_px;
            if (result.acquisition_follow.geometry_accepted &&
                std::string(result.acquisition_follow.outcome) == "accepted" &&
                result.acquisition_movement_px > 0.6 && known_total > 0.6) {
                ++measured_total_resets;
                if (passed_before_reset > 0) ++resets_after_pass;
            }
            origin_step = step;
            passed_before_reset = 0;
        } else {
            ++full_passes;
            ++passed_before_reset;
            const int count = passed_before_reset == 1 ? 1 : 2;
            requireAcquiring(result, count);
            requireStillStep(result, count, (step - origin_step) * interval);
        }
    }
    REQUIRE(64 * interval > 0.5);
    REQUIRE(full_passes >= 4);
    REQUIRE(measured_total_resets >= 2);
    REQUIRE(resets_after_pass >= 2);
}

void testGradualControlChange(const Fixture& fixture) {
    BigMapSession session;
    const auto frame = fixture.frame();
    int origin_step = 0;
    int control_resets = 0;
    for (int step = 0; step <= 64; ++step) {
        cv::Mat changed = frame.clone();
        // Adjacent frames differ by only 2 grayscale levels: every rolling control
        // comparison would pass. Fixed-origin delta reaches 8 and must fail instead.
        changed(cv::Rect(1395, 250, 85, 225)).setTo(30 + step * 2);
        const auto result = session.update(changed, fixture.refs, 6.0 + step / 60.0);
        requireNoSift(result);
        REQUIRE(result.phase == "acquiring");
        if (step == 0) {
            requireAcquiring(result, 0);
        } else if (step - origin_step == 4) {
            requireAcquiring(result, 0);
            REQUIRE(result.acquisition_follow.geometry_accepted);
            REQUIRE(result.acquisition_movement_px >= 0.0 && result.acquisition_movement_px <= 0.6);
            REQUIRE(std::string(result.acquisition_follow.outcome) == "controls_change");
            // cv::mean computes sum/area in double; integer source deltas do not
            // require the diagnostic quotient to be bit-identical to 8.0.
            REQUIRE(std::abs(result.acquisition_follow.controls[0].mean_delta - 8.0) < 1e-9);
            origin_step = step;
            ++control_resets;
        } else {
            const int count = step - origin_step == 1 ? 1 : 2;
            requireAcquiring(result, count);
            requireStillStep(result, count, (step - origin_step) / 60.0);
        }
    }
    REQUIRE(control_resets == 16);
}

void testShortPlateauDoesNotFinishGesture(const Fixture& fixture, int fps) {
    BigMapSession session;
    cv::Mat last;
    // Same 9px/s trajectory at 5/30/60Hz, with a real 300ms plateau in its middle.
    // Neither a pair of identical frames nor an incomplete dwell may capture it.
    for (int i = 0; i <= fps * 2; ++i) {
        const double elapsed = double(i) / fps;
        const double progress = std::min(elapsed, 0.6) + std::max(0.0, elapsed - 0.9);
        const cv::Mat motion = (cv::Mat_<double>(2, 3) << 1, 0, 9 * progress, 0, 1, 0);
        last = fixture.frame(motion);
        const auto result = session.update(last, fixture.refs, 8.0 + elapsed);
        requireNoSift(result);
        REQUIRE(result.phase == "acquiring");
        REQUIRE(result.acquisition_still_seconds < 0.5);
    }
    requireNoSift(session.update(last, fixture.refs, 10.1));
    requireNoSift(session.update(last, fixture.refs, 10.4));
    requireCandidate(session.update(last, fixture.refs, 10.6));
}

void testPartialMotionReset(const Fixture& fixture) {
    BigMapSession session;
    const auto frame = fixture.frame();
    requireAcquiring(session.update(frame, fixture.refs, 1.0), 0);
    requireAcquiring(session.update(frame, fixture.refs, 1.1), 1);
    const cv::Mat motion = (cv::Mat_<double>(2, 3) << 1, 0, 8, 0, 1, 0);
    const auto shifted = fixture.frame(motion);
    const auto moving = session.update(shifted, fixture.refs, 1.2);
    requireAcquiring(moving, 0);
    REQUIRE(moving.acquisition_follow.geometry_accepted && moving.acquisition_movement_px > 0.6);
    const auto first = session.update(shifted, fixture.refs, 1.3);
    requireAcquiring(first, 1);
    requireStillStep(first, 1);
    const auto early = session.update(shifted, fixture.refs, 1.6);
    requireAcquiring(early, 2);
    requireStillStep(early, 2, 0.4);
    requireCandidate(session.update(shifted, fixture.refs, 1.8));
}

void testPartialAppearanceReset(const Fixture& fixture, bool control) {
    BigMapSession session;
    const auto frame = fixture.frame();
    requireAcquiring(session.update(frame, fixture.refs, 1.0), 0);
    requireAcquiring(session.update(frame, fixture.refs, 1.1), 1);
    cv::Mat changed = frame.clone();
    changed(control ? cv::Rect(1395, 250, 85, 225) : cv::Rect(630, 360, 90, 90)).setTo(255);
    const auto failed = session.update(changed, fixture.refs, 1.2);
    requireAcquiring(failed, 0);
    REQUIRE(failed.acquisition_follow.geometry_accepted);
    REQUIRE(failed.acquisition_movement_px >= 0.0 && failed.acquisition_movement_px <= 0.6);
    REQUIRE(std::string(failed.acquisition_follow.outcome) == (control ? "controls_change" : "tile_change"));
    // A first full-check failure must cancel an already partial confirmation,
    // even though the large unchanged background still supplies sparse geometry.
    const auto next = session.update(changed, fixture.refs, 1.3);
    requireAcquiring(next, 1);
    requireStillStep(next, 1);
    const auto early = session.update(changed, fixture.refs, 1.6);
    requireAcquiring(early, 2);
    requireStillStep(early, 2, 0.4);
    requireCandidate(session.update(changed, fixture.refs, 1.8));
}

void testBlankCannotSettle(const Fixture& fixture) {
    BigMapSession session;
    const auto frame = fixture.frame();
    const cv::Mat blank(fixture.size, CV_8UC1, cv::Scalar(0));
    requireAcquiring(session.update(frame, fixture.refs, 1.0), 0);
    requireAcquiring(session.update(frame, fixture.refs, 1.1), 1);
    for (int i = 0; i < 4; ++i) {
        const auto result = session.update(blank, fixture.refs, 1.2 + i * 0.1);
        requireAcquiring(result, 0);
        REQUIRE(std::string(result.acquisition_follow.outcome) == "insufficient_descriptors");
        REQUIRE(!result.acquisition_follow.geometry_accepted);
        REQUIRE(result.acquisition_movement_px == -1.0);
    }
    const auto returning = session.update(frame, fixture.refs, 1.6);
    requireAcquiring(returning, 0); // blank -> textured is not a stationary observation
    REQUIRE(!returning.acquisition_follow.geometry_accepted);
    requireAcquiring(session.update(frame, fixture.refs, 1.7), 1);
    requireAcquiring(session.update(frame, fixture.refs, 1.8), 2);
    requireCandidate(session.update(frame, fixture.refs, 2.2));
}

void testAcquisitionGap(const Fixture& fixture) {
    BigMapSession session;
    const auto frame = fixture.frame();
    requireAcquiring(session.update(frame, fixture.refs, 1.0), 0);
    const auto boundary = session.update(frame, fixture.refs, 2.0);
    requireAcquiring(boundary, 1);
    requireStillStep(boundary, 1, 1.0); // >=0.5s alone is insufficient without two passes
    const auto gap = session.update(frame, fixture.refs, 3.01);
    requireAcquiring(gap, 0);
    REQUIRE(std::string(gap.acquisition_follow.outcome) == "not_attempted");
    REQUIRE(gap.acquisition_movement_px == -1.0);
    requireAcquiring(session.update(frame, fixture.refs, 3.11), 1);
    const auto early = session.update(frame, fixture.refs, 3.21);
    requireAcquiring(early, 2);
    requireStillStep(early, 2, 0.2);
    requireCandidate(session.update(frame, fixture.refs, 3.61));
}

void testExplicitReset(const Fixture& fixture) {
    const auto frame = fixture.frame();
    for (bool hidden_candidate : {false, true}) {
        BigMapSession session;
        requireAcquiring(session.update(frame, fixture.refs, 1.0), 0);
        requireAcquiring(session.update(frame, fixture.refs, 1.1), 1);
        if (hidden_candidate) requireCandidate(session.update(frame, fixture.refs, 1.5));
        session.reset(); // native reset used for pause; not an asynchronous Android/JNI test
        const auto resumed = session.update(frame, fixture.refs, 2.0);
        requireAcquiring(resumed, 0);
        REQUIRE(std::string(resumed.follow.outcome) == "not_attempted");
        REQUIRE(std::string(resumed.acquisition_follow.outcome) == "not_attempted");
        REQUIRE(resumed.acquisition_movement_px == -1.0);
        requireAcquiring(session.update(frame, fixture.refs, 2.1), 1);
        const auto early = session.update(frame, fixture.refs, 2.4);
        requireAcquiring(early, 2);
        requireStillStep(early, 2, 0.4);
        requireCandidate(session.update(frame, fixture.refs, 2.5));
        requireTwoAnchorChecks(session, fixture, frame, identity(), 2.6);
    }
}

void requireRestartAfterInvalid(BigMapSession& session, const Fixture& fixture, const cv::Mat& frame) {
    const auto first = session.update(frame, fixture.refs, 2.0);
    requireAcquiring(first, 0);
    REQUIRE(std::string(first.follow.outcome) == "not_attempted");
    REQUIRE(std::string(first.acquisition_follow.outcome) == "not_attempted");
    REQUIRE(first.acquisition_movement_px == -1.0);
    requireAcquiring(session.update(frame, fixture.refs, 2.1), 1);
    const auto early = session.update(frame, fixture.refs, 2.4);
    requireAcquiring(early, 2);
    requireStillStep(early, 2, 0.4);
    requireCandidate(session.update(frame, fixture.refs, 2.5));
}

void testInvalidInputsClearPartial(const Fixture& fixture) {
    const auto frame = fixture.frame();
    for (double invalid : {1.1, 1.05, -1.0, std::numeric_limits<double>::quiet_NaN(),
             std::numeric_limits<double>::infinity(), -std::numeric_limits<double>::infinity()}) {
        BigMapSession session;
        requireAcquiring(session.update(frame, fixture.refs, 1.0), 0);
        requireAcquiring(session.update(frame, fixture.refs, 1.1), 1);
        const auto rejected = session.update(frame, fixture.refs, invalid);
        requireNoSift(rejected);
        REQUIRE(rejected.phase == "rejected");
        REQUIRE(std::string(rejected.reset_reason) == "invalid_input_or_timestamp");
        REQUIRE(rejected.acquisition_still_frames == 0);
        requireRestartAfterInvalid(session, fixture, frame);
    }
    for (const cv::Mat& invalid : std::vector<cv::Mat>{cv::Mat(),
             cv::Mat(fixture.size, CV_8UC4, cv::Scalar::all(0)), cv::Mat(720, 640, CV_8UC1, cv::Scalar(0))}) {
        BigMapSession session;
        requireAcquiring(session.update(frame, fixture.refs, 1.0), 0);
        requireAcquiring(session.update(frame, fixture.refs, 1.1), 1);
        const auto rejected = session.update(invalid, fixture.refs, 1.2);
        requireNoSift(rejected);
        REQUIRE(rejected.phase == "rejected");
        REQUIRE(std::string(rejected.reset_reason) == "invalid_input_or_timestamp");
        requireRestartAfterInvalid(session, fixture, frame);
    }
}

void testSizeChange(const Fixture& fixture) {
    const auto frame = fixture.frame();
    cv::Mat resized;
    cv::resize(frame, resized, cv::Size(1300, 600));
    for (bool hidden_candidate : {false, true}) {
        BigMapSession session;
        requireAcquiring(session.update(frame, fixture.refs, 1.0), 0);
        requireAcquiring(session.update(frame, fixture.refs, 1.1), 1);
        if (hidden_candidate) requireCandidate(session.update(frame, fixture.refs, 1.5));
        const auto changed = session.update(resized, fixture.refs, 1.6);
        requireAcquiring(changed, 0);
        REQUIRE(std::string(changed.reset_reason) == "frame_size");
        REQUIRE(std::string(changed.follow.outcome) == "not_attempted");
        REQUIRE(std::string(changed.acquisition_follow.outcome) == "not_attempted");
        requireAcquiring(session.update(resized, fixture.refs, 1.7), 1);
        const auto early = session.update(resized, fixture.refs, 1.8);
        requireAcquiring(early, 2);
        requireStillStep(early, 2, 0.2);
        requireCandidate(session.update(resized, fixture.refs, 2.2));
    }
}

void testAbsoluteExpiry(const Fixture& fixture) {
    BigMapSession session;
    const auto frame = fixture.frame();
    establishCandidate(session, fixture, frame, 10.0);
    requireTwoAnchorChecks(session, fixture, frame, identity(), 10.6);
    for (int i = 1; i <= 12; ++i) {
        const double timestamp = 10.7 + i * 0.8;
        const auto tracked = session.update(frame, fixture.refs, timestamp);
        requireGeometry(tracked, fixture, identity(), timestamp);
        REQUIRE(!tracked.anchor_expired);
    }
    const auto expired = session.update(frame, fixture.refs, 20.6);
    requireAcquiring(expired, 0);
    REQUIRE(expired.anchor_expired && expired.anchor_age_seconds > 10.0);
    REQUIRE(std::string(expired.follow.outcome) == "not_attempted");
    REQUIRE(expired.acquisition_movement_px == -1.0);
    requireAcquiring(session.update(frame, fixture.refs, 20.7), 1);
    const auto early = session.update(frame, fixture.refs, 21.0);
    requireAcquiring(early, 2);
    requireStillStep(early, 2, 0.4);
    requireCandidate(session.update(frame, fixture.refs, 21.2));
    requireTwoAnchorChecks(session, fixture, frame, identity(), 21.3);
}

void testSceneSwitchCannotReuseHiddenCandidate(const Fixture& fixture) {
    BigMapSession session;
    const auto frame = fixture.frame();
    establishCandidate(session, fixture, frame, 1.0);
    cv::Mat switched = frame.clone();
    switched(cv::Rect(630, 360, 90, 90)).setTo(255);
    const std::vector<std::shared_ptr<LayerReference>> no_refs;
    const auto changed = session.update(switched, no_refs, 1.6);
    requireAcquiring(changed, 0);
    REQUIRE(std::string(changed.reset_reason) == "follow_failed");
    REQUIRE(changed.follow.geometry_accepted && std::string(changed.follow.outcome) == "tile_change");
    requireAcquiring(session.update(switched, no_refs, 1.7), 1);
    const auto early = session.update(switched, no_refs, 1.8);
    requireAcquiring(early, 2);
    requireStillStep(early, 2, 0.2);
    const auto settled_but_throttled = session.update(switched, no_refs, 2.2);
    requireAcquiring(settled_but_throttled, 2);
    requireStillStep(settled_but_throttled, 2, 0.6);
    REQUIRE(settled_but_throttled.absolute_throttled);
    requireAcquiring(session.update(switched, no_refs, 3.0), 2);
    const auto independently_rejected = session.update(switched, no_refs, 3.51);
    requireHidden(independently_rejected);
    REQUIRE(independently_rejected.absolute_attempted);
    REQUIRE(independently_rejected.phase == "rejected");
    REQUIRE(independently_rejected.registration.query_features > 0);

    // The same surviving texture can independently register to a new reference.
    // Different space/origin/units make reuse of the hidden old matrix detectable.
    cv::Mat mask(fixture.texture.size(), CV_8UC1, cv::Scalar(255));
    auto replacement = std::make_shared<LayerReference>("replacement", "replacement-space", "other-layer",
        fixture.texture, mask, mask, 3.0, -700.0, 900.0);
    const std::vector<std::shared_ptr<LayerReference>> replacement_refs{replacement};
    for (double timestamp : {3.6, 4.5, 5.5}) {
        const auto waiting = session.update(switched, replacement_refs, timestamp);
        requireAcquiring(waiting, 2);
        REQUIRE(waiting.absolute_throttled);
    }
    const auto replacement_candidate = session.update(switched, replacement_refs, 5.6);
    requireCandidate(replacement_candidate);
    const auto first_check = session.update(switched, replacement_refs, 5.7);
    requireNoSift(first_check);
    REQUIRE(first_check.stable_frames == 1 && first_check.phase == "confirming");
    const auto tracked = session.update(switched, replacement_refs, 5.8);
    requireGeometry(tracked, fixture, identity(), 5.8, "replacement-space", 3.0, -700.0, 900.0);
    REQUIRE(tracked.geometry.reference == "replacement");
}

} // namespace

int main() {
    cv::setNumThreads(1);
    cv::setRNGSeed(42);
    const Fixture fixture;
    for (int fps : {60, 30, 5}) testStationaryCadence(fixture, fps);
    testExactDwellBoundary(fixture);
    testContinuousMotion(fixture, false);
    testContinuousMotion(fixture, true);
    testSlowCumulativeMotion(fixture, false);
    testSlowCumulativeMotion(fixture, true);
    testGradualControlChange(fixture);
    for (int fps : {60, 30, 5}) testShortPlateauDoesNotFinishGesture(fixture, fps);
    testPartialMotionReset(fixture);
    testPartialAppearanceReset(fixture, false);
    testPartialAppearanceReset(fixture, true);
    testBlankCannotSettle(fixture);
    testAcquisitionGap(fixture);
    testExplicitReset(fixture);
    testInvalidInputsClearPartial(fixture);
    testSizeChange(fixture);
    testAbsoluteExpiry(fixture);
    testSceneSwitchCannotReuseHiddenCandidate(fixture);
    std::cout << "big-map acquisition tests passed\n";
}
