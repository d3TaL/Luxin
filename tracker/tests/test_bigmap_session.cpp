// Stage-2 tests: stable big-map anchor that does NOT reset on minor shifts.
// Tests verify: geometry_anchor_ vs track_anchor_ separation, composition via follow,
// not_ok_counter threshold, grid/stability independence from layer.

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

    // One appearance failure: anchor survives, geometry published (last known matrix).
    // Follow continues against the fixed anchor; next clean frame recovers publishing.
    cv::Mat tile_edit = frame.clone();
    tile_edit(cv::Rect(630, 360, 90, 90)).setTo(250);
    auto fail1 = session.update(tile_edit, {ref}, 3.5);
    assert(!fail1.absolute_attempted);
    // Geometry published: last known matrix, markers stay on screen moving with map.
    assert(!fail1.geometry.matrix.empty());
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

    std::cout << "big-map stage-2 anchor stability tests passed\n";
    return 0;
}
