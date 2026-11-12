#include "luxin/bigmap_session.hpp"
#include <cassert>
#include <iostream>
#include <opencv2/imgproc.hpp>

using namespace luxin;

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
    auto candidate = session.update(frame, {ref}, 1);
    assert(candidate.phase == "confirming" && candidate.geometry.matrix.empty() && candidate.absolute_attempted);
    auto first = session.update(frame, {ref}, 1.5);
    assert(first.geometry.matrix.empty() && !first.absolute_attempted);
    auto stable = session.update(frame, {ref}, 2);
    assert(stable.phase == "tracking" && stable.geometry.mode == "found" && !stable.absolute_attempted);
    assert(stable.geometry.timestamp_seconds == 2);
    assert(std::abs(stable.geometry.matrix.at<double>(0, 0) - 0.45) < 0.01);
    BigMapSession slow;
    assert(slow.update(frame, {ref}, 1).phase == "confirming");
    assert(slow.update(frame, {ref}, 4).geometry.matrix.empty());
    assert(slow.update(frame, {ref}, 4.5).phase == "tracking");
    // Blanking left-top UI must not make a fake movement in the geometric matching.
    cv::Mat uiOnlyChange = frame.clone();
    uiOnlyChange(cv::Rect(0, 0, 380, 200)).setTo(255);
    assert(slow.update(uiOnlyChange, {ref}, 5).geometry.mode == "found");
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
    assert(session.update(changed, {ref}, 5.5).geometry.matrix.empty());
    session.reset();
    assert(session.update(frame, {ref}, 5).phase == "confirming");
    assert(session.update(frame, {ref}, 5).geometry.matrix.empty()); // duplicate timestamp resets
    assert(session.update(frame, {ref}, 6).phase == "confirming");
    assert(session.update(cv::Mat(size, CV_8UC1, cv::Scalar(0)), {ref}, 6.5).geometry.matrix.empty());
    // Expired absolute anchor cannot silently acquire a fresh timestamp forever.
    session.reset();
    assert(session.update(frame, {ref}, 10).phase == "confirming");
    for (double t = 10.5; t <= 20; t += 0.5) {
        auto value = session.update(frame, {ref}, t);
        assert(!value.absolute_attempted);
    }
    auto refresh = session.update(frame, {ref}, 21);
    assert(refresh.absolute_attempted && refresh.geometry.matrix.empty());
    assert(session.update(frame, {ref}, 21.5).geometry.matrix.empty());
    assert(session.update(frame, {ref}, 22).geometry.mode == "found");
    // Controls changed even though map texture stayed identical.
    cv::Mat controls = frame.clone();
    controls(cv::Rect(1395, 250, 85, 225)).setTo(255);
    assert(session.update(controls, {ref}, 22.5).geometry.matrix.empty());
    // Changed capture geometry and long absence cannot reuse the old anchor.
    cv::Mat resized;
    cv::resize(frame, resized, cv::Size(2340, 1080));
    auto resizedCandidate = session.update(resized, {ref}, 23);
    assert(resizedCandidate.absolute_attempted && resizedCandidate.geometry.matrix.empty());
    auto longGap = session.update(resized, {ref}, 40);
    assert(longGap.absolute_attempted && longGap.geometry.matrix.empty());
    // Same-space appearance switch and an unrelated image both fail closed.
    cv::Mat unrelated(frame.size(), CV_8UC1);
    cv::randu(unrelated, 0, 255);
    assert(session.update(unrelated, {ref}, 40.5).geometry.matrix.empty());
    assert(session.update(frame, {ref}, -1).geometry.matrix.empty());
    BigMapSession ambiguous;
    auto other = std::make_shared<LayerReference>("other", "other-space", "surface", reference, mask, mask, 2, 1000, -400);
    auto uncertain = ambiguous.update(frame, {ref, other}, 1);
    assert(uncertain.phase == "ambiguous" && uncertain.geometry.mode == "ambiguous" && uncertain.geometry.matrix.empty());
    // Recoverable bad input invalidates stationarity/anchor, not just this one result.
    BigMapSession recovery;
    assert(recovery.update(frame, {ref}, 1).phase == "confirming");
    recovery.update(frame, {ref}, 1.5);
    assert(recovery.update(frame, {ref}, 2).geometry.mode == "found");
    assert(recovery.update(cv::Mat(size, CV_8UC4), {ref}, 2.5).geometry.matrix.empty());
    assert(recovery.update(frame, {ref}, 3).phase == "confirming");
    assert(recovery.update(frame, {ref}, 3.5).geometry.matrix.empty());
    assert(recovery.update(frame, {ref}, 4).geometry.mode == "found");
    std::cout << "big-map session tests passed\n";
}
