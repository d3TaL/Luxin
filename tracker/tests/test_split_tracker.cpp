#include "luxin/split_tracker.hpp"

#include <cassert>
#include <cmath>
#include <iostream>
#include <random>
#include <vector>

#include <opencv2/imgproc.hpp>

using namespace luxin;

namespace {

PositionFix makePosition(std::string space = "twt702", double x = 50.0, double y = 50.0, double scale = 1.125, double score = 0.8) {
    PositionFix p;
    p.space = std::move(space);
    p.x = x;
    p.y = y;
    p.scale = scale;
    p.rotation = 0.0;
    p.support = 0.5;
    p.inliers = 12;
    p.error_px = 0.3;
    p.score = score;
    return p;
}

void testChoosePosition() {
    SplitGates gates;
    auto p1 = makePosition("twt702", 50.0, 50.0, 1.125, 0.80);
    auto p2 = makePosition("twt702", 50.2, 50.0, 1.125, 0.81);
    PositionFix best;
    auto [ok, mode] = choosePosition({p1, p2}, gates, best);
    assert(ok);
    assert(mode == "candidate");
    assert(std::abs(best.x - 50.2) < 1e-6);

    auto p_far = makePosition("twt702", 100.0, 50.0, 1.125, 0.80);
    auto [ok2, mode2] = choosePosition({p1, p_far}, gates, best);
    assert(!ok2);
    assert(mode2 == "ambiguous");

    auto p_other_space = makePosition("kzsd2", 50.0, 50.0, 1.125, 0.80);
    auto [ok3, mode3] = choosePosition({p1, p_other_space}, gates, best);
    assert(!ok3);
    assert(mode3 == "ambiguous");
}

void testSmallSceneSupportAndPoseMatrix() {
    cv::Mat gray = cv::Mat::zeros(200, 200, CV_8UC1);
    cv::Mat context = cv::Mat::zeros(200, 200, CV_8UC1);
    context(cv::Rect(20, 20, 21, 21)).setTo(255);

    LayerReference model("small", "twt702", "UG_SMALL", gray, context, context, 1.0, 0.0, 0.0);

    Registration reg;
    reg.x = 50.0;
    reg.y = 50.0;
    reg.scale = 1.0;
    reg.rotation = 0.0;
    reg.inliers = 8;
    reg.matches = 10;
    reg.error_px = 0.2;
    reg.matrix = (cv::Mat_<double>(2, 3) << 1.0, 0.0, 0.0, 0.0, 1.0, 0.0);
    reg.inlier_points = {
        {21.0f, 21.0f}, {30.0f, 21.0f}, {39.0f, 21.0f}, {39.0f, 30.0f},
        {39.0f, 39.0f}, {30.0f, 39.0f}, {21.0f, 39.0f}, {21.0f, 30.0f}
    };

    MinimapCrop mm;
    mm.gray = gray;
    mm.mask = cv::Mat(200, 200, CV_8UC1, cv::Scalar(255));
    mm.lx = 50.0;
    mm.ly = 50.0;

    PositionFix fix;
    bool ok = positionFromRegistration(model, reg, mm, SplitGates(), fix);
    assert(ok);
    assert(fix.support > 0.7);

    LayerReference ref2("ref2", "twt702", "UG1", gray, context, context, 2.0, 1000.0, -2000.0);
    PositionFix p = makePosition("twt702", 1200.0, -1800.0, 1.0, 0.8);
    cv::Mat M = poseMatrix(ref2, mm, p);
    double rx0 = M.at<double>(0, 0) * 50.0 + M.at<double>(0, 1) * 50.0 + M.at<double>(0, 2);
    double ry0 = M.at<double>(1, 0) * 50.0 + M.at<double>(1, 1) * 50.0 + M.at<double>(1, 2);
    assert(std::abs(rx0 - 100.0) < 1e-6);
    assert(std::abs(ry0 - 100.0) < 1e-6);
}

void testAppearanceAndLayerLatch() {
    std::mt19937 rng(42);
    std::uniform_int_distribution<int> dist(100, 170);

    std::vector<cv::Mat> masks(3);
    for (int i = 0; i < 3; ++i) {
        masks[i] = cv::Mat::zeros(120, 120, CV_8UC1);
    }
    masks[0](cv::Rect(0, 0, 60, 60)).setTo(255);
    masks[1](cv::Rect(0, 65, 60, 50)).setTo(255);
    masks[2](cv::Rect(80, 70, 16, 16)).setTo(255); // Small floor (<2% of ring)

    cv::Mat texture(120, 120, CV_64F);
    for (int y = 0; y < 120; ++y) {
        for (int x = 0; x < 120; ++x) {
            texture.at<double>(y, x) = static_cast<double>(dist(rng));
        }
    }

    std::vector<cv::Mat> models(3);
    for (int active = 0; active < 3; ++active) {
        cv::Mat img(120, 120, CV_8UC1, cv::Scalar(30));
        for (int i = 0; i < 3; ++i) {
            const double factor = (i == active) ? 1.0 : 0.45;
            for (int y = 0; y < 120; ++y) {
                for (int x = 0; x < 120; ++x) {
                    if (masks[i].at<uchar>(y, x) > 0) {
                        img.at<uchar>(y, x) = static_cast<uchar>(std::round(texture.at<double>(y, x) * factor));
                    }
                }
            }
        }
        models[active] = img;
    }

    cv::Mat full_mask(120, 120, CV_8UC1, cv::Scalar(255));
    SplitGates gates;

    for (int active = 0; active < 3; ++active) {
        cv::Mat query(120, 120, CV_8UC1);
        for (int y = 0; y < 120; ++y) {
            for (int x = 0; x < 120; ++x) {
                double v = models[active].at<uchar>(y, x) * 0.8 + 15.0;
                query.at<uchar>(y, x) = static_cast<uchar>(std::clamp(v, 0.0, 255.0));
            }
        }
        // Add bright white icon noise in top-left
        query(cv::Rect(10, 10, 20, 20)).setTo(255);

        auto [scores, reason] = appearanceErrors(query, models, masks, full_mask, gates);
        assert(!scores.empty());
        int best_idx = 0;
        for (int i = 1; i < 3; ++i) {
            if (scores[i].error < scores[best_idx].error) {
                best_idx = i;
            }
        }
        assert(best_idx == active);
        assert(scores[active].error < 3.0);
    }

    // LayerLatch confirmation & immediate clear on unknown
    LayerLatch latch;
    LayerDecision d1;
    d1.layer = "UG1";
    d1.mode = "candidate";
    auto r1 = latch.update(d1, 0.0);
    assert(r1.layer.empty() && r1.mode == "pending");

    auto r2 = latch.update(d1, 0.5);
    assert(r2.layer == "UG1" && r2.mode == "known");

    LayerDecision d_unk;
    d_unk.reason = "ambiguous";
    auto r3 = latch.update(d_unk, 1.0);
    assert(r3.layer.empty() && r3.mode == "unknown");

    auto r4 = latch.update(d1, 1.5);
    assert(r4.layer.empty() && r4.mode == "pending");
}

void testFullSiftSplitTrackerEndToEnd() {
    cv::theRNG().state = 12345;
    std::mt19937 rng(7);
    std::uniform_int_distribution<int> dist(40, 200);

    cv::Mat base_tex(360, 360, CV_8UC1);
    for (int y = 0; y < 360; ++y) {
        for (int x = 0; x < 360; ++x) {
            base_tex.at<uchar>(y, x) = static_cast<uchar>(dist(rng));
        }
    }
    cv::GaussianBlur(base_tex, base_tex, cv::Size(5, 5), 1.1);

    cv::Mat mask_a = cv::Mat::zeros(360, 360, CV_8UC1);
    cv::Mat mask_b = cv::Mat::zeros(360, 360, CV_8UC1);
    mask_a(cv::Rect(40, 40, 180, 260)).setTo(255);
    mask_b(cv::Rect(160, 60, 160, 240)).setTo(255);
    cv::Mat ctx = mask_a | mask_b;

    auto makeFloorImg = [&](bool a_active) {
        cv::Mat out(360, 360, CV_8UC1, cv::Scalar(30));
        for (int y = 0; y < 360; ++y) {
            for (int x = 0; x < 360; ++x) {
                bool in_a = mask_a.at<uchar>(y, x) > 0;
                bool in_b = mask_b.at<uchar>(y, x) > 0;
                double v = 30.0;
                if (a_active) {
                    if (in_b) v = base_tex.at<uchar>(y, x) * 0.45;
                    if (in_a) v = base_tex.at<uchar>(y, x) * 1.0;
                } else {
                    if (in_a) v = base_tex.at<uchar>(y, x) * 0.45;
                    if (in_b) v = base_tex.at<uchar>(y, x) * 1.0;
                }
                out.at<uchar>(y, x) = static_cast<uchar>(std::clamp(v, 0.0, 255.0));
            }
        }
        return out;
    };

    cv::Mat img_a = makeFloorImg(true);
    cv::Mat img_b = makeFloorImg(false);

    // Distinct texture for distant location
    cv::Mat img_distant(360, 360, CV_8UC1);
    for (int y = 0; y < 360; ++y) {
        for (int x = 0; x < 360; ++x) {
            img_distant.at<uchar>(y, x) = static_cast<uchar>((x * 13 + y * 17) % 256);
        }
    }

    auto ref_a = std::make_shared<LayerReference>("ug1", "twt702", "UG1", img_a, mask_a, ctx, 1.0, 1000.0, -2000.0);
    auto ref_b = std::make_shared<LayerReference>("ug2", "twt702", "UG2", img_b, mask_b, ctx, 1.0, 1000.0, -2000.0);
    // Add a distant third location model (e.g. 9000 units away) with distinct texture
    auto ref_distant = std::make_shared<LayerReference>("distant", "twt702", "DISTANT_FLOOR", img_distant, mask_a, ctx, 1.0, 10000.0, -20000.0);

    SplitTracker tracker({ref_a, ref_b, ref_distant}, 0.5625);

    cv::Mat crop_gray = img_a(cv::Rect(90, 90, 180, 180)).clone();
    // Simulate a drawn marker icon at (50, 50) in the crop, and exclude it via MaskCircle
    cv::circle(crop_gray, cv::Point(50, 50), 10, cv::Scalar(255), -1);

    cv::Mat ring_mask = cv::Mat::zeros(180, 180, CV_8UC1);
    cv::circle(ring_mask, cv::Point(90, 90), 82, cv::Scalar(255), -1);
    cv::circle(ring_mask, cv::Point(90, 90), 14, cv::Scalar(0), -1);

    MinimapCrop mm = MinimapCrop::fromGrayCrop(
        crop_gray, ring_mask, 90.0, 90.0, 1.0, {{50.0f, 50.0f, 12.0f}}
    );
    assert(mm.mask.at<uchar>(50, 50) == 0);

    auto step1 = tracker.update(mm, 0.0);
    assert(!step1.has_position && step1.position_mode == "pending");
    assert(!step1.overlayReady());

    auto step2 = tracker.update(mm, 0.5);
    assert(step2.has_position && step2.position_mode == "found");
    assert(std::abs(step2.position.x - 1180.0) < 1.0);
    assert(std::abs(step2.position.y - (-1820.0)) < 1.0);
    assert(step2.layer.mode == "pending");
    assert(!step2.overlayReady());

    auto step3 = tracker.update(mm, 1.0);
    assert(step3.has_position && step3.position_mode == "track");
    assert(step3.layer.mode == "known" && step3.layer.layer == "UG1");
    assert(step3.overlayReady());
}

} // namespace

int main() {
    testChoosePosition();
    testSmallSceneSupportAndPoseMatrix();
    testAppearanceAndLayerLatch();
    testFullSiftSplitTrackerEndToEnd();
    std::cout << "All C++ SplitTracker tests passed!\n";
    return 0;
}
