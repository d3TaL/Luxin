// Desktop replay of a BigMapDumpFormat burst. No references, SIFT, or absolute location.
#include "luxin/bigmap_motion.hpp"

#include <charconv>
#include <chrono>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string_view>
#include <opencv2/core/utils/logger.hpp>
#include <opencv2/imgcodecs.hpp>

namespace {
namespace fs = std::filesystem;
using Clock = std::chrono::steady_clock;
constexpr char header[] = "index,timestampNs,width,height,generation,screenMode,dropped_before,has_color,"
                          "source_width,source_height,scale";

// The Android writer sanitizes the only text column (no commas/quotes/newlines).
struct Frame {
    int index, width, height, dropped, color, source_w, source_h;
    int64_t ns;
    uint64_t generation;
    std::string mode;
};
template <class T> T integer(std::string_view text) {
    T v{};
    const auto end = text.data() + text.size();
    auto r = std::from_chars(text.data(), end, v);
    if (r.ec != std::errc{} || r.ptr != end || v < 0)
        throw std::runtime_error("invalid integer");
    return v;
}
Frame parse(const std::string &line) {
    std::array<std::string_view, 11> f;
    std::string_view rest(line);
    for (size_t i = 0; i < f.size(); ++i) {
        const auto comma = rest.find(',');
        if ((comma == rest.npos) != (i == f.size() - 1))
            throw std::runtime_error("wrong column count");
        f[i] = rest.substr(0, comma);
        if (comma != rest.npos)
            rest.remove_prefix(comma + 1);
    }
    Frame v{};
    v.index = integer<int>(f[0]);
    v.ns = integer<int64_t>(f[1]);
    v.width = integer<int>(f[2]);
    v.height = integer<int>(f[3]);
    v.generation = integer<uint64_t>(f[4]);
    if (f[5].empty() || f[5].find_first_of("\"\r\t ") != f[5].npos)
        throw std::runtime_error("invalid screenMode");
    v.mode = f[5];
    v.dropped = integer<int>(f[6]);
    v.color = integer<int>(f[7]);
    v.source_w = integer<int>(f[8]);
    v.source_h = integer<int>(f[9]);
    std::istringstream input{std::string(f[10])};
    double scale = 0;
    if (!(input >> std::noskipws >> scale) || !input.eof() || !std::isfinite(scale) ||
        !(scale > 0 && scale <= 1) || v.height == 0 || v.width <= v.height || v.source_h < v.height ||
        v.source_w < v.width || v.color > 1 || std::abs(scale - double(v.height) / v.source_h) > 5.01e-7 ||
        std::abs(v.width - v.source_w * (double(v.height) / v.source_h)) > .500001)
        throw std::runtime_error("invalid dimensions/scale or has_color");
    return v;
}
cv::Mat image(const fs::path &dir, const Frame &v) {
    const auto suffix = "_" + std::to_string(v.ns) + ".png";
    std::ostringstream name;
    name << "f_" << std::setw(6) << std::setfill('0') << v.index << suffix;
    auto path = dir / name.str();
    if (!fs::is_regular_file(path))
        path = dir / ("f_" + std::to_string(v.index) + suffix);
    auto img = cv::imread(path.string(), cv::IMREAD_UNCHANGED);
    if (img.empty() || img.size() != cv::Size(v.width, v.height) || img.depth() != CV_8U ||
        (img.channels() != 1 && img.channels() != 3 && img.channels() != 4))
        throw std::runtime_error("missing or invalid frame PNG");
    return img;
}
const char *verdict(luxin::ControlVerdict v) {
    switch (v) {
    case luxin::ControlVerdict::Map:
        return "map";
    case luxin::ControlVerdict::Overlay:
        return "overlay";
    case luxin::ControlVerdict::Unverifiable:
        return "unverifiable";
    }
    throw std::logic_error("invalid control verdict");
}
void pose(const cv::Matx33d &m, int w, int h, bool valid) {
    if (!valid) {
        std::cout << ",,,";
        return;
    }
    // Same as Python transform_center_shift_3120: displacement of the centre, not affine t.
    const double k = 3120. / w, s = m(0, 0);
    std::cout << ',' << s << ',' << k * ((s - 1) * w * .5 + m(0, 2)) << ',' << k * ((s - 1) * h * .5 + m(1, 2));
}
void stripCR(std::string &line) {
    if (!line.empty() && line.back() == '\r')
        line.pop_back();
}
} // namespace

