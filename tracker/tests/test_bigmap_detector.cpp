#include "luxin/bigmap_detector.hpp"
#include "../src/bigmap_templates.hpp"

#include <chrono>
#include <iostream>
#include <stdexcept>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

namespace {
void require(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}

cv::Mat fixture(bool plus = true, bool minus = true, bool track = true, int shift = 0) {
    cv::Mat image(720, 1560, CV_8U, cv::Scalar(20));
    if (track) {
        image(cv::Rect(96 + shift, 290, 1, 140)).setTo(100);
        image(cv::Rect(104 + shift, 290, 1, 140)).setTo(100);
    }
    if (plus) cv::Mat(24, 24, CV_8U, const_cast<uint8_t*>(luxin::bigmap_templates::plus)).copyTo(image(cv::Rect(88 + shift, 268, 24, 24)));
    if (minus) cv::Mat(24, 24, CV_8U, const_cast<uint8_t*>(luxin::bigmap_templates::minus)).copyTo(image(cv::Rect(88 + shift, 428, 24, 24)));
    return image;
}

void testSynthetic() {
    using luxin::detectBigMap;
    require(detectBigMap(fixture()).detected, "complete slider not detected");
    require(detectBigMap(fixture(true, true, true, 30)).detected, "horizontal shift not detected");
    require(!detectBigMap(fixture(false, true)).detected, "minus alone detected");
    require(!detectBigMap(fixture(true, false)).detected, "plus alone detected");
    require(!detectBigMap(fixture(true, true, false)).detected, "buttons without track detected");
    cv::Mat wrongGap = fixture();
    wrongGap(cv::Rect(88, 428, 24, 24)).setTo(20);
    cv::Mat(24, 24, CV_8U, const_cast<uint8_t*>(luxin::bigmap_templates::minus)).copyTo(wrongGap(cv::Rect(88, 458, 24, 24)));
    require(!detectBigMap(wrongGap).detected, "wrong vertical separation detected");
    for (int h : {480, 1080, 1440}) {
        cv::Mat scaled;
        cv::resize(fixture(), scaled, cv::Size(h * 13 / 6, h), 0, 0, cv::INTER_LINEAR);
        require(detectBigMap(scaled).detected, "scaled slider not detected");
    }
    cv::Mat brighter;
    fixture().convertTo(brighter, CV_8U, 0.85, 25);
    require(detectBigMap(brighter).detected, "brightness correction failed");
    auto occludedCorners = fixture();
    occludedCorners(cv::Rect(88, 268, 5, 5)).setTo(255);
    occludedCorners(cv::Rect(107, 447, 5, 5)).setTo(255);
    require(detectBigMap(occludedCorners).detected, "icons outside diamond changed detection");
    require(!detectBigMap(cv::Mat()).detected, "empty accepted");
    require(!detectBigMap(cv::Mat(720, 1560, CV_8U, cv::Scalar(0))).detected, "black accepted");
    require(!detectBigMap(cv::Mat(720, 1560, CV_8U, cv::Scalar(255))).detected, "white accepted");
    require(!detectBigMap(cv::Mat(1440, 720, CV_8U, cv::Scalar(20))).detected, "portrait accepted");
    require(!detectBigMap(cv::Mat(720, 1560, CV_32F)).detected, "float accepted");
    require(!detectBigMap(cv::Mat(720, 1560, CV_8UC4)).detected, "unsupported channels accepted");
    cv::Mat noise(720, 1560, CV_8U);
    cv::RNG rng(42);
    for (int i = 0; i < 10; ++i) {
        rng.fill(noise, cv::RNG::UNIFORM, 0, 256);
        require(!detectBigMap(noise).detected, "noise accepted");
    }
}
} // namespace

// Optional labelled files: test_bigmap_detector <0|1> <image> ...
// With no arguments this is a self-contained CTest (does not need private screenshots).
int main(int argc, char** argv) {
    try {
        testSynthetic();
        if ((argc - 1) % 2 != 0) throw std::runtime_error("expected <0|1> <image> pairs");
        for (int i = 1; i < argc; i += 2) {
            const std::string label(argv[i]);
            require(label == "0" || label == "1", "invalid expected label");
            const auto image = cv::imread(argv[i + 1]);
            require(!image.empty(), "cannot open image");
            const auto start = std::chrono::steady_clock::now();
            const auto s = luxin::detectBigMap(image);
            const auto ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
            std::cout << argv[i + 1] << " detected=" << s.detected << " plus=" << s.plus_score
                      << " minus=" << s.minus_score << " track=" << s.track_fraction << " ms=" << ms << '\n';
            require(s.detected == (label == "1"), "wrong classification");
        }
        std::cout << "Big-map detector tests passed\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
