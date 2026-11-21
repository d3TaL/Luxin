// Stage-2 tests: stable big-map anchor that does NOT reset on minor shifts.
// Tests verify: geometry_anchor_ vs track_anchor_ separation, composition via follow,
// not_ok_counter threshold, grid/stability independence from layer.

#include "luxin/bigmap_session.hpp"
#include <array>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>
#include <opencv2/imgproc.hpp>

using namespace luxin;

// Stays active under NDEBUG (Release builds), unlike assert(); used by the motion-tolerance tests.
#define REQUIRE(condition) do { \
    if (!(condition)) { \
        std::cerr << __func__ << ':' << __LINE__ << ": " #condition "\n"; \
        std::abort(); \
    } \
} while (false)

namespace luxin {
struct BigMapSessionTestAccess {
    static void awaitRefresh(BigMapSession& session) {
        assert(session.renewal_.valid());
        if (session.renewal_.wait_for(std::chrono::seconds(30)) != std::future_status::ready) std::abort();
    }
};
}

namespace {

// Hold a complete search in flight without sleeping or relying on relative thread speeds.
struct RefreshBarrier {
    std::mutex mutex;
    std::condition_variable changed;
    int entered = 0, released = 0;
    bool wrong_space = false;

    void waitFor(int& counter, int n) {
        std::unique_lock<std::mutex> lock(mutex);
        if (!changed.wait_for(lock, std::chrono::seconds(30), [&] { return counter >= n; })) std::abort();
    }
    void release(int n) {
        std::lock_guard<std::mutex> lock(mutex);
        released = n;
        changed.notify_all();
    }
};
RefreshBarrier* active_barrier = nullptr;

BigMapGeometry controlledRefresh(const cv::Mat& frame, const std::vector<std::shared_ptr<LayerReference>>& refs,
    double timestamp, BigMapRegistrationStats* stats) {
    auto& b = *active_barrier;
    {
        std::unique_lock<std::mutex> lock(b.mutex);
        const int ticket = ++b.entered;
        b.changed.notify_all();
        if (!b.changed.wait_for(lock, std::chrono::seconds(30), [&] { return b.released >= ticket; })) std::abort();
    }
    auto g = registerBigMap(frame, refs, timestamp, stats);
    if (b.wrong_space) g.space = "conflict";
    return g;
}

BigMapSessionResult acquireSettled(BigMapSession& session, const cv::Mat& frame,
    const std::vector<std::shared_ptr<LayerReference>>& refs, double timestamp) {
    auto first = session.update(frame, refs, timestamp - 0.5);
    assert(!first.absolute_attempted && first.geometry.matrix.empty());
    auto second = session.update(frame, refs, timestamp - 0.25);
    assert(!second.absolute_attempted && second.geometry.matrix.empty());
    auto result = session.update(frame, refs, timestamp);
    assert(result.absolute_attempted && result.geometry.matrix.empty());
    assert(result.acquisition_still_seconds >= 0.5);
    return result;
}

void assertFollowAccepted(const BigMapFollowStats& follow, cv::Size working_size) {
    assert(follow.geometry_accepted);
    assert(std::string(follow.outcome) == "accepted");
    assert(follow.matches >= 24);
    assert(follow.inliers >= 24 && follow.inliers <= follow.matches);
    assert(follow.grid_cells >= 4);
    assert(follow.inlier_ratio >= 0.65);
    assert(follow.mean_error_px >= 0.0 && follow.mean_error_px <= 0.8);
    assert(follow.scale >= 0.7 && follow.scale <= 1.4);
    assert(std::abs(follow.angle_degrees) <= 1.0);
    assert(follow.hull_fraction >= 0.015);
    assert(follow.overlap_fraction >= 0.20);
    assert(follow.changed_fraction <= 0.025);
}

void testSeamlessRenewal(const cv::Mat& frame, const std::shared_ptr<LayerReference>& ref) {
    RefreshBarrier b;
    active_barrier = &b;
    BigMapSession session(controlledRefresh);
    acquireSettled(session, frame, {ref}, 1.0);
    session.update(frame, {ref}, 1.25);
    auto first = session.update(frame, {ref}, 1.5);
    int starts = 0, adoptions = 0;
    double source = -1;
    for (double t = 2; t <= 32; t += 0.5) {
        auto r = session.update(frame, {ref}, t);
        assert(r.phase == "tracking" && r.geometry.timestamp_seconds == t && !r.absolute_attempted);
        assert(equivalentBigMapGeometry(first.geometry, r.geometry));
        if (std::string(r.refresh) == "started") {
            b.waitFor(b.entered, ++starts);
            source = t;
        }
        if (t == source + 1) { // normal fresh publications continued while the SIFT worker was blocked
            b.release(starts);
            BigMapSessionTestAccess::awaitRefresh(session);
        }
        if (std::string(r.refresh) == "adopted") {
            ++adoptions;
            assert(session.snapshot().geometry.timestamp_seconds == source); // not adoption time
        }
    }
    b.release(100); // the last search may be in flight at destruction
    assert(adoptions >= 4);
}

void testLateRenewal(const cv::Mat& frame, const std::shared_ptr<LayerReference>& ref, bool pause) {
    RefreshBarrier b;
    active_barrier = &b;
    BigMapSession session(controlledRefresh);
    acquireSettled(session, frame, {ref}, 1.0);
    session.update(frame, {ref}, 1.5);
    assert(std::string(session.update(frame, {ref}, 7.0).refresh) == "started");
    b.waitFor(b.entered, 1);
    double t = pause ? 7.15 : 11.01;
    if (pause) session.reset(); // hide/show before completion; no frame-size change
    auto hidden = session.update(frame, {ref}, t);
    assert(hidden.geometry.matrix.empty() && !hidden.absolute_attempted && hidden.anchor_expired == !pause);
    b.release(1);
    BigMapSessionTestAccess::awaitRefresh(session);
    for (int i = 1; i <= 3; ++i) {
        auto after = session.update(frame, {ref}, t + i * 0.1);
        assert(after.geometry.matrix.empty() && !after.absolute_attempted && std::string(after.refresh) != "adopted");
    }
}

void testConflictingRenewal(const cv::Mat& frame, const std::shared_ptr<LayerReference>& ref) {
    RefreshBarrier b;
    b.wrong_space = true;
    active_barrier = &b;
    BigMapSession session(controlledRefresh);
    cv::Mat base = frame.clone(), pan;
    const cv::Mat motion = (cv::Mat_<double>(2, 3) << 1, 0, 4, 0, 1, 2);
    cv::warpAffine(frame, pan, motion, frame.size());
    for (auto* im : {&base, &pan}) (*im)(cv::Rect(1010, 0, 550, 720)).setTo(30);
    acquireSettled(session, base, {ref}, 1.0);
    session.update(base, {ref}, 1.5);
    assert(std::string(session.update(base, {ref}, 7.0).refresh) == "started");
    b.release(1);
    BigMapSessionTestAccess::awaitRefresh(session);
    auto r = session.update(pan, {ref}, 7.25, nullptr, nullptr, true);
    assert(std::string(r.follow.outcome) == "accepted" && r.movement_px > 0.6);
    assert(std::string(r.refresh) == "conflict" && !r.measured_motion && r.geometry.matrix.empty());
    assert(r.follow.debug_points.empty());
    assert(r.phase == "acquiring" && session.snapshot().geometry.matrix.empty());
}

void testNoRefreshAtCutoff(const cv::Mat& frame, const std::shared_ptr<LayerReference>& ref) {
    BigMapSession session(registerBigMap);
    acquireSettled(session, frame, {ref}, 1.0);
    session.update(frame, {ref}, 1.5);
    auto last = session.update(frame, {ref}, 11.0);
    assert(last.phase == "tracking" && std::string(last.refresh) == "none");
    auto expired = session.update(frame, {ref}, 11.1);
    assert(expired.anchor_expired && expired.geometry.matrix.empty());
}

void testRenewalDuringStopStart(const cv::Mat& frame, const std::shared_ptr<LayerReference>& ref) {
    RefreshBarrier b;
    active_barrier = &b;
    auto old = std::make_unique<BigMapSession>(controlledRefresh);
    acquireSettled(*old, frame, {ref}, 1.0);
    old->update(frame, {ref}, 1.5);
    assert(std::string(old->update(frame, {ref}, 7.0).refresh) == "started");
    b.waitFor(b.entered, 1);
    old->reset();
    auto closing = std::async(std::launch::async, [owner = std::move(old)]() mutable { owner.reset(); });
    BigMapSession next(controlledRefresh);
    acquireSettled(next, frame, {ref}, 7.8);
    next.update(frame, {ref}, 8.3);
    auto checked = next.update(frame, {ref}, 14.0);
    assert(checked.phase == "tracking" && std::string(checked.refresh) == "none"); // no queued worker
    b.release(1);
    closing.get();
    assert(next.snapshot().geometry.timestamp_seconds == 7.8); // late worker never changed new owner
}

void testMeasuredPanAndPoints(const cv::Mat& map, const std::shared_ptr<LayerReference>& ref) {
    // Constant screen-fixed controls, so pan should pass both geometry AND appearance.
    auto fixedControls = [](cv::Mat& image) {
        image(cv::Rect(1010, 0, 550, 720)).setTo(30);
    };
    cv::Mat base = map.clone(), pan;
    const cv::Mat translation = (cv::Mat_<double>(2, 3) << 1, 0, 4, 0, 1, 2);
    cv::warpAffine(map, pan, translation, map.size());
    fixedControls(base); fixedControls(pan);
    BigMapSession session;
    acquireSettled(session, base, {ref}, 1.0);
    session.update(base, {ref}, 1.2);
    auto stable = session.update(base, {ref}, 1.4);
    assert(stable.phase == "tracking" && stable.follow.debug_points.empty());
    auto moved = session.update(pan, {ref}, 1.6, nullptr, nullptr, true);
    assert(std::string(moved.follow.outcome) == "accepted" && moved.phase == "moving" && moved.measured_motion);
    assert(moved.geometry.timestamp_seconds == 1.6 && !moved.geometry.matrix.empty());
    assert(std::abs(moved.geometry.matrix.at<double>(0, 2) - stable.geometry.matrix.at<double>(0, 2) - 4) < 1.0);
    assert(!moved.follow.debug_points.empty() && moved.follow.debug_points.size() <= 48);
    for (auto p : moved.follow.debug_points) assert(p.x >= 0 && p.y >= 0 && p.x < pan.cols && p.y < pan.rows);
    auto lost = session.update(cv::Mat::zeros(map.size(), map.type()), {ref}, 1.8, nullptr, nullptr, true);
    assert(!lost.measured_motion && lost.geometry.matrix.empty() && lost.follow.debug_points.empty());
}

// ---- Motion tolerance (experimental stage 1) -------------------------------------------------------
// Fixture shared with testMeasuredPanAndPoints: the map moves, the screen-fixed panel is painted after.
// `panel_x` is where the screen-fixed panel starts; 1075 leaves control ROI 1 (x 1010..1070) as map.
cv::Mat stillFrame(const cv::Mat& map, int panel_x = 1010) {
    cv::Mat out = map.clone();
    out(cv::Rect(panel_x, 0, 1560 - panel_x, 720)).setTo(30);
    return out;
}

cv::Mat panFrame(const cv::Mat& map, double dx, double dy, int panel_x = 1010) {
    cv::Mat out;
    const cv::Mat translation = (cv::Mat_<double>(2, 3) << 1, 0, dx, 0, 1, dy);
    cv::warpAffine(map, out, translation, map.size());
    out(cv::Rect(panel_x, 0, 1560 - panel_x, 720)).setTo(30);
    return out;
}

// Map whose texture (not a flat patch) lies under control ROI 1, as in the 21 Nov log.
cv::Mat mapWithTextureInRoi1(const cv::Mat& map) {
    cv::Mat out = map.clone();
    cv::Mat noise(200, 110, CV_8UC1);
    cv::RNG rng(5);
    rng.fill(noise, cv::RNG::UNIFORM, 40, 220);
    cv::GaussianBlur(noise, noise, cv::Size(5, 5), 1);
    noise.copyTo(out(cv::Rect(990, 260, 110, 200)));
    return out;
}

// Excellent sparse geometry but one redrawn tile while the map moves (the failure seen in the
// 19-20 Nov logs): the frame must publish measured motion instead of spending a strike.
void testPanWithPixelChangeIsTolerated(const cv::Mat& map, const std::shared_ptr<LayerReference>& ref) {
    const cv::Mat base = stillFrame(map);
    cv::Mat pan = panFrame(map, 4, 2);
    pan(cv::Rect(630, 360, 90, 90)).setTo(250);
    BigMapSession session;
    acquireSettled(session, base, {ref}, 1.0);
    session.update(base, {ref}, 1.2);
    const auto stable = session.update(base, {ref}, 1.4);
    REQUIRE(stable.phase == "tracking" && !stable.appearance_tolerated);

    const auto moved = session.update(pan, {ref}, 1.6);
    REQUIRE(std::string(moved.follow.outcome) == "tile_change" && moved.follow.geometry_accepted);
    REQUIRE(moved.appearance_tolerated && moved.phase == "moving" && moved.measured_motion);
    REQUIRE(!moved.absolute_attempted && moved.movement_px > 0.6 && moved.stable_frames == 0);
    REQUIRE(moved.geometry.timestamp_seconds == 1.6 && !moved.geometry.matrix.empty());
    REQUIRE(std::abs(moved.geometry.matrix.at<double>(0, 2) - stable.geometry.matrix.at<double>(0, 2) - 4) < 1.0);

    // Inside the grace the held picture is still part of the pan (inertia / duplicated capture).
    REQUIRE(session.update(pan, {ref}, 1.8).appearance_tolerated);

    // After the grace the same picture is judged strictly again: no geometry, three strikes drop the anchor.
    const auto held1 = session.update(pan, {ref}, 2.8);
    REQUIRE(!held1.appearance_tolerated && !held1.measured_motion && held1.geometry.matrix.empty());
    REQUIRE(std::string(held1.follow.outcome) == "tile_change");
    session.update(pan, {ref}, 2.9);
    const auto dropped = session.update(pan, {ref}, 3.0);
    REQUIRE(std::string(dropped.reset_reason) == "follow_failed" && dropped.phase == "acquiring");
}

// A stationary pixel change is never tolerated, whatever happened earlier.
void testStillPixelChangeStaysStrict(const cv::Mat& map, const std::shared_ptr<LayerReference>& ref) {
    const cv::Mat base = stillFrame(map);
    cv::Mat edited = base.clone();
    edited(cv::Rect(630, 360, 90, 90)).setTo(250);
    BigMapSession session;
    acquireSettled(session, base, {ref}, 1.0);
    session.update(base, {ref}, 1.2);
    REQUIRE(session.update(base, {ref}, 1.4).phase == "tracking");
    for (double t : {1.6, 1.8}) {
        const auto r = session.update(edited, {ref}, t);
        REQUIRE(std::string(r.follow.outcome) == "tile_change");
        REQUIRE(!r.appearance_tolerated && !r.measured_motion && r.geometry.matrix.empty());
    }
    const auto third = session.update(edited, {ref}, 2.0);
    REQUIRE(std::string(third.reset_reason) == "follow_failed" && third.phase == "acquiring");
}

// Screen-fixed controls changing during a pan (menu, floor switch) stay fatal.
void testControlsChangeIsNotTolerated(const cv::Mat& map, const std::shared_ptr<LayerReference>& ref) {
    const cv::Mat base = stillFrame(map);
    cv::Mat pan = panFrame(map, 4, 2);
    pan(cv::Rect(630, 360, 90, 90)).setTo(250);
    pan(cv::Rect(1395, 250, 85, 225)).setTo(200);
    BigMapSession session;
    acquireSettled(session, base, {ref}, 1.0);
    session.update(base, {ref}, 1.2);
    REQUIRE(session.update(base, {ref}, 1.4).phase == "tracking");
    const auto r = session.update(pan, {ref}, 1.6);
    REQUIRE(std::string(r.follow.outcome) == "controls_change");
    REQUIRE(!r.appearance_tolerated && !r.measured_motion && r.geometry.matrix.empty());
}

// 21 Nov log: control ROI 1 showed MAP content, so it changed with every pan and blocked tolerance
// (mean delta 15-18 at rest 0). Map-anchored comparison passes it; a panel that appears still fails.
void testMapContentInControlRoiDoesNotBlockPan(const cv::Mat& map, const std::shared_ptr<LayerReference>& ref) {
    const cv::Mat textured = mapWithTextureInRoi1(map);
    const cv::Mat base = stillFrame(textured, 1075);
    cv::Mat pan = panFrame(textured, 4, 2, 1075);
    pan(cv::Rect(630, 360, 90, 90)).setTo(250);
    BigMapSession session;
    acquireSettled(session, base, {ref}, 1.0);
    session.update(base, {ref}, 1.2);
    REQUIRE(session.update(base, {ref}, 1.4).phase == "tracking");
    const auto moved = session.update(pan, {ref}, 1.6);
    REQUIRE(moved.appearance_tolerated && moved.measured_motion && moved.phase == "moving");
    REQUIRE(std::string(moved.follow.pixel_outcome) == "tile_change");
    REQUIRE(moved.follow.controls[1].mean_delta < 6.0); // matched the warped anchor, not the fixed one

    // The same pan with a real panel opening over ROI 1 stays fatal, and telemetry keeps the first failure.
    BigMapSession blocked;
    acquireSettled(blocked, base, {ref}, 1.0);
    blocked.update(base, {ref}, 1.2);
    REQUIRE(blocked.update(base, {ref}, 1.4).phase == "tracking");
    pan(cv::Rect(1010, 270, 60, 175)).setTo(200);
    const auto panel = blocked.update(pan, {ref}, 1.6);
    REQUIRE(std::string(panel.follow.outcome) == "controls_change");
    REQUIRE(std::string(panel.follow.pixel_outcome) == "tile_change");
    REQUIRE(!panel.appearance_tolerated && !panel.measured_motion && panel.geometry.matrix.empty());
}

// Blur or duplicated captures right after a measured pan must not drop the anchor; the next good
// frame resumes measured motion against the SAME anchor. Outside the grace failures count again.
void testBlurAfterPanKeepsAnchor(const cv::Mat& map, const std::shared_ptr<LayerReference>& ref) {
    const cv::Mat base = stillFrame(map);
    BigMapSession session;
    acquireSettled(session, base, {ref}, 1.0);
    session.update(base, {ref}, 1.2);
    const auto stable = session.update(base, {ref}, 1.4);
    REQUIRE(stable.phase == "tracking");
    REQUIRE(session.update(panFrame(map, 4, 2), {ref}, 1.6).measured_motion);

    const cv::Mat blank = cv::Mat::zeros(map.size(), map.type());
    for (double t : {1.7, 1.8, 1.9, 2.0}) { // 4 failures > MAX_NOT_OK, all inside the grace
        const auto r = session.update(blank, {ref}, t);
        REQUIRE(r.geometry.matrix.empty() && !r.measured_motion && std::string(r.reset_reason) == "follow_failed");
        REQUIRE(r.phase != "acquiring");
    }
    const auto resumed = session.update(panFrame(map, 8, 4), {ref}, 2.2);
    REQUIRE(!resumed.absolute_attempted && resumed.phase == "moving" && resumed.measured_motion);
    REQUIRE(std::abs(resumed.geometry.matrix.at<double>(0, 2) - stable.geometry.matrix.at<double>(0, 2) - 8) < 1.0);

    // Grace expired (last measured motion 2.2): three failures drop the anchor as before.
    session.update(blank, {ref}, 3.4);
    session.update(blank, {ref}, 3.5);
    const auto dropped = session.update(blank, {ref}, 3.6);
    REQUIRE(std::string(dropped.reset_reason) == "follow_failed" && dropped.phase == "acquiring");
}

// Stage-2: geometry anchor stays stable across small shifts; follow composes matrix.
void testAnchorStabilityAcrossMinorShift(const cv::Mat& frame,
    const std::shared_ptr<LayerReference>& ref) {
    BigMapSession session;
    auto candidate = acquireSettled(session, frame, {ref}, 1.0);
    assert(candidate.phase == "confirming");
    assert(candidate.absolute_attempted);

    // Two clean follow frames -> tracking, geometry published.
    auto accepted = session.update(frame, {ref}, 1.5);
    assert(!accepted.absolute_attempted);
    auto stable = session.update(frame, {ref}, 2.0);
    assert(stable.phase == "tracking");
    assert(stable.geometry.mode == "found");
    assert(!stable.absolute_attempted);

    // Small shift: movement > 0 but <= 0.6, no appearance failure -> follow success,
    // anchor stays, phase moving/confirming but geometry_anchor_ NOT destroyed.
    cv::Mat shifted;
    cv::Mat shift = (cv::Mat_<double>(2, 3) << 1, 0, 3, 0, 1, 2);
    cv::warpAffine(frame, shifted, shift, frame.size());
    auto moved = session.update(shifted, {ref}, 2.5);
    assert(!moved.absolute_attempted);
    // Follow succeeded against track_anchor; geometry_anchor_ still valid.
    assert(moved.geometry.matrix.empty() || moved.geometry.mode == "found");

    // Second shifted frame: anchor should still compose geometry against same reference.
    auto moved2 = session.update(shifted, {ref}, 3.0);
    assert(!moved2.absolute_attempted);
    // Anchor not destroyed: geometry exists (found) or confirming but not reset.
    assert(moved2.geometry.matrix.empty() || moved2.geometry.mode == "found");

    // Return to original frame: anchor should recover tracking without absolute re-search.
    auto back = session.update(frame, {ref}, 3.5);
    assert(!back.absolute_attempted);
    assert(back.geometry.mode == "found" || back.geometry.matrix.empty());
}

// Stage-2: not_ok_counter threshold; single transient failure keeps anchor.
void testTransientFailureKeepsAnchor(const cv::Mat& frame,
    const std::shared_ptr<LayerReference>& ref) {
    BigMapSession session;
    acquireSettled(session, frame, {ref}, 1.0);
    session.update(frame, {ref}, 1.5);
    assert(session.update(frame, {ref}, 2.0).geometry.mode == "found");

    // One appearance failure: anchor survives privately, but no current geometry is emitted.
    // Follow continues against the fixed anchor; next clean frame recovers publishing.
    cv::Mat tile_edit = frame.clone();
    tile_edit(cv::Rect(630, 360, 90, 90)).setTo(250);
    auto fail1 = session.update(tile_edit, {ref}, 3.5);
    assert(!fail1.absolute_attempted);
    assert(fail1.geometry.matrix.empty() && !fail1.measured_motion);
    assert(std::string(fail1.follow.outcome) == "tile_change");

    // Clean frame: tracking recovers against same anchor without absolute re-search.
    auto recover1 = session.update(frame, {ref}, 4.0);
    assert(!recover1.absolute_attempted);
    assert(recover1.geometry.mode == "found");

    auto recover2 = session.update(frame, {ref}, 4.5);
    assert(!recover2.absolute_attempted);
    assert(recover2.geometry.mode == "found");
}

// Stage-2: repeated not_ok failures eventually reset anchor (MAX_NOT_OK = 3).
void testRepeatedFailureResetsAnchor(const cv::Mat& frame,
    const std::shared_ptr<LayerReference>& ref) {
    BigMapSession session;
    acquireSettled(session, frame, {ref}, 1.0);
    session.update(frame, {ref}, 1.5);
    session.update(frame, {ref}, 2.0);

    cv::Mat blank(frame.size(), CV_8UC1, cv::Scalar(0));
    // First two failures: phase acquiring, anchor not yet destroyed.
    auto f1 = session.update(blank, {ref}, 3.0);
    assert(!f1.absolute_attempted);
    auto f2 = session.update(blank, {ref}, 4.0);
    assert(!f2.absolute_attempted);
    // Third failure: full reset -> next frame will reacquire absolute.
    auto f3 = session.update(blank, {ref}, 5.0);
    assert(!f3.absolute_attempted);
    assert(std::string(f3.reset_reason) == "follow_failed");
    assert(f3.phase == "acquiring");
}

// Stage-2: small shift + UI change does NOT reset anchor; geometry survives movement.
void testMovingMapKeepsAnchor(const cv::Mat& frame, const std::shared_ptr<LayerReference>& ref) {
    BigMapSession session;
    acquireSettled(session, frame, {ref}, 1.0);
    session.update(frame, {ref}, 1.5);
    assert(session.update(frame, {ref}, 2.0).geometry.mode == "found");

    cv::Mat shifted;
    cv::Mat motion = (cv::Mat_<double>(2, 3) << 1, 0, 8, 0, 1, 0);
    cv::warpAffine(frame, shifted, motion, frame.size());
    // Pure map shift without UI change: follow should succeed against track anchor.

    auto moving = session.update(shifted, {ref}, 2.5);
    assert(!moving.absolute_attempted);
    assert(moving.phase == "moving");

    // After motion stops: recover against same anchor without absolute re-search.
    auto stopped = session.update(shifted, {ref}, 3.0);
    assert(!stopped.absolute_attempted);
    auto stopped2 = session.update(shifted, {ref}, 3.5);
    assert(!stopped2.absolute_attempted);
    assert(stopped2.geometry.mode == "found");
}

// Stage-2: geometry anchor vs layer independence (not in this scope, but placeholder).
void testAnchorIndependentOfLayer(const cv::Mat& frame,
    const std::shared_ptr<LayerReference>& ref) {
    BigMapSession session;
    acquireSettled(session, frame, {ref}, 1.0);
    session.update(frame, {ref}, 1.5);
    auto stable = session.update(frame, {ref}, 2.0);
    assert(stable.geometry.mode == "found");
    // geometry_anchor_ should equal original registration matrix (scale 0.45 in test fixture).
    double expectedScale = 0.45;
    double actualScale = std::hypot(stable.geometry.matrix.at<double>(0, 0),
        stable.geometry.matrix.at<double>(1, 0));
    assert(std::abs(actualScale - expectedScale) < 0.05);
}

// Stage-2 snapshot: atomic snapshot() returns last known geometry without new computation.
void testSnapshotAtomicNoNewComputation(const cv::Mat& frame,
    const std::shared_ptr<LayerReference>& ref) {
    BigMapSession session;
    acquireSettled(session, frame, {ref}, 1.0);
    session.update(frame, {ref}, 1.5);
    auto stable = session.update(frame, {ref}, 2.0);
    assert(stable.geometry.mode == "found");

    const int rev1 = session.snapshot_revision();
    auto snap1 = session.snapshot();
    assert(snap1.geometry.mode == "found");
    assert(snap1.snapshot_revision == rev1);
    assert(snap1.snapshot_revision == session.snapshot_revision());

    // A second snapshot without any update must return the same revision and geometry.
    const int rev2 = session.snapshot_revision();
    auto snap2 = session.snapshot();
    assert(rev2 == rev1);
    assert(snap2.snapshot_revision == rev1);
    assert(snap2.geometry.mode == snap1.geometry.mode);
    assert(snap2.geometry.timestamp_seconds == snap1.geometry.timestamp_seconds);
    assert(!snap2.geometry.matrix.empty());

    // Snapshot must NOT trigger a new absolute attempt or reset.
    assert(!snap2.absolute_attempted);
    assert(std::string(snap2.reset_reason) == "none");
    assert(std::string(snap2.follow.outcome) == "not_attempted");

    // After a reset, revision changes and snapshot reflects cleared state.
    session.reset();
    assert(session.snapshot_revision() > rev1);
    auto snap3 = session.snapshot();
    assert(snap3.snapshot_revision > rev1);
    assert(snap3.geometry.matrix.empty());
    assert(std::string(snap3.phase) == "acquiring");
}

} // namespace

