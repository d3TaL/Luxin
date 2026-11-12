#include "luxin/bigmap_geometry.hpp"
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <opencv2/imgproc.hpp>

using namespace luxin;

int main() {
    const cv::Size qualityFrame(2000, 1440);
    assert(bigMapGeometryQualityAccepted(12, 40, 2.5, 14400, 288000, qualityFrame));
    assert(!bigMapGeometryQualityAccepted(11, 40, 2.5, 14400, 288000, qualityFrame));
    assert(!bigMapGeometryQualityAccepted(12, 41, 2.5, 14400, 288000, qualityFrame));
    assert(!bigMapGeometryQualityAccepted(12, 40, 2.501, 14400, 288000, qualityFrame));
    assert(!bigMapGeometryQualityAccepted(12, 40, 2.5, 14399, 200000, qualityFrame));
    assert(!bigMapGeometryQualityAccepted(12, 40, 2.5, 14400, 288001, qualityFrame));
    assert(!bigMapGeometryQualityAccepted(12, 40, 0, 14400, 0, qualityFrame));
    assert(!bigMapGeometryQualityAccepted(12, 40, std::numeric_limits<double>::quiet_NaN(), 14400, 288000, qualityFrame));
    cv::setNumThreads(1);
    cv::setRNGSeed(42);
    cv::Mat reference(720, 1100, CV_8UC1);
    cv::randu(reference, 0, 255);
    cv::GaussianBlur(reference, reference, cv::Size(5, 5), 1.0);
    cv::Mat mask(reference.size(), CV_8UC1, cv::Scalar(255));
    auto ref = std::make_shared<LayerReference>("test", "space", "surface", reference, mask, mask, 2.0, 1000, -400);
    const cv::Size size(1560, 720);
    cv::Mat image;
    cv::Mat transform = (cv::Mat_<double>(2, 3) << 0.9, 0, 180, 0, 0.9, 40);
    cv::warpAffine(reference, image, transform, size);
    auto g = registerBigMap(image, {ref}, 12.5);
    assert(g.mode == "found");
    assert(g.space == "space" && g.frame_width == 1560 && g.timestamp_seconds == 12.5);
    assert(g.inliers >= 12 && g.support >= 0.1);
    for (const auto& p : std::vector<cv::Point2d>{{1100, -200}, {1800, 400}, {2500, 700}}) {
        double x = g.matrix.at<double>(0, 0) * p.x + g.matrix.at<double>(0, 1) * p.y + g.matrix.at<double>(0, 2);
        double y = g.matrix.at<double>(1, 0) * p.x + g.matrix.at<double>(1, 1) * p.y + g.matrix.at<double>(1, 2);
        assert(std::hypot(x - (0.45 * (p.x - 1000) + 180), y - (0.45 * (p.y + 400) + 40)) < 2.0);
    }
    // Same appearance in different coordinate spaces must not select one arbitrarily.
    auto other = std::make_shared<LayerReference>("other", "other_space", "surface", reference, mask, mask, 2, 1000, -400);
    assert(registerBigMap(image, {ref, other}, 1).mode == "ambiguous");
    auto shifted = std::make_shared<LayerReference>("shifted", "space", "shifted", reference, mask, mask, 2, 1500, -400);
    assert(registerBigMap(image, {ref, shifted}, 1).mode == "ambiguous");
    // Equivalent references for different floors do not imply floor knowledge or ambiguity.
    auto floor = std::make_shared<LayerReference>("floor", "space", "UG1", reference, mask, mask, 2, 1000, -400);
    assert(registerBigMap(image, {ref, floor}, 1).mode == "found");
    assert(registerBigMap(cv::Mat(size, CV_8UC1, cv::Scalar(0)), {ref}, 1).mode == "rejected");
    assert(registerBigMap(image, {}, 1).mode == "rejected");
    cv::Mat emptyMask = cv::Mat::zeros(reference.size(), CV_8UC1);
    auto noContext = std::make_shared<LayerReference>("empty", "space", "surface", reference, emptyMask, emptyMask, 2, 1000, -400);
    assert(registerBigMap(image, {noContext}, 1).mode == "rejected");
    assert(registerBigMap(image, {ref}, -1).mode == "rejected");
    assert(registerBigMap(cv::Mat(), {ref}, 1).mode == "rejected");
    assert(registerBigMap(cv::Mat(size, CV_8UC4), {ref}, 1).mode == "rejected");
    cv::Mat colour;
    cv::cvtColor(image, colour, cv::COLOR_GRAY2BGR);
    assert(registerBigMap(colour, {ref}, 1).mode == "found");
    auto ui = bigMapGeometryMask(size);
    assert(ui.at<uchar>(100, 1400) == 0); // potential side panel
    assert(ui.at<uchar>(280, 100) == 0); // zoom slider
    assert(ui.at<uchar>(400, 700) == 255);
    // Fewer than twelve spatially unique matches must not produce a registration.
    cv::Mat tinyPatch(size, CV_8UC1, cv::Scalar(0));
    image(cv::Rect(650, 400, 10, 10)).copyTo(tinyPatch(cv::Rect(650, 400, 10, 10)));
    assert(registerBigMap(tinyPatch, {ref}, 1).mode == "rejected");
    // Even a perfectly matching reference only inside UI must not localize the map.
    cv::Mat uiOnly(size, CV_8UC1, cv::Scalar(0));
    image.copyTo(uiOnly, ui == 0);
    assert(registerBigMap(uiOnly, {ref}, 1).mode == "rejected");
    cv::Mat rotated;
    cv::warpAffine(reference, rotated, cv::getRotationMatrix2D(cv::Point2f(500, 360), 10, 1), size);
    assert(registerBigMap(rotated, {ref}, 1).mode == "rejected");
    // Exercise extraction downscaling and conversion back to original frame pixels.
    cv::Mat large;
    cv::resize(image, large, cv::Size(4680, 2160));
    auto big = registerBigMap(large, {ref}, 2);
    assert(big.mode == "found");
    assert(std::abs(big.matrix.at<double>(0, 0) - 1.35) < 0.01);
    assert(std::abs(big.matrix.at<double>(0, 2) - (540 - 1350)) < 3);
    cv::Mat odd;
    cv::resize(image, odd, cv::Size(3121, 1441));
    auto oddGeometry = registerBigMap(odd, {ref}, 2);
    assert(oddGeometry.mode == "found");
    assert(std::abs(oddGeometry.matrix.at<double>(0, 0) - 0.45 * 3121 / 1560) < 0.01);
    cv::Mat zoomed;
    cv::Mat zoomTransform = (cv::Mat_<double>(2, 3) << 1.4, 0, -200, 0, 1.4, -100);
    cv::warpAffine(reference, zoomed, zoomTransform, size);
    auto zoom = registerBigMap(zoomed, {ref}, 3);
    assert(zoom.mode == "found");
    assert(std::abs(zoom.matrix.at<double>(0, 0) - 0.7) < 0.01);
    std::cout << "big-map geometry tests passed\n";
}