int main(int argc, char **argv) {
    if (argc != 2 && !(argc == 4 && std::string_view(argv[2]) == "--stride")) {
        std::cerr << "usage: bigmap_motion_frames DUMP_DIR [--stride N] > native.csv\n";
        return 2;
    }
    unsigned row = 1;
    try {
        const unsigned stride = argc == 4 ? integer<unsigned>(argv[3]) : 1;
        if (!stride)
            throw std::runtime_error("stride must be positive");
        cv::setNumThreads(1);
        cv::utils::logging::setLogLevel(cv::utils::logging::LOG_LEVEL_SILENT);
        const fs::path dir(argv[1]);
        std::ifstream csv(dir / "frames.csv");
        std::string line;
        if (!std::getline(csv, line))
            throw std::runtime_error("cannot read frames.csv");
        stripCR(line);
        if (line != header)
            throw std::runtime_error("unexpected frames.csv header");
        std::cout
            << "index,timestampNs,width,height,generation,screenMode,dropped_before,epoch,ok,reason,bridge,"
               "step_scale,step_tx_3120,step_ty_3120,cum_scale,cum_tx_3120,cum_ty_3120,"
               "roi_right,roi_inner,overlay_hidden,lk_ms,ransac_ms,update_ms,"
               "right_screen_fixed,right_warped_residual,right_fixed_residual,right_warped_changed_fraction,right_fixed_changed_fraction,"
               "inner_screen_fixed,inner_warped_residual,inner_fixed_residual,inner_warped_changed_fraction,inner_fixed_changed_fraction\n"
            << std::setprecision(17);
        luxin::BigMapMotionSession session;
        Frame prev{};
        unsigned selected = 0;
        while (std::getline(csv, line)) {
            ++row;
            stripCR(line);
            auto v = parse(line);
            if (row > 2 && (v.index <= prev.index || v.ns <= prev.ns || v.generation < prev.generation))
                throw std::runtime_error("non-monotonic index, timestamp or generation");
            if (row > 2 && (v.source_w != prev.source_w || v.source_h != prev.source_h))
                session.reset();
            prev = v;
            if ((row - 2) % stride)
                continue;
            ++selected;
            auto img = image(dir, v);
            luxin::BigMapMotionStats stats;
            const auto start = Clock::now();
            session.update(img, {}, v.ns, v.generation, &stats);
            const double ms = std::chrono::duration<double, std::milli>(Clock::now() - start).count();
            const auto &step = stats.step;
            std::cout << v.index << ',' << v.ns << ',' << v.width << ',' << v.height << ',' << v.generation
                      << ',' << v.mode << ',' << v.dropped << ',' << stats.epoch << ',' << step.ok << ','
                      << step.reason << ',' << stats.bridge;
            const int h = std::min(360, v.height), w = cvRound(v.width * (double(h) / v.height));
            pose(step.work, w, h, step.ok);
            // The epoch origin is defined, but is not a successful measured step.
            pose(stats.chain, v.width, v.height,
                 step.ok || std::string_view(step.reason) == "no_previous_frame");
            std::cout << ',' << verdict(stats.controls[0].verdict) << ',' << verdict(stats.controls[1].verdict)
                      << ',' << stats.overlay_hidden << ',' << step.lk_ms << ',' << step.ransac_ms << ',' << ms;
            for (const auto& c : stats.controls)
                std::cout << ',' << c.screen_fixed << ',' << c.warped_residual << ',' << c.fixed_residual
                          << ',' << c.warped_changed_fraction << ',' << c.fixed_changed_fraction;
            std::cout << '\n';
        }
        if (!csv.eof() || csv.bad() || selected < 2)
            throw std::runtime_error("incomplete burst (need >=2 frames)");
        if (!std::cout.flush())
            throw std::runtime_error("cannot write output");
    } catch (const fs::filesystem_error &) {
        std::cerr << "filesystem error (manifest row " << row << ")\n";
        return 1;
    } catch (const std::runtime_error &e) {
        std::cerr << e.what() << " (manifest row " << row << ")\n";
        return 1;
    } catch (const std::exception &) {
        std::cerr << "replay failed (manifest row " << row << ")\n";
        return 1;
    }
}
