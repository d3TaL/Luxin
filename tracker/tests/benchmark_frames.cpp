#include "luxin/split_tracker.hpp"

#include <chrono>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include <opencv2/imgcodecs.hpp>

using namespace luxin;

namespace {

struct RefSpec {
    std::string name;
    std::string space;
    std::string layer;
    std::string prefix;
    double upp = 1.0;
    double ox = 0.0;
    double oy = 0.0;
};

} // namespace

int main(int argc, char** argv) {
    if (argc < 5) {
        std::cerr << "Usage: benchmark_frames <origin_x> <origin_y> <ref_dir> <layer1> <layer2> [layer3...] --frames <t1:png> [t2:png...]\n";
        return 1;
    }

    const double ox = std::stod(argv[1]);
    const double oy = std::stod(argv[2]);
    const std::string ref_dir = argv[3];

    std::vector<std::string> layers;
    std::vector<std::pair<double, std::string>> frames;
    bool reading_frames = false;
    for (int i = 4; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--frames") {
            reading_frames = true;
            continue;
        }
        if (!reading_frames) {
            layers.push_back(arg);
        } else {
            auto colon = arg.find(':');
            if (colon == std::string::npos) {
                std::cerr << "Invalid frame spec: " << arg << "\n";
                return 1;
            }
            double t = std::stod(arg.substr(0, colon));
            std::string path = arg.substr(colon + 1);
            frames.emplace_back(t, path);
        }
    }

    auto t_init0 = std::chrono::steady_clock::now();
    cv::Mat ctx = cv::imread(ref_dir + "/context.mask.png", cv::IMREAD_GRAYSCALE);
    if (ctx.empty()) {
        std::cerr << "Cannot read " << ref_dir << "/context.mask.png\n";
        return 1;
    }

    std::vector<std::shared_ptr<LayerReference>> models;
    for (const auto& layer : layers) {
        cv::Mat gray = cv::imread(ref_dir + "/" + layer + ".png", cv::IMREAD_GRAYSCALE);
        cv::Mat active = cv::imread(ref_dir + "/" + layer + ".active.png", cv::IMREAD_GRAYSCALE);
        if (gray.empty() || active.empty()) {
            std::cerr << "Cannot read layer files for " << layer << "\n";
            return 1;
        }
        models.push_back(std::make_shared<LayerReference>(
            layer, "twt702", layer, gray, active, ctx, 1.0, ox, oy
        ));
    }
    auto t_init1 = std::chrono::steady_clock::now();
    double init_ms = std::chrono::duration<double, std::milli>(t_init1 - t_init0).count();
    std::cout << "Built " << models.size() << " C++ SIFT models in " << std::fixed << std::setprecision(1) << init_ms << " ms\n";

    MinimapSpec spec = MinimapSpec::forFrameHeight(1080);
    SplitTracker tracker(models, spec.base_scale);

    for (const auto& [t, path] : frames) {
        cv::Mat frame = cv::imread(path, cv::IMREAD_COLOR);
        if (frame.empty()) {
            std::cerr << "Cannot read frame " << path << "\n";
            return 1;
        }
        auto t0 = std::chrono::steady_clock::now();
        MinimapCrop mm = MinimapCrop::fromBgrFrame(frame, spec);
        TrackingStepResult res = tracker.update(mm, t);
        auto t1 = std::chrono::steady_clock::now();
        double step_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();

        std::cout << std::fixed << std::setprecision(2)
                  << "t=" << t << "s | search=" << res.search_mode
                  << " | pos=" << res.position_mode;
        if (res.has_position) {
            std::cout << " (" << res.position.x << ", " << res.position.y
                      << ", scale=" << std::setprecision(4) << res.position.scale << ")";
        }
        std::cout << std::setprecision(2)
                  << " | layer_mode=" << res.layer.mode
                  << " layer=" << (res.layer.layer.empty() ? "-" : res.layer.layer)
                  << " err=" << res.layer.error
                  << " mrg=" << res.layer.margin
                  << " | overlay=" << (res.overlayReady() ? 1 : 0)
                  << " | " << std::setprecision(1) << step_ms << " ms\n";
    }
    return 0;
}
