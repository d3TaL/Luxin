#include "luxin/bigmap_session.hpp"
#include <array>
#include <cassert>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>
#include <opencv2/imgproc.hpp>

using namespace luxin;

namespace {

struct DiagnosticSinkLog {
    std::array<BigMapSessionResult, 16> observations;
    size_t count = 0;
};

void recordBeforeAbsolute(const BigMapSessionResult& result, void* context) noexcept {
    auto* log = static_cast<DiagnosticSinkLog*>(context);
    if (log && log->count < log->observations.size()) {
        log->observations[log->count++] = result;
    }
}

// Explicit fixture setup: two still checks spanning 0.5s precede the requested SIFT frame.
// Never use this helper to test failure/reset behavior; those transitions are exercised below.
BigMapSessionResult acquireSettled(BigMapSession& session, const cv::Mat& frame,
    const std::vector<std::shared_ptr<LayerReference>>& refs, double timestamp,
    BigMapSessionDiagnosticSink sink = nullptr, void* context = nullptr) {
    const auto first = session.update(frame, refs, timestamp - 0.5, sink, context);
    assert(!first.absolute_attempted && first.geometry.matrix.empty());
    assert(first.acquisition_still_frames == 0);
    const auto second = session.update(frame, refs, timestamp - 0.25, sink, context);
    assert(!second.absolute_attempted && second.geometry.matrix.empty());
    assert(second.acquisition_still_frames == 1);
    auto result = session.update(frame, refs, timestamp, sink, context);
    assert(result.absolute_attempted && result.geometry.matrix.empty());
    assert(result.acquisition_still_frames == 2);
    assert(result.acquisition_still_seconds >= 0.5);
    return result;
}

void assertControlUnvisited(const BigMapControlStats& control) {
    assert(control.rect == cv::Rect());
    assert(control.mean_delta == -1.0);
    assert(control.changed_pixels == -1);
    assert(control.pixels == 0);
    assert(control.changed_fraction == -1.0);
}

void assertTileStatsUnvisited(const BigMapFollowStats& follow) {
    assert(follow.tiles_evaluated == 0);
    assert(follow.worst_tile == cv::Rect());
    assert(follow.worst_tile_valid_pixels == -1);
    assert(follow.worst_tile_changed_pixels == -1);
    assert(follow.worst_tile_changed_fraction == -1.0);
}

void assertFollowNotAttempted(const BigMapFollowStats& follow) {
    assert(!follow.geometry_accepted);
    assert(std::string(follow.outcome) == "not_attempted");
    assert(follow.working_size == cv::Size());
    assert(follow.anchor_keypoints == -1);
    assert(follow.current_keypoints == -1);
    assert(follow.anchor_descriptors == -1);
    assert(follow.current_descriptors == -1);
    assert(follow.matches == -1);
    assert(follow.inliers == -1);
    assert(follow.grid_cells == -1);
    assert(follow.inlier_ratio == -1.0);
    assert(follow.mean_error_px == -1.0);
    assert(follow.scale == -1.0);
    assert(follow.angle_degrees == -1.0);
    assert(follow.hull_fraction == -1.0);
    assert(follow.overlap_pixels == -1);
    assert(follow.overlap_fraction == -1.0);
    assert(follow.changed_pixels == -1);
    assert(follow.changed_fraction == -1.0);
    assertTileStatsUnvisited(follow);
    assertControlUnvisited(follow.controls[0]);
    assertControlUnvisited(follow.controls[1]);
}

void assertFollowInsufficientDescriptors(const BigMapFollowStats& follow, cv::Size working_size) {
    assert(!follow.geometry_accepted);
    assert(std::string(follow.outcome) == "insufficient_descriptors");
    assert(follow.working_size == working_size);
    assert(follow.anchor_keypoints >= 2 && follow.anchor_descriptors == follow.anchor_keypoints);
    assert(follow.current_keypoints >= 0 && follow.current_keypoints < 2);
    assert(follow.current_descriptors >= 0 && follow.current_descriptors < 2);
    assert(follow.matches == -1);
    assert(follow.inliers == -1);
    assert(follow.grid_cells == -1);
    assert(follow.inlier_ratio == -1.0);
    assert(follow.mean_error_px == -1.0);
    assert(follow.scale == -1.0);
    assert(follow.angle_degrees == -1.0);
    assert(follow.hull_fraction == -1.0);
    assert(follow.overlap_pixels == -1);
    assert(follow.overlap_fraction == -1.0);
    assert(follow.changed_pixels == -1);
    assert(follow.changed_fraction == -1.0);
    assertTileStatsUnvisited(follow);
    assertControlUnvisited(follow.controls[0]);
    assertControlUnvisited(follow.controls[1]);
}

void assertFollowAccepted(const BigMapFollowStats& follow, cv::Size working_size) {
    assert(follow.geometry_accepted);
    assert(std::string(follow.outcome) == "accepted");
    assert(follow.working_size == working_size);
    assert(follow.anchor_keypoints >= 2 && follow.anchor_descriptors == follow.anchor_keypoints);
    assert(follow.current_keypoints >= 2 && follow.current_descriptors == follow.current_keypoints);
    assert(follow.matches >= 24);
    assert(follow.inliers >= 24 && follow.inliers <= follow.matches);
    assert(follow.grid_cells >= 4);
    assert(follow.inlier_ratio >= 0.65 && follow.inlier_ratio <= 1.0);
    assert(follow.mean_error_px >= 0.0 && follow.mean_error_px <= 0.8);
    assert(follow.scale >= 0.7 && follow.scale <= 1.4);
    assert(std::abs(follow.angle_degrees) <= 1.0);
    assert(follow.hull_fraction >= 0.015 && follow.hull_fraction <= 1.0);
    assert(follow.overlap_pixels >= working_size.area() * 0.20);
    assert(follow.overlap_fraction >= 0.20 && follow.overlap_fraction <= 1.0);
    assert(follow.changed_pixels >= 0 && follow.changed_pixels <= follow.overlap_pixels * 0.025);
    assert(follow.changed_fraction >= 0.0 && follow.changed_fraction <= 0.025);
    assert(follow.tiles_evaluated > 0);
    assert(follow.worst_tile.area() > 0);
    assert(follow.worst_tile_valid_pixels >= 200);
    assert(follow.worst_tile_changed_pixels >= 0);
    assert(follow.worst_tile_changed_fraction >= 0.0 && follow.worst_tile_changed_fraction <= 0.12);
    for (const auto& control : follow.controls) {
        assert(control.rect.area() > 0);
        assert(control.pixels == control.rect.area());
        assert(control.mean_delta >= 0.0 && control.mean_delta <= 6.0);
        assert(control.changed_pixels >= 0 && control.changed_pixels <= control.pixels * 0.04);
        assert(control.changed_fraction >= 0.0 && control.changed_fraction <= 0.04);
    }
}

void assertControlStatsEqual(const BigMapControlStats& a, const BigMapControlStats& b) {
    assert(a.rect == b.rect);
    assert(a.mean_delta == b.mean_delta);
    assert(a.changed_pixels == b.changed_pixels);
    assert(a.pixels == b.pixels);
    assert(a.changed_fraction == b.changed_fraction);
}

void assertResultsEquivalent(const BigMapSessionResult& a, const BigMapSessionResult& b) {
    assert(a.geometry.mode == b.geometry.mode);
    assert(a.geometry.space == b.geometry.space);
    assert(a.geometry.reference == b.geometry.reference);
    assert(a.geometry.frame_width == b.geometry.frame_width);
    assert(a.geometry.frame_height == b.geometry.frame_height);
    assert(a.geometry.timestamp_seconds == b.geometry.timestamp_seconds);
    assert(a.geometry.inliers == b.geometry.inliers);
    assert(a.geometry.matches == b.geometry.matches);
    assert(a.geometry.error_px == b.geometry.error_px);
    assert(a.geometry.support == b.geometry.support);
    assert(a.geometry.matrix.empty() == b.geometry.matrix.empty());
    if (!a.geometry.matrix.empty()) {
        assert(a.geometry.matrix.size() == b.geometry.matrix.size());
        assert(a.geometry.matrix.type() == b.geometry.matrix.type());
        assert(cv::norm(a.geometry.matrix, b.geometry.matrix, cv::NORM_INF) == 0.0);
    }
    assert(a.phase == b.phase);
    assert(a.absolute_attempted == b.absolute_attempted);
    assert(a.registration.query_features == b.registration.query_features);
    assert(a.registration.extraction_width == b.registration.extraction_width);
    assert(a.registration.extraction_height == b.registration.extraction_height);
    assert(std::string(a.follow.outcome) == std::string(b.follow.outcome));
    assert(a.follow.working_size == b.follow.working_size);
    assert(a.follow.anchor_keypoints == b.follow.anchor_keypoints);
    assert(a.follow.current_keypoints == b.follow.current_keypoints);
    assert(a.follow.anchor_descriptors == b.follow.anchor_descriptors);
    assert(a.follow.current_descriptors == b.follow.current_descriptors);
    assert(a.follow.matches == b.follow.matches);
    assert(a.follow.inliers == b.follow.inliers);
    assert(a.follow.grid_cells == b.follow.grid_cells);
    assert(a.follow.inlier_ratio == b.follow.inlier_ratio);
    assert(a.follow.mean_error_px == b.follow.mean_error_px);
    assert(a.follow.scale == b.follow.scale);
    assert(a.follow.angle_degrees == b.follow.angle_degrees);
    assert(a.follow.hull_fraction == b.follow.hull_fraction);
    assert(a.follow.overlap_pixels == b.follow.overlap_pixels);
    assert(a.follow.overlap_fraction == b.follow.overlap_fraction);
    assert(a.follow.changed_pixels == b.follow.changed_pixels);
    assert(a.follow.changed_fraction == b.follow.changed_fraction);
    assert(a.follow.tiles_evaluated == b.follow.tiles_evaluated);
    assert(a.follow.worst_tile == b.follow.worst_tile);
    assert(a.follow.worst_tile_valid_pixels == b.follow.worst_tile_valid_pixels);
    assert(a.follow.worst_tile_changed_pixels == b.follow.worst_tile_changed_pixels);
    assert(a.follow.worst_tile_changed_fraction == b.follow.worst_tile_changed_fraction);
    assertControlStatsEqual(a.follow.controls[0], b.follow.controls[0]);
    assertControlStatsEqual(a.follow.controls[1], b.follow.controls[1]);
    assert(std::string(a.reset_reason) == std::string(b.reset_reason));
    assert(a.anchor_expired == b.anchor_expired);
    assert(a.anchor_age_seconds == b.anchor_age_seconds);
    assert(a.observation_gap_seconds == b.observation_gap_seconds);
    assert(a.since_attempt_seconds == b.since_attempt_seconds);
    assert(a.movement_px == b.movement_px);
    assert(a.stable_frames == b.stable_frames);
    assert(a.absolute_throttled == b.absolute_throttled);
    assert(a.follow.geometry_accepted == b.follow.geometry_accepted);
    assert(a.appearance_suspended == b.appearance_suspended);
    assert(a.suspended_still_frames == b.suspended_still_frames);
    assert(a.acquisition_movement_px == b.acquisition_movement_px);
    assert(a.acquisition_still_frames == b.acquisition_still_frames);
    assert(a.acquisition_still_seconds == b.acquisition_still_seconds);
    // Compare every acquisition-follow diagnostic through the same equality checks.
    // The recursive calls have default (unvisited) acquisition diagnostics.
    if (a.acquisition_follow.current_keypoints >= 0 || b.acquisition_follow.current_keypoints >= 0) {
        BigMapSessionResult x, y;
        x.follow = a.acquisition_follow;
        y.follow = b.acquisition_follow;
        assertResultsEquivalent(x, y);
    }
}

void assertPreSiftSinkShape(const BigMapSessionResult& observed) {
    assert(observed.absolute_attempted);
    assert(observed.phase == "acquiring");
    assert(!observed.absolute_throttled);
    assert(observed.geometry.mode == "rejected" && observed.geometry.matrix.empty());
    assert(observed.registration.query_features == 0);
    assert(observed.registration.features_ms == 0.0);
    assert(observed.registration.matching_ms == 0.0);
    assert(observed.registration.validation_ms == 0.0);
    assert(observed.registration.extraction_width == 0);
    assert(observed.registration.extraction_height == 0);
    assert(observed.acquisition_still_frames == 2);
    assert(observed.acquisition_still_seconds >= 0.5);
    assertFollowAccepted(observed.acquisition_follow, observed.acquisition_follow.working_size);
    assert(observed.acquisition_movement_px <= 0.6);
}

void testFollowDiagnosticsAndObserver(const cv::Mat& frame, const std::shared_ptr<LayerReference>& ref) {
    cv::setRNGSeed(101);
    BigMapSession session;
    DiagnosticSinkLog sink;

    auto acquired = acquireSettled(session, frame, {ref}, 1.0, recordBeforeAbsolute, &sink);
    assert(sink.count == 1);
    assertPreSiftSinkShape(sink.observations[0]);
    assert(!sink.observations[0].anchor_expired);
    assertFollowNotAttempted(sink.observations[0].follow);
    assert(acquired.phase == "confirming" && acquired.absolute_attempted);
    assert(acquired.registration.query_features > 0);
    assert(acquired.registration.extraction_width == frame.cols && acquired.registration.extraction_height == frame.rows);

    auto accepted = session.update(frame, {ref}, 1.5, recordBeforeAbsolute, &sink);
    assert(sink.count == 1); // No sink invocation when follow succeeds.
    assert(accepted.phase == "confirming" && !accepted.absolute_attempted && !accepted.absolute_throttled);
    assert(!accepted.anchor_expired && std::string(accepted.reset_reason) == "none");
    assert(accepted.anchor_age_seconds == 0.5 && accepted.observation_gap_seconds == 0.5);
    assert(accepted.since_attempt_seconds == 0.5 && accepted.stable_frames == 1);
    assertFollowAccepted(accepted.follow, frame.size());
    assert(accepted.follow.changed_pixels == 0 && accepted.follow.changed_fraction == 0.0);
    assert(accepted.follow.worst_tile_changed_pixels == 0 && accepted.follow.worst_tile_changed_fraction == 0.0);
    assert(accepted.follow.controls[0].mean_delta == 0.0 && accepted.follow.controls[0].changed_pixels == 0);
    assert(accepted.follow.controls[1].mean_delta == 0.0 && accepted.follow.controls[1].changed_pixels == 0);
    const int full_tile_count = accepted.follow.tiles_evaluated;

    auto tracking = session.update(frame, {ref}, 2.0, recordBeforeAbsolute, &sink);
    assert(sink.count == 1);
    assert(tracking.phase == "tracking" && tracking.geometry.mode == "found" && tracking.stable_frames == 2);
    assertFollowAccepted(tracking.follow, frame.size());

    // Deterministic local tile failure on exact unmasked 90x90 grid tile (630, 360).
    cv::Mat tile_edit = frame.clone();
    cv::Mat tile_roi = tile_edit(cv::Rect(630, 360, 90, 90));
    for (int y = 0; y < tile_roi.rows; ++y) {
        auto* row = tile_roi.ptr<uchar>(y);
        for (int x = 0; x < tile_roi.cols; ++x) row[x] = row[x] > 127 ? 0 : 255;
    }
    auto tile_reacquire = session.update(tile_edit, {ref}, 3.5, recordBeforeAbsolute, &sink);
    assert(sink.count == 1); // The failed observation only starts a new settling interval.
    const auto& pre_tile = tile_reacquire;
    assert(!pre_tile.anchor_expired && std::string(pre_tile.reset_reason) == "follow_failed");
    assert(pre_tile.anchor_age_seconds == 2.5 && pre_tile.observation_gap_seconds == 1.5);
    assert(pre_tile.since_attempt_seconds == 2.5);
    assert(std::string(pre_tile.follow.outcome) == "tile_change");
    assert(pre_tile.follow.matches >= 24 && pre_tile.follow.inliers >= 24);
    assert(pre_tile.follow.inlier_ratio >= 0.65 && pre_tile.follow.mean_error_px <= 0.8);
    assert(pre_tile.follow.overlap_fraction >= 0.20 && pre_tile.follow.changed_fraction <= 0.025);
    assert(pre_tile.follow.tiles_evaluated > 0 && pre_tile.follow.tiles_evaluated < full_tile_count);
    assert(pre_tile.follow.worst_tile == cv::Rect(630, 360, 90, 90));
    assert(pre_tile.follow.worst_tile_valid_pixels == 8100);
    assert(pre_tile.follow.worst_tile_changed_pixels > pre_tile.follow.worst_tile_valid_pixels * 0.12);
    assert(pre_tile.follow.worst_tile_changed_fraction > 0.12);
    assertControlUnvisited(pre_tile.follow.controls[0]);
    assertControlUnvisited(pre_tile.follow.controls[1]);
    assert(!tile_reacquire.absolute_attempted && tile_reacquire.geometry.matrix.empty());
    assert(tile_reacquire.phase == "acquiring" && std::string(tile_reacquire.reset_reason) == "follow_failed");
    assert(std::string(tile_reacquire.follow.outcome) == "tile_change");
    assert(!session.update(tile_edit, {ref}, 3.75, recordBeforeAbsolute, &sink).absolute_attempted);
    assert(session.update(tile_edit, {ref}, 4.0, recordBeforeAbsolute, &sink).absolute_attempted);
    assert(sink.count == 2);
    assertPreSiftSinkShape(sink.observations[1]);

    // Textureless failure during 2s cooldown: no sink invocation, later follow stages stay unvisited.
    cv::Mat blank(frame.size(), CV_8UC1, cv::Scalar(0));
    auto throttled_follow_fail = session.update(blank, {ref}, 4.1, recordBeforeAbsolute, &sink);
    assert(sink.count == 2);
    assert(throttled_follow_fail.absolute_throttled && !throttled_follow_fail.absolute_attempted);
    assert(throttled_follow_fail.phase == "acquiring" && throttled_follow_fail.registration.query_features == 0);
    assert(std::string(throttled_follow_fail.reset_reason) == "follow_failed");
    assertFollowInsufficientDescriptors(throttled_follow_fail.follow, frame.size());

    // Cooldown with no active anchor: follow not attempted and sink not invoked.
    auto throttled_no_anchor = session.update(frame, {ref}, 4.5, recordBeforeAbsolute, &sink);
    assert(sink.count == 2);
    assert(throttled_no_anchor.absolute_throttled && !throttled_no_anchor.absolute_attempted);
    assert(throttled_no_anchor.phase == "acquiring" && std::string(throttled_no_anchor.reset_reason) == "none");
    assertFollowNotAttempted(throttled_no_anchor.follow);

    // Reacquire outside cooldown, then fail follow on textureless frame outside cooldown.
    auto reacquired = acquireSettled(session, frame, {ref}, 6.1, recordBeforeAbsolute, &sink);
    assert(sink.count == 3);
    assert(reacquired.phase == "confirming");

    auto blank_reacquire = session.update(blank, {ref}, 8.5, recordBeforeAbsolute, &sink);
    assert(sink.count == 3);
    const auto& pre_blank = blank_reacquire;
    assert(std::string(pre_blank.reset_reason) == "follow_failed");
    assertFollowInsufficientDescriptors(pre_blank.follow, frame.size());
    assert(blank_reacquire.phase == "acquiring" && !blank_reacquire.absolute_attempted);
    assert(!session.update(blank, {ref}, 8.6, recordBeforeAbsolute, &sink).absolute_attempted);
    assert(!session.update(blank, {ref}, 8.7, recordBeforeAbsolute, &sink).absolute_attempted);
    assert(std::string(blank_reacquire.reset_reason) == "follow_failed");
    assertFollowInsufficientDescriptors(blank_reacquire.follow, frame.size());
}

void testControlRoiFailures(const cv::Mat& frame, const std::shared_ptr<LayerReference>& ref) {
    // First control ROI failure: controls[0] fails, controls[1] remains unvisited.
    cv::setRNGSeed(202);
    BigMapSession session0;
    DiagnosticSinkLog sink0;
    assert(acquireSettled(session0, frame, {ref}, 1.0, recordBeforeAbsolute, &sink0).phase == "confirming");
    auto clean = session0.update(frame, {ref}, 1.5, recordBeforeAbsolute, &sink0);
    const int clean_tiles = clean.follow.tiles_evaluated;

    cv::Mat ctrl0_edit = frame.clone();
    ctrl0_edit(cv::Rect(1395, 250, 85, 225)).setTo(255);
    auto fail0 = session0.update(ctrl0_edit, {ref}, 3.5, recordBeforeAbsolute, &sink0);
    assert(sink0.count == 1 && !fail0.absolute_attempted && fail0.geometry.matrix.empty());
    const auto& pre0 = fail0;
    assert(std::string(pre0.reset_reason) == "follow_failed");
    assert(std::string(pre0.follow.outcome) == "controls_change");
    assert(pre0.follow.tiles_evaluated == clean_tiles);
    assert(pre0.follow.worst_tile_changed_fraction >= 0.0 && pre0.follow.worst_tile_changed_fraction <= 0.12);
    assert(pre0.follow.controls[0].rect == cv::Rect(1395, 250, 85, 225));
    assert(pre0.follow.controls[0].pixels == 85 * 225);
    assert(pre0.follow.controls[0].mean_delta > 6.0);
    assert(pre0.follow.controls[0].changed_pixels > pre0.follow.controls[0].pixels * 0.04);
    assert(pre0.follow.controls[0].changed_fraction > 0.04);
    assertControlUnvisited(pre0.follow.controls[1]);
    assert(std::string(fail0.follow.outcome) == "controls_change");
    assert(fail0.registration.query_features == 0);

    // Second control ROI failure: controls[0] passes, controls[1] fails.
    cv::setRNGSeed(202);
    BigMapSession session1;
    DiagnosticSinkLog sink1;
    assert(acquireSettled(session1, frame, {ref}, 1.0, recordBeforeAbsolute, &sink1).phase == "confirming");
    assert(std::string(session1.update(frame, {ref}, 1.5, recordBeforeAbsolute, &sink1).follow.outcome) == "accepted");

    cv::Mat ctrl1_edit = frame.clone();
    ctrl1_edit(cv::Rect(1010, 270, 60, 175)).setTo(255);
    auto fail1 = session1.update(ctrl1_edit, {ref}, 3.5, recordBeforeAbsolute, &sink1);
    assert(sink1.count == 1 && !fail1.absolute_attempted && fail1.geometry.matrix.empty());
    const auto& pre1 = fail1;
    assert(std::string(pre1.reset_reason) == "follow_failed");
    assert(std::string(pre1.follow.outcome) == "controls_change");
    assert(pre1.follow.tiles_evaluated == clean_tiles);
    assert(pre1.follow.controls[0].rect == cv::Rect(1395, 250, 85, 225));
    assert(pre1.follow.controls[0].pixels == 85 * 225);
    assert(pre1.follow.controls[0].mean_delta == 0.0);
    assert(pre1.follow.controls[0].changed_pixels == 0);
    assert(pre1.follow.controls[0].changed_fraction == 0.0);
    assert(pre1.follow.controls[1].rect == cv::Rect(1010, 270, 60, 175));
    assert(pre1.follow.controls[1].pixels == 60 * 175);
    assert(pre1.follow.controls[1].mean_delta > 6.0);
    assert(pre1.follow.controls[1].changed_pixels > pre1.follow.controls[1].pixels * 0.04);
    assert(pre1.follow.controls[1].changed_fraction > 0.04);
    assert(std::string(fail1.follow.outcome) == "controls_change");
    assert(fail1.registration.query_features == 0);
}

void testObserverEquivalenceAndSessionResets(
    const cv::Mat& frame,
    const cv::Mat& shifted,
    const std::shared_ptr<LayerReference>& ref
) {
    cv::Mat ctrl0_edit = frame.clone();
    ctrl0_edit(cv::Rect(1395, 250, 85, 225)).setTo(255);
    cv::Mat resized;
    cv::resize(frame, resized, cv::Size(1040, 480));
    cv::Mat invalid_rgba(frame.size(), CV_8UC4, cv::Scalar::all(0));

    struct Step {
        const cv::Mat* image;
        double timestamp;
    };
    const std::vector<Step> steps = {
        {&frame, 0.5}, {&frame, 0.75},
        {&frame, 1.0},       // sink 0: settled initial acquire
        {&frame, 1.5},       // follow accepted (confirming)
        {&frame, 2.0},       // follow accepted (tracking)
        {&shifted, 2.5},     // follow accepted (moving)
        {&shifted, 3.0},     // follow accepted (confirming)
        {&shifted, 3.5},     // follow accepted (tracking)
        {&ctrl0_edit, 4.0},  // appearance failure + measured movement: suspended, no geometry
        {&ctrl0_edit, 4.2},  // first stationary appearance failure
        {&ctrl0_edit, 4.4},  // second still failure => discard, begin settling
        {&ctrl0_edit, 4.65}, {&ctrl0_edit, 4.9}, // sink 1: new settled search
        {&frame, 5.0},       // controls_change while stationary, throttled, no sink
        {&frame, 6.5}, {&frame, 6.75},
        {&frame, 7.0},       // sink 2: settled reacquire after cooldown
        {&frame, 15.0},      // follow accepted within 10s
        {&frame, 17.1}, {&frame, 17.35}, {&frame, 17.6}, // sink 3: expired anchor, settled refresh
        {&frame, 28.0}, {&frame, 28.25}, {&frame, 28.5}, // sink 4: observation gap then settling
        {&resized, 30.5}, {&resized, 30.75}, {&resized, 31.0}, // sink 5: resize then settling
        {&invalid_rgba, 31.1} // invalid_input_or_timestamp, no sink
    };

    auto run_sequence = [&](BigMapSessionDiagnosticSink sink_fn, DiagnosticSinkLog* sink_log) {
        cv::setRNGSeed(1337);
        BigMapSession session;
        std::vector<BigMapSessionResult> results;
        results.reserve(steps.size());
        for (const auto& step : steps) {
            results.push_back(session.update(*step.image, {ref}, step.timestamp, sink_fn, sink_log));
        }
        return results;
    };

    const auto plain = run_sequence(nullptr, nullptr);
    DiagnosticSinkLog sink;
    const auto observed = run_sequence(recordBeforeAbsolute, &sink);

    assert(plain.size() == observed.size());
    for (size_t i = 0; i < plain.size(); ++i) {
        assertResultsEquivalent(plain[i], observed[i]);
    }

    assert(sink.count == 6);
    for (size_t i = 0; i < sink.count; ++i) {
        assertPreSiftSinkShape(sink.observations[i]);
    }
    assert(std::string(sink.observations[0].follow.outcome) == "not_attempted");
    assertFollowNotAttempted(sink.observations[1].follow);
    assert(sink.observations[3].anchor_expired);
    assert(std::string(sink.observations[3].reset_reason) == "none");
    assertFollowNotAttempted(sink.observations[3].follow);
    assert(std::abs(sink.observations[3].anchor_age_seconds - 10.6) < 1e-6);
    assert(!sink.observations[4].anchor_expired);
    assert(std::string(sink.observations[4].reset_reason) == "none");
    assert(!sink.observations[5].anchor_expired);
    assert(std::string(sink.observations[5].reset_reason) == "none");

    const auto at = [&](double t) -> const BigMapSessionResult& {
        for (size_t i = 0; i < steps.size(); ++i) if (steps[i].timestamp == t) return observed[i];
        std::abort();
    };
    assert(at(4.0).appearance_suspended && at(4.0).phase == "moving");
    assert(!at(4.0).absolute_attempted && at(4.0).geometry.matrix.empty());
    assert(at(4.2).suspended_still_frames == 1 && at(4.2).geometry.matrix.empty());
    assert(at(5.0).absolute_throttled && !at(5.0).absolute_attempted);
    assert(std::string(at(5.0).reset_reason) == "follow_failed");
    assert(at(17.1).anchor_expired && std::string(at(17.1).reset_reason) == "none");
    assert(std::string(at(28.0).reset_reason) == "observation_gap");
    assert(std::string(at(30.5).reset_reason) == "frame_size");
    assert(at(31.1).phase == "rejected" && std::string(at(31.1).reset_reason) == "invalid_input_or_timestamp");
    assert(!at(31.1).absolute_attempted);
}

void testMovingAppearanceIsNotPublished(const cv::Mat& frame, const std::shared_ptr<LayerReference>& ref) {
    cv::setRNGSeed(303);
    // Compose a map first, then fixed UI. Never warp UI along with the map.
    const auto draw_ui = [](cv::Mat& image) {
        image(cv::Rect(70, 255, 60, 210)).setTo(240); // existing slider exclusion
        image(cv::Rect(1395, 250, 85, 225)).setTo(40);
        image(cv::Rect(1010, 270, 60, 175)).setTo(40);
    };
    cv::Mat anchor = frame.clone();
    draw_ui(anchor);
    BigMapSession session;
    assert(acquireSettled(session, anchor, {ref}, 1).phase == "confirming");
    assert(!session.update(anchor, {ref}, 1.1).absolute_attempted);
    assert(session.update(anchor, {ref}, 1.2).geometry.mode == "found");

    cv::Mat motion = (cv::Mat_<double>(2, 3) << 1, 0, 8, 0, 1, 0);
    cv::Mat shifted;
    cv::warpAffine(frame, shifted, motion, frame.size());
    draw_ui(shifted);
    cv::Mat changed = shifted.clone();
    changed(cv::Rect(630, 360, 90, 90)).setTo(250); // not masked just because it changed
    const auto moving = session.update(changed, {ref}, 1.3);
    assert(moving.follow.geometry_accepted && std::string(moving.follow.outcome) == "tile_change");
    assert(moving.phase == "moving" && moving.appearance_suspended);
    assert(moving.geometry.matrix.empty() && !moving.absolute_attempted);
    assert(moving.stable_frames == 0 && moving.suspended_still_frames == 0);

    // Moving evidence alone must never authorize geometry, even on a floor-like switch.
    const auto still_bad = session.update(changed, {ref}, 1.4);
    assert(still_bad.geometry.matrix.empty() && !still_bad.absolute_attempted);
    assert(still_bad.phase == "confirming" && still_bad.suspended_still_frames == 1);
    const auto rejected = session.update(changed, {ref}, 1.5);
    assert(rejected.geometry.matrix.empty() && rejected.absolute_throttled);
    assert(std::string(rejected.reset_reason) == "follow_failed" && !rejected.appearance_suspended);
    assert(!session.update(shifted, {ref}, 1.6).follow.geometry_accepted); // destroyed, not stale fallback

    // A transient map occlusion can clear. Two clean stationary checks are still required.
    session.reset();
    assert(acquireSettled(session, anchor, {ref}, 3).phase == "confirming");
    session.update(anchor, {ref}, 3.1);
    assert(session.update(anchor, {ref}, 3.2).geometry.mode == "found");
    assert(session.update(changed, {ref}, 3.3).appearance_suspended);
    const auto clean1 = session.update(shifted, {ref}, 3.4);
    assert(clean1.follow.geometry_accepted && std::string(clean1.follow.outcome) == "accepted");
    assert(clean1.geometry.matrix.empty() && !clean1.absolute_attempted);
    // An interleaved bad frame cancels a partial clean confirmation.
    const auto interleaved = session.update(changed, {ref}, 3.45);
    assert(interleaved.geometry.matrix.empty() && interleaved.suspended_still_frames == 1);
    assert(session.update(shifted, {ref}, 3.5).geometry.matrix.empty());
    const auto clean2 = session.update(shifted, {ref}, 3.6);
    assert(clean2.geometry.mode == "found" && clean2.phase == "tracking");
    assert(!clean2.absolute_attempted && !clean2.appearance_suspended);
    assert(clean2.geometry.timestamp_seconds == 3.6);

    // Sparse failure while suspended is immediate rejection, never a moving fallback.
    session.reset();
    acquireSettled(session, anchor, {ref}, 5);
    session.update(anchor, {ref}, 5.1);
    session.update(anchor, {ref}, 5.2);
    assert(session.update(changed, {ref}, 5.3).appearance_suspended);
    const auto blank = session.update(cv::Mat(frame.size(), CV_8UC1, cv::Scalar(0)), {ref}, 5.4);
    assert(!blank.follow.geometry_accepted && !blank.appearance_suspended);
    assert(blank.geometry.matrix.empty() && std::string(blank.reset_reason) == "follow_failed");

    // Pause/reset destroys a suspended candidate before a later observation can complete it.
    session.reset();
    acquireSettled(session, anchor, {ref}, 7);
    assert(session.update(changed, {ref}, 7.1).appearance_suspended);
    session.reset();
    const auto restarted = session.update(shifted, {ref}, 7.2);
    assert(!restarted.absolute_attempted && restarted.geometry.matrix.empty());
    assert(restarted.acquisition_still_frames == 0);
    assert(std::string(restarted.follow.outcome) == "not_attempted" && !restarted.appearance_suspended);

    // A changed control during a real pan cannot be hidden by the suspension.
    session.reset();
    acquireSettled(session, anchor, {ref}, 8);
    session.update(anchor, {ref}, 8.1);
    session.update(anchor, {ref}, 8.2);
    cv::Mat control_changed = shifted.clone();
    control_changed(cv::Rect(1010, 270, 60, 175)).setTo(255);
    const auto control_moving = session.update(control_changed, {ref}, 8.3);
    assert(std::string(control_moving.follow.outcome) == "controls_change");
    assert(control_moving.appearance_suspended && control_moving.geometry.matrix.empty());
    const auto control_still = session.update(control_changed, {ref}, 8.4);
    assert(control_still.suspended_still_frames == 1 && control_still.geometry.matrix.empty());
    const auto control_reject = session.update(control_changed, {ref}, 8.5);
    assert(std::string(control_reject.reset_reason) == "follow_failed");
    assert(control_reject.geometry.matrix.empty() && !control_reject.appearance_suspended);

    // Suspension cannot refresh the 10-second absolute-anchor lifetime during continued motion.
    session.reset();
    acquireSettled(session, anchor, {ref}, 10);
    session.update(anchor, {ref}, 10.1);
    session.update(anchor, {ref}, 10.2);
    for (int i = 1; i <= 5; ++i) {
        motion.at<double>(0, 2) = i * 8;
        cv::warpAffine(frame, changed, motion, frame.size());
        draw_ui(changed);
        changed(cv::Rect(630, 360, 90, 90)).setTo(250);
        const auto value = session.update(changed, {ref}, 10.2 + i * 1.5);
        assert(value.geometry.matrix.empty() && !value.absolute_attempted && value.appearance_suspended);
    }
    const auto expired = session.update(changed, {ref}, 20.1);
    assert(expired.anchor_expired && !expired.absolute_attempted && expired.geometry.matrix.empty());
    assert(expired.acquisition_still_frames == 0);
}

void testStationaryUiOverMovingMap(const cv::Mat& frame, const std::shared_ptr<LayerReference>& ref) {
    cv::setRNGSeed(404);
    const auto draw_ui = [](cv::Mat& image) {
        image(cv::Rect(70, 255, 60, 210)).setTo(255);
        image(cv::Rect(1395, 250, 85, 225)).setTo(30);
        image(cv::Rect(1010, 270, 60, 175)).setTo(30);
    };
    cv::Mat anchor = frame.clone();
    draw_ui(anchor);
    BigMapSession session;
    acquireSettled(session, anchor, {ref}, 1);
    session.update(anchor, {ref}, 1.1);
    assert(session.update(anchor, {ref}, 1.2).geometry.mode == "found");
    for (const cv::Mat& motion : std::vector<cv::Mat>{
             (cv::Mat_<double>(2, 3) << 1, 0, 1.25, 0, 1, 0.5),
             (cv::Mat_<double>(2, 3) << 1.03, 0, -15, 0, 1.03, -5)}) {
        cv::Mat shifted;
        cv::warpAffine(frame, shifted, motion, frame.size());
        draw_ui(shifted);
        const double t = motion.at<double>(0, 0) == 1 ? 1.3 : 1.6;
        const auto moving = session.update(shifted, {ref}, t);
        assert(moving.phase == "moving" && moving.geometry.matrix.empty() && !moving.absolute_attempted);
        assertFollowAccepted(moving.follow, frame.size());
        assert(session.update(shifted, {ref}, t + 0.1).geometry.matrix.empty());
        const auto settled = session.update(shifted, {ref}, t + 0.2);
        assert(settled.phase == "tracking" && settled.geometry.mode == "found" && !settled.absolute_attempted);
    }
}

} // namespace