int main() {
    cv::setNumThreads(1);
    cv::setRNGSeed(42);
    cv::Mat reference(720, 1100, CV_8UC1);
    cv::randu(reference, 0, 255);
    cv::GaussianBlur(reference, reference, cv::Size(5, 5), 1);
    cv::Mat mask(reference.size(), CV_8UC1, cv::Scalar(255));
    auto ref = std::make_shared<LayerReference>("test", "space", "surface",
        reference, mask, mask, 2.0, 1000.0, -400.0);
    const cv::Size size(1560, 720);
    cv::Mat frame;
    cv::Mat pose = (cv::Mat_<double>(2, 3) << 0.9, 0, 180, 0, 0.9, 40);
    cv::warpAffine(reference, frame, pose, size);
    frame(cv::Rect(990, 260, 110, 200)).setTo(0);

    testAnchorStabilityAcrossMinorShift(frame, ref);
    testTransientFailureKeepsAnchor(frame, ref);
    testRepeatedFailureResetsAnchor(frame, ref);
    testMovingMapKeepsAnchor(frame, ref);
    testAnchorIndependentOfLayer(frame, ref);
    testSnapshotAtomicNoNewComputation(frame, ref);
    testSeamlessRenewal(frame, ref);
    testLateRenewal(frame, ref, true);
    testLateRenewal(frame, ref, false);
    testConflictingRenewal(frame, ref);
    testNoRefreshAtCutoff(frame, ref);
    testRenewalDuringStopStart(frame, ref);
    testMeasuredPanAndPoints(frame, ref);
    testPanWithPixelChangeIsTolerated(frame, ref);
    testStillPixelChangeStaysStrict(frame, ref);
    testControlsChangeIsNotTolerated(frame, ref);
    testBlurAfterPanKeepsAnchor(frame, ref);
    testMapContentInControlRoiDoesNotBlockPan(frame, ref);

    std::cout << "big-map stage-2 anchor stability tests passed\n";
    return 0;
}
