// Local opt-in diagnostic. Inputs stay outside Git; output contains no images or UID.
#include "luxin/bigmap_geometry.hpp"
#include <chrono>
#include <iostream>
#include <opencv2/imgcodecs.hpp>

int main(int argc, char** argv) try {
    cv::setNumThreads(2);
    int next = 1;
    std::vector<std::shared_ptr<luxin::LayerReference>> refs;
    while (next < argc && std::string(argv[next]) == "--ref" && next + 5 < argc) {
        const auto gray = cv::imread(argv[next + 1], cv::IMREAD_GRAYSCALE);
        if (gray.empty()) return 2;
        cv::Mat mask;
        cv::compare(gray, 0, mask, cv::CMP_GT);
        refs.push_back(std::make_shared<luxin::LayerReference>("reference-" + std::to_string(refs.size()),
            argv[next + 2], "unknown", gray, mask, mask,
            std::stod(argv[next + 3]), std::stod(argv[next + 4]), std::stod(argv[next + 5])));
        next += 6;
    }
    if (refs.empty() || next >= argc || std::string(argv[next]) != "--frames" || ++next >= argc) {
        std::cerr << "usage: bigmap_geometry_frames --ref reference.png space units_per_px origin_x origin_y [--ref ...] --frames frame.jpg...\n";
        return 2;
    }
    const int first = next;
    for (int i = first; i < argc; ++i) {
        auto frame = cv::imread(argv[i]);
        if (frame.empty()) return 2;
        const auto start = std::chrono::steady_clock::now();
        auto result = luxin::registerBigMap(frame, refs, i);
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
        std::cout << "input=" << i - first + 1 << " mode=" << result.mode << " ref=" << result.reference << " inliers=" << result.inliers
            << " errorPx=" << result.error_px << " support=" << result.support << " ms=" << ms;
        if (!result.matrix.empty()) std::cout << " matrix=" << result.matrix;
        std::cout << '\n';
    }
} catch (const std::exception&) {
    std::cerr << "Invalid input or registration failure\n";
    return 2;
}