int main() {
    cv::setNumThreads(1);
    cv::setRNGSeed(42);
    cv::Mat reference(720, 1100, CV_8UC1);
    cv::randu(reference, 0, 255);
    cv::GaussianBlur(reference, reference, cv::Size(5, 5), 1);
    cv::Mat mask(reference.size(), CV_8UC1, cv::Scalar(255));
    auto ref = std::make_shared<LayerReference>("test", "space", "surface", reference, mask, mask, 2, 1000, -400);
    const cv::Size size(1560, 720);
    cv::Mat frame;
    cv::Mat pose = (cv::Mat_<double>(2, 3) << 0.9, 0, 180, 0, 0.9, 40);
    cv::warpAffine(reference, frame, pose, size);
    // Static UI regions do not pan with the map texture.
    frame(cv::Rect(990, 260, 110, 200)).setTo(0);
    BigMapSession session;
    auto candidate = acquireSettled(session, frame, {ref}, 1);
    assert(candidate.phase == "confirming" && candidate.geometry.matrix.empty() && candidate.absolute_attempted);
    assert(std::string(candidate.follow.outcome) == "not_attempted" && !candidate.anchor_expired && !candidate.absolute_throttled);
    auto first = session.update(frame, {ref}, 1.5);
    assert(first.geometry.matrix.empty() && !first.absolute_attempted);
    assertFollowAccepted(first.follow, size);
    auto stable = session.update(frame, {ref}, 2);
    assert(stable.phase == "tracking" && stable.geometry.mode == "found" && !stable.absolute_attempted);
    assert(stable.geometry.timestamp_seconds == 2);
    assert(std::abs(stable.geometry.matrix.at<double>(0, 0) - 0.45) < 0.01);
    assertFollowAccepted(stable.follow, size);
    BigMapSession slow;
    assert(acquireSettled(slow, frame, {ref}, 1).phase == "confirming");
    assert(slow.update(frame, {ref}, 4).geometry.matrix.empty());
    assert(slow.update(frame, {ref}, 4.5).phase == "tracking");
    // Blanking left-top UI must not make a fake movement in the geometric matching.
    cv::Mat uiOnlyChange = frame.clone();
    uiOnlyChange(cv::Rect(0, 0, 380, 200)).setTo(255);
    assert(slow.update(uiOnlyChange, {ref}, 5).geometry.mode == "found");
    // Legacy geometry-only fixture (whole-frame warp). Independent fixed UI is tested below.
    // Translation: no matrix while moving, then recover against the SAME absolute anchor.
    cv::Mat shifted;
    cv::Mat shift = (cv::Mat_<double>(2, 3) << 1, 0, 12, 0, 1, 0);
    cv::warpAffine(frame, shifted, shift, size);
    auto moving = session.update(shifted, {ref}, 2.5);
    assert(moving.geometry.matrix.empty() && !moving.absolute_attempted);
    auto settle = session.update(shifted, {ref}, 3);
    assert(settle.geometry.matrix.empty());
    auto stopped = session.update(shifted, {ref}, 3.5);
    assert(stopped.phase == "tracking" && stopped.geometry.mode == "found" && !stopped.absolute_attempted);
    assert(std::abs(stopped.geometry.matrix.at<double>(0, 2) - (192 - 450)) < 2);
    // Modest zoom and translation compose with the anchor, not the last follow matrix.
    cv::Mat zoomed;
    cv::Mat zoom = (cv::Mat_<double>(2, 3) << 1.03, 0, -15, 0, 1.03, -5);
    cv::warpAffine(frame, zoomed, zoom, size);
    auto zoomMoving = session.update(zoomed, {ref}, 4);
    assert(zoomMoving.geometry.matrix.empty() && !zoomMoving.absolute_attempted);
    assert(!session.update(zoomed, {ref}, 4.5).absolute_attempted);
    auto zoomStable = session.update(zoomed, {ref}, 5);
    assert(zoomStable.geometry.mode == "found" && !zoomStable.absolute_attempted);
    assert(std::abs(zoomStable.geometry.matrix.at<double>(0, 0) - 0.45 * 1.03) < 0.01);
    // Local changed patch (e.g. a selected floor) must not ride on unchanged surroundings.
    cv::Mat changed = zoomed.clone();
    changed(cv::Rect(650, 400, 90, 90)).setTo(250);
    auto changedResult = session.update(changed, {ref}, 5.5);
    assert(changedResult.geometry.matrix.empty());
    assert(std::string(changedResult.reset_reason) == "follow_failed");
    assert(std::string(changedResult.follow.outcome) == "tile_change");
    session.reset();
    assert(acquireSettled(session, frame, {ref}, 5).phase == "confirming");
    assert(session.update(frame, {ref}, 5).geometry.matrix.empty()); // duplicate timestamp resets
    assert(acquireSettled(session, frame, {ref}, 6).phase == "confirming");
    auto textureless = session.update(cv::Mat(size, CV_8UC1, cv::Scalar(0)), {ref}, 6.5);
    assert(textureless.geometry.matrix.empty());
    assertFollowInsufficientDescriptors(textureless.follow, size);
    assert(std::string(textureless.reset_reason) == "follow_failed" && textureless.absolute_throttled);
    // Expired absolute anchor cannot silently acquire a fresh timestamp forever.
    session.reset();
    assert(acquireSettled(session, frame, {ref}, 10).phase == "confirming");
    for (double t = 10.5; t <= 20; t += 0.5) {
        auto value = session.update(frame, {ref}, t);
        assert(!value.absolute_attempted);
    }
    auto refresh = session.update(frame, {ref}, 21);
    assert(!refresh.absolute_attempted && refresh.geometry.matrix.empty());
    assert(refresh.anchor_expired && std::string(refresh.reset_reason) == "none");
    assertFollowNotAttempted(refresh.follow);
    assert(!session.update(frame, {ref}, 21.25).absolute_attempted);
    assert(session.update(frame, {ref}, 21.5).absolute_attempted);
    assert(session.update(frame, {ref}, 21.75).geometry.matrix.empty());
    assert(session.update(frame, {ref}, 22).geometry.mode == "found");
    // Controls changed even though map texture stayed identical.
    cv::Mat controls = frame.clone();
    controls(cv::Rect(1395, 250, 85, 225)).setTo(255);
    auto controlsResult = session.update(controls, {ref}, 22.5);
    assert(controlsResult.geometry.matrix.empty());
    assert(std::string(controlsResult.follow.outcome) == "controls_change");
    assert(std::string(controlsResult.reset_reason) == "follow_failed" && controlsResult.absolute_throttled);
    assertControlUnvisited(controlsResult.follow.controls[1]);
    // Changed capture geometry and long absence cannot reuse the old anchor.
    cv::Mat resized;
    cv::resize(frame, resized, cv::Size(2340, 1080));
    auto resizedCandidate = session.update(resized, {ref}, 23);
    assert(!resizedCandidate.absolute_attempted && resizedCandidate.geometry.matrix.empty());
    assert(std::string(resizedCandidate.reset_reason) == "frame_size");
    auto longGap = session.update(resized, {ref}, 40);
    assert(!longGap.absolute_attempted && longGap.geometry.matrix.empty());
    assert(std::string(longGap.reset_reason) == "observation_gap");
    // Same-space appearance switch and an unrelated image both fail closed.
    cv::Mat unrelated(frame.size(), CV_8UC1);
    cv::randu(unrelated, 0, 255);
    assert(session.update(unrelated, {ref}, 40.5).geometry.matrix.empty());
    auto negativeTs = session.update(frame, {ref}, -1);
    assert(negativeTs.geometry.matrix.empty());
    assert(negativeTs.phase == "rejected" && std::string(negativeTs.reset_reason) == "invalid_input_or_timestamp");
    BigMapSession ambiguous;
    auto other = std::make_shared<LayerReference>("other", "other-space", "surface", reference, mask, mask, 2, 1000, -400);
    auto uncertain = acquireSettled(ambiguous, frame, {ref, other}, 1);
    assert(uncertain.phase == "ambiguous" && uncertain.geometry.mode == "ambiguous" && uncertain.geometry.matrix.empty());
    // Recoverable bad input invalidates stationarity/anchor, not just this one result.
    BigMapSession recovery;
    assert(acquireSettled(recovery, frame, {ref}, 1).phase == "confirming");
    recovery.update(frame, {ref}, 1.5);
    assert(recovery.update(frame, {ref}, 2).geometry.mode == "found");
    assert(recovery.update(cv::Mat(size, CV_8UC4), {ref}, 2.5).geometry.matrix.empty());
    assert(acquireSettled(recovery, frame, {ref}, 3.1).phase == "confirming");
    assert(recovery.update(frame, {ref}, 3.5).geometry.matrix.empty());
    assert(recovery.update(frame, {ref}, 4).geometry.mode == "found");

    testFollowDiagnosticsAndObserver(frame, ref);
    testControlRoiFailures(frame, ref);
    testObserverEquivalenceAndSessionResets(frame, shifted, ref);
    testMovingAppearanceIsNotPublished(frame, ref);
    testStationaryUiOverMovingMap(frame, ref);

    std::cout << "big-map session tests passed\n";
}
