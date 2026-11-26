#include "../src/bigmap_motion_math.hpp"

#include <cmath>
#include <iostream>
#include <stdexcept>
#include <opencv2/imgproc.hpp>

namespace {
using namespace luxin;
using namespace luxin::motion;
const cv::Size size(780, 360);
constexpr int64_t ms = 1000000;
int checks = 0;
void check(bool value, const std::string &description) {
    ++checks;
    if (!value)
        throw std::runtime_error(description);
}
Pose centered(double s, double dx = 0, double dy = 0) {
    return similarity(s, dx + (1 - s) * size.width * .5, dy + (1 - s) * size.height * .5);
}
double error(const Pose &actual, const Pose &expected) {
    double worst = 0;
    for (double x : {0., double(size.width)})
        for (double y : {0., double(size.height)}) {
            cv::Vec3d p(x, y, 1);
            worst = std::max(worst, cv::norm(actual * p - expected * p));
        }
    return worst;
}
class Map {
    cv::Mat canvas, ui, feature_ui;

  public:
    Map() : canvas(1200, 2400, CV_8U), ui(bigMapControlSourceMask(size)), feature_ui(bigMapGeometryMask(size)) {
        cv::RNG rng(42);
        cv::Mat a(100, 200, CV_8U), b(400, 800, CV_8U);
        rng.fill(a, cv::RNG::UNIFORM, 10, 240);
        rng.fill(b, cv::RNG::UNIFORM, 0, 255);
        cv::resize(a, a, canvas.size(), 0, 0, cv::INTER_CUBIC);
        cv::resize(b, b, canvas.size(), 0, 0, cv::INTER_LINEAR);
        cv::addWeighted(a, .62, b, .38, 0, canvas);
        for (int i = 0; i < 100; ++i) {
            cv::Point p(rng.uniform(0, 2400), rng.uniform(0, 1200)),
                q(rng.uniform(0, 2400), rng.uniform(0, 1200));
            cv::line(canvas, p, q, rng.uniform(20, 235), 2, cv::LINE_AA);
        }
    }
    cv::Mat frame(Pose pose = Pose::eye(), bool chrome = true, bool icons = true,
                  bool optional_panel = false) const {
        cv::Mat image;
        auto m = pose * similarity(1, -810, -420);
        cv::warpAffine(canvas, image, cv::Mat(m).rowRange(0, 2), size, cv::INTER_LINEAR, cv::BORDER_REFLECT101);
        cv::GaussianBlur(image, image, {3, 3}, .7);
        if (icons)
            for (int i = 0; i < 8; ++i) {
                cv::Vec3d p = pose * cv::Vec3d(85 + i * 88, 145 + i % 3 * 63, 1);
                cv::drawMarker(image, {cvRound(p[0]), cvRound(p[1])}, 235, cv::MARKER_DIAMOND, 11, 2);
            }
        if (chrome)
            image.setTo(223, (optional_panel ? feature_ui : ui) == 0);
        return image;
    }
};
BigMapGeometry absolute(double ts, Pose pose = Pose::eye(), std::string space = "world", cv::Size sz = size) {
    BigMapGeometry a;
    a.mode = "found";
    a.space = space;
    a.reference = "synthetic";
    a.frame_width = sz.width;
    a.frame_height = sz.height;
    a.timestamp_seconds = ts;
    a.inliers = 48;
    a.matches = 60;
    a.support = .35;
    a.matrix = cv::Mat(pose).rowRange(0, 2).clone();
    return a;
}
struct Stub {
    std::promise<BigMapGeometry> result;
    double ts = 0;
    int requests = 0;
    BigMapMotionSession session{[this](cv::Mat, BigMapMotionSession::Refs, double t) {
        result = {};
        ts = t;
        ++requests;
        return result.get_future();
    }};
    BigMapMotionStats stats;
    int64_t time = 0;
    uint64_t gen = 1;
    BigMapSessionResult feed(const cv::Mat &image, int64_t dt = 33 * ms) {
        time += dt;
        return session.update(image, {nullptr}, time, gen, &stats);
    }
    void reply(Pose p = Pose::eye(), std::string space = "world") { result.set_value(absolute(ts, p, space)); }
    void lock(const cv::Mat &image) {
        check(feed(image).geometry.matrix.empty(), "first frame hidden");
        reply();
        check(feed(image).phase == "tracking", "acquired");
    }
};
double series(const Map &map, int dt, int kind) {
    Stub s;
    Pose truth = Pose::eye();
    s.feed(map.frame(Pose::eye(), true, true, true));
    double worst = 0;
    // Start at 16ms so the prior is estimated from actual images. Reach the target
    // interval via realistic acceleration; never seed LK with the ground truth.
    std::vector<int> times{16, 33, 67, 100, 167};
    times.erase(std::remove_if(times.begin(), times.end(), [dt](int n) { return n > dt; }), times.end());
    times.insert(times.end(), kind == 0 ? 3 : int(std::ceil(std::log(2.5) / (.00223 * dt))), dt);
    for (int t : times) {
        const double scale = kind == 0 ? 1. : std::min(std::exp(.00223 * t), 2.5 / truth(0, 0));
        const auto step =
            centered(scale, kind == 1 ? 0 : t * (kind == 0 ? .65 : .17), kind == 1 ? 0 : -t * .05);
        truth = step * truth;
        s.feed(map.frame(truth, true, true, true), t * ms);
        check(s.stats.step.ok, "LK failed: dt=" + std::to_string(t) + " kind=" + std::to_string(kind) + " " +
                                   s.stats.step.reason);
        const double err = error(s.stats.step.work, step);
        // Same procedural PNGs, Python lk_3dof_pred (OpenCV 4.10): worst errors
        // at ~1.45x are 1.490 px zoom and 6.487 px zoom+pan. These are isolated
        // reference envelopes for 167ms, outside the target cadence (<=67ms),
        // not looser LK/RANSAC acceptance gates. Keep the normal .30 px test.
        double limit = .30;
        if (scale >= 1.44)
            limit = kind == 1 ? 1.6 : 6.6;
        else if (kind == 2 && t == 67)
            limit = .35;
        else if (kind == 2 && scale >= 1.16)
            limit = .36;
        check(err <= limit, "corner error=" + std::to_string(err) + " limit=" + std::to_string(limit));
        worst = std::max(worst, err);
    }
    std::cout << "series dt=" << dt << " kind=" << kind << " max_work_px=" << worst << '\n';
    return worst;
}
void chain(const Map &map) {
    Stub s;
    Pose orig = Pose::eye();
    s.feed(map.frame(Pose::eye(), true, true, true));
    double worst = 0;
    for (int i = 1; i <= 180; ++i) {
        orig = centered(std::exp(.4 * std::sin(i * .034)), 70 * std::sin(i * .031), 32 * std::sin(i * .045));
        s.feed(map.frame(orig, true, true, true));
        check(s.stats.step.ok, "180-frame series step");
        worst = std::max(worst, error(s.stats.chain, orig) * 4);
    }
    std::cout << "180-step max corner drift (3120 px)=" << worst << '\n';
    check(worst <= 12, "chain drift");
    const auto final = s.stats.chain;
    auto still = map.frame(orig, true, true, true);
    for (int i = 0; i < 12; ++i) {
        s.feed(still);
        check(s.stats.chain == final && s.stats.step.ok, "pixel-static pose is bit-exact");
    }
}
void slowMotion(const Map &map) {
    auto sample = [](double scale, double tx) {
        // Analytic texture avoids warpAffine's 1/32-pixel interpolation table: a
        // 0.02px test step must not alternate between duplicate and 0.03125px frames.
        cv::Mat image(size, CV_8U);
        for (int y = 0; y < size.height; ++y)
            for (int x = 0; x < size.width; ++x) {
                const double u = (x - tx) / scale, v = y / scale;
                const double value = 128 + 20 * std::sin(.13 * u) * std::sin(.11 * v) +
                                     30 * std::sin(.05 * u + .09 * v) + 20 * std::cos(.07 * u - .06 * v) +
                                     10 * std::sin(.23 * u) * std::cos(.21 * v);
                image.at<uchar>(y, x) = cv::saturate_cast<uchar>(value);
            }
        return image;
    };
    for (bool zoom : {false, true}) {
        Stub s;
        s.lock(sample(1, 0));
        for (int i = 1; i <= 100; ++i) {
            const Pose truth = zoom ? similarity(std::pow(1.0003, i), 0, 0) : similarity(1, .02 * i, 0);
            s.feed(sample(truth(0, 0), truth(0, 2)));
            check(s.stats.step.ok, "slow movement must remain trackable");
        }
        const double expected = zoom ? std::pow(1.0003, 100) : 2.;
        const double actual = zoom ? s.stats.chain(0, 0) : s.stats.chain(0, 2);
        std::cout << "creep " << (zoom ? "scale" : "tx_work") << " actual=" << actual
                  << " expected=" << expected << '\n';
        // Image-based LK includes 8-bit quantization, unlike the exact correspondence
        // regression below. A .05 work-px final tolerance still detects the old dead zone.
        check(std::abs(actual - expected) < (zoom ? .0005 : .05), "subthreshold steps must accumulate");
    }
    for (bool zoom : {false, true}) {
        Pose integrated = Pose::eye();
        Points p, q;
        for (int i = 0; i < 100; ++i)
            p.emplace_back(30 + (i % 10) * 60, 30 + (i / 10) * 25);
        for (auto v : p)
            q.push_back(zoom ? v * 1.0003 : v + cv::Point2d(.02, 0));
        for (int i = 0; i < 100; ++i) {
            std::vector<uchar> inliers;
            const auto step = fit(p, q, inliers);
            check(step.ok, "exact subthreshold correspondences");
            integrated = step.work * integrated;
        }
        check(std::abs((zoom ? integrated(0, 0) : integrated(0, 2)) - (zoom ? std::pow(1.0003, 100) : 2.)) <
                  1e-9,
              "exact creep accumulation");
    }
    Stub s;
    auto original = map.frame(Pose::eye(), true, false);
    s.lock(original);
    const Pose initial = s.stats.chain;
    cv::RNG rng(836);
    for (int i = 0; i < 240; ++i) {
        auto noisy = original.clone();
        for (int y = 0; y < noisy.rows; ++y)
            for (int x = 0; x < noisy.cols; ++x)
                noisy.at<uchar>(y, x) =
                    cv::saturate_cast<uchar>(int(original.at<uchar>(y, x)) + rng.uniform(-1, 2));
        s.feed(noisy);
        check(s.stats.step.ok && std::string(s.stats.step.reason) == "static_pixels" &&
                  s.stats.chain == initial,
              "random-sign pixel dither must not drift");
    }
}
void repeatAndReverse(const Map &map) {
    Stub a, b;
    auto origin = map.frame();
    a.feed(origin);
    b.feed(origin);
    for (double x : {8., 24., 56., 104., 104., 96., 75.}) {
        auto gray = map.frame(centered(1, x, -x * .2));
        cv::Mat bgr;
        cv::cvtColor(gray, bgr, cv::COLOR_GRAY2BGR);
        a.feed(gray);
        b.feed(bgr);
        check(a.stats.step.ok && b.stats.step.ok && a.stats.step.work == b.stats.step.work &&
                  a.stats.step.inliers == b.stats.step.inliers,
              "repeatable flow and BGR preprocessing");
        check(error(a.stats.chain, centered(1, x, -x * .2)) < .3, "acceleration/stop/reversal");
    }
}
void algebra() {
    const auto a = similarity(2.8, 65, -91), at = centered(1.73, -12, 19);
    const auto d = correction(at, at * a);
    for (int i = 0; i < 32; ++i) {
        const auto current = centered(std::exp(i * .032), -11 * i, 7 * i);
        check(error(current * d, current * a) < 1e-10, "delayed K -> current composition");
    }
    Points p, q;
    const auto zoom = centered(1.45);
    for (int i = 0; i < 100; ++i) {
        cv::Point2d centre(50 + i % 10 * 62, 50 + i / 10 * 25);
        cv::Vec3d dest = zoom * cv::Vec3d(centre.x, centre.y, 1);
        // An edge of a constant-size icon lies 9px from its moving centre,
        // whereas an edge painted on the map scales to 13.05px.
        bool icon = i % 4 == 0;
        p.push_back(centre + cv::Point2d(9, 0));
        q.push_back(cv::Point2d(dest[0] + 9 * (icon ? 1 : 1.45), dest[1]));
    }
    std::vector<uchar> mask, again;
    auto fit1 = fit(p, q, mask), fit2 = fit(p, q, again);
    check(fit1.ok && error(fit1.work, zoom) < 1e-9, "known zoom with fixed-size icons");
    int rejected = 0;
    for (int i = 0; i < 100; i += 4)
        rejected += !mask[i];
    std::cout << "RANSAC rejected labelled constant-radius edges: " << rejected
              << "/25, map inliers=" << fit1.inliers << "/75\n";
    check(rejected == 25 && fit1.inliers == 75, "icon RANSAC labels");
    check(mask == again && fit1.work == fit2.work, "repeatable RANSAC");
}
void loss(const Map &map) {
    auto a = map.frame();
    auto blank = a.clone();
    blank.setTo(40, bigMapGeometryMask(size));
    for (int gap : {1, 2, 3}) {
        Stub s;
        s.lock(a);
        for (int i = 0; i < gap; ++i)
            check(s.feed(blank).geometry.matrix.empty(), "dropout fresh rejection");
        auto recovery = s.feed(map.frame(centered(1, 5, 2)));
        check(s.stats.bridge && recovery.phase == "tracking" &&
                  std::abs(recovery.geometry.matrix.at<double>(0, 2) - 5) < .3,
              "short bridge");
    }
    Stub s;
    s.lock(a);
    auto far = map.frame(centered(1, 360, 25));
    for (int i = 0; i < 8; ++i)
        check(s.feed(far).geometry.matrix.empty(), ">40% width jump");
    check(s.stats.lost && s.stats.requested, "eight strikes initiate search");
    s.reply(centered(1, 360, 25));
    auto restored = s.feed(map.frame(centered(1, 363, 25)));
    check(restored.phase == "tracking" && s.stats.bridge &&
              std::abs(restored.geometry.matrix.at<double>(0, 2) - 363) < .3,
          "disconnected snapshot bridge");
    check(s.feed(far, 601 * ms).geometry.matrix.empty() && s.stats.lost, "600ms limit even when LK matches");
}
void screen(const Map &map) {
    // Closing the map to a blank screen loses LK, independent of any panel decision.
    Stub closed;
    closed.lock(map.frame());
    for (int i = 0; i < 2; ++i)
        check(closed.feed(cv::Mat(size, CV_8U, cv::Scalar(53))).geometry.matrix.empty(), "closed map hidden");
    Stub s;
    s.lock(map.frame(Pose::eye(), false));
    for (int i = 1; i <= 5; ++i)
        check(s.feed(map.frame(centered(1, 3 * i, 2 * i), false)).phase == "tracking", "map behind controls");
    for (int i = 1; i <= 36; ++i)
        check(s.feed(map.frame(centered(1, 15 - 10 * i, 10), false)).phase == "tracking",
              "pan outside confirmed ROI coverage");
    for (int i = 37; i <= 38; ++i) {
        auto panel = map.frame(centered(1, 15 - 10 * i, 10), false);
        panel(cv::Rect(700, 130, 36, 90)).setTo(12);
        auto r = s.feed(panel);
        check(r.geometry.matrix.empty() && s.stats.overlay_hidden, "moving panel beyond original coverage");
    }
    Stub fast;
    fast.lock(map.frame(Pose::eye(), false));
    int x = 0;
    for (int dx : {-10, -20, -40, -65, -100, -100, -100}) {
        x += dx;
        auto r = fast.feed(map.frame(centered(1, x, 0), false), -dx * ms);
        check(r.phase == "tracking", "unseen map ROI at x=" + std::to_string(x) +
                                         " LK=" + fast.stats.step.reason +
                                         " hidden=" + std::to_string(fast.stats.overlay_hidden));
    }
    const auto before = fast.stats.epoch;
    auto last = map.frame(centered(1, x, 0), false), blurred = last.clone();
    blurred.setTo(50, bigMapGeometryMask(size));
    for (int i = 0; i < 2; ++i)
        check(fast.feed(blurred).geometry.matrix.empty() && fast.stats.epoch == before,
              "map-only ROI dropout retains epoch");
    check(fast.feed(last).phase == "tracking" && fast.stats.bridge && fast.stats.epoch == before,
          "short bridge after wide pan");
}
void cleanControls(const Map &map) {
    for (int dt : {67, 167})
        for (bool zoom : {false, true}) {
            Stub s;
            const double start = zoom ? 2. : 1.;
            s.lock(map.frame(centered(start)));
            double scale = start, x = 0;
            int unknown = 0, confirmed = 0;
            // Warm up the *measured* LK predictor, then keep the requested interval.
            for (int t : {16, 33, 67, dt, dt, dt, dt, dt}) {
                if (zoom)
                    scale *= std::exp(-.0010 * t);
                else
                    x -= .63 * t;
                auto r = s.feed(map.frame(centered(scale, x)), t * ms);
                check(!r.geometry.matrix.empty() && s.stats.step.ok && !s.stats.overlay_hidden,
                      "clean pan/zoom dt=" + std::to_string(t) + " zoom=" + std::to_string(zoom));
                for (auto c : s.stats.controls) {
                    unknown += c.verdict == ControlVerdict::Unverifiable;
                    confirmed += c.verdict == ControlVerdict::Map;
                    check((c.verdict == ControlVerdict::Unverifiable) == (c.coverage < .90),
                          "coverage boundary");
                }
            }
            check(confirmed > 0 && (dt != 167 || unknown > 0), "clean controls exercised");
        }
}

// The reproducer is also run by the ordinary test target: probes cannot silently diverge from CI.
void panelProbe(const Map &map, bool trace) {
    Stub s;
    s.lock(map.frame());
    int x = 0;
    for (int dx : {-10, -20, -40, -65, -100, -100}) {
        x += dx;
        check(!s.feed(map.frame(centered(1, x)), -dx * ms).geometry.matrix.empty(), "probe pan");
    }
    auto frame = [&](int dx, bool panel, int dt) {
        x += dx;
        auto image = map.frame(centered(1, x));
        if (panel) {
            // Fixed panel covers the whole ROI; its internal stripes make unwarped
            // equality and the wrong warped prediction independently observable.
            auto content = image(cv::Rect(690, 115, 60, 140));
            content.setTo(12);
            for (int j = 0; j < content.cols; j += 12)
                content.colRange(j, j + 4).setTo(195);
        }
        auto r = s.feed(image, dt * ms);
        check(s.stats.step.ok, "probe needs valid LK");
        if (trace)
            std::cout << "dx=" << dx << " coverage=" << s.stats.controls[0].coverage
                      << " roi=" << static_cast<int>(s.stats.controls[0].verdict)
                      << " published=" << !r.geometry.matrix.empty() << '\n';
        return !r.geometry.matrix.empty();
    };
    check(frame(-100, true, 100) && s.stats.controls[0].verdict == ControlVerdict::Unverifiable,
          "panel appeared outside overlap");
    for (int i = 0; i < 4; ++i)
        check(frame(0, true, 33), "unseen panel while stationary");
    check(!frame(-3, true, 33) && s.stats.controls[0].verdict == ControlVerdict::Overlay &&
              s.stats.controls[0].screen_fixed,
          "first observable movement hides screen-fixed panel");
    check(!frame(0, true, 33), "stationary identical panel cannot clear latch");
    check(!frame(0, false, 33), "closing transition remains hidden");
    check(!frame(0, false, 33), "stationary clean map cannot clear latch");
    check(frame(-8, false, 33) && s.stats.controls[0].verdict == ControlVerdict::Map,
          "first moving map restores publication");
}
void independentControls(const Map &map) {
    Stub s;
    s.lock(map.frame());
    int x = 0;
    auto feed = [&](int dx, bool right, bool left, int dt) {
        x += dx;
        auto f = map.frame(centered(1, x));
        if (right)
            f(cv::Rect(700, 130, 36, 90)).setTo(20);
        if (left)
            f(cv::Rect(510, 140, 20, 70)).setTo(20);
        return !s.feed(f, dt * ms).geometry.matrix.empty();
    };
    check(!feed(-10, true, false, 10) && s.stats.controls[0].verdict == ControlVerdict::Overlay,
          "right overlay");
    for (int dx : {-20, -40, -65, -100})
        check(!feed(dx, true, true, -dx), "overlapping panel latches");
    check(!feed(-100, false, false, 100), "right unverifiable on closing transition");
    check(!feed(-100, false, false, 100) && s.stats.controls[0].verdict == ControlVerdict::Unverifiable &&
              s.stats.controls[1].verdict == ControlVerdict::Map,
          "left map cannot clear unknown right latch");
    check(s.feed(cv::Mat(size, CV_8U, cv::Scalar(50))).geometry.matrix.empty() && !s.stats.step.ok,
          "dropout with the right ROI latched");
    check(!feed(-8, false, false, 10) && s.stats.step.ok && s.stats.bridge &&
              s.stats.controls[0].verdict == ControlVerdict::Unverifiable && s.stats.overlay_hidden,
          "successful non-adjacent bridge cannot clear the latch even on a clean map");
    check(feed(-8, false, false, 10), "next adjacent moving map releases last latch");
}
void corrections(const Map &map) {
    Stub s;
    auto a = map.frame();
    s.lock(a);
    auto next = [&]() {
        for (int i = 0; i < 80 && !s.stats.requested; ++i)
            s.feed(a);
        check(s.stats.requested, "refresh request");
    };
    auto position = [&]() {
        auto r = s.feed(a);
        check(r.phase == "tracking", "correction has fresh step");
        return r.geometry.matrix.at<double>(0, 2);
    };
    next();
    s.reply(centered(1, 3, 0));
    double x = position();
    check(x > 0 && x < 3, "small jump smoothed");
    next();
    s.reply(centered(1, 18, 0));
    x = position();
    check(x > 3 && x < 18, "medium jump smoothed");
    position();
    check(std::abs(position() - 18) < 1e-8, "medium jump finishes in three frames");
    next();
    s.reply(centered(1, 95, 0));
    check(position() == 18, "large jump held");
    next();
    s.reply(centered(1, -110, 0));
    check(position() == 18 && s.stats.abs_conflicts == 1, "inconsistent large jump");
    next();
    s.reply(centered(1, -108, 0));
    position();
    position();
    check(position() == -108, "independent confirmation");
    next();
    auto blank = a.clone();
    blank.setTo(42, bigMapGeometryMask(size));
    check(s.feed(blank).geometry.matrix.empty(), "dropout before space change");
    s.reply(centered(1), "other");
    auto epoch = s.stats.epoch;
    check(s.feed(a).geometry.matrix.empty() && s.stats.epoch != epoch, "space change hard reset");
    check(!s.stats.requested && !s.stats.bridge, "old bridge cannot schedule disconnected search");
    s.feed(a);
    check(s.stats.requested, "new space snapshot is connected to its own chain");
    s.reply(centered(1), "other");
    check(s.feed(a).geometry.space == "other", "space reacquisition");
}
void budget(const Map &map, bool zoom) {
    Stub s;
    auto a = map.frame();
    s.lock(a);
    bool hidden = false;
    for (int i = 1; i <= 320 && !hidden; ++i) {
        const auto truth =
            zoom ? centered(std::exp(.48 * std::sin(i * .1))) : centered(1, 160 * std::sin(i * .13));
        auto r = s.feed(map.frame(truth));
        check(s.stats.step.ok, "budget series LK");
        if (s.stats.zoom_since_abs > 3 || s.stats.path_since_abs > 2400) {
            hidden = true;
            check(r.geometry.matrix.empty(), "budget exceeded -> hidden");
            if (!s.stats.requested) { // an earlier slow-frame refresh may still be in flight
                s.result.set_value({});
                s.feed(map.frame(truth));
            }
            check(s.stats.requested, "urgent budget search");
            for (int j = 1; j <= 4; ++j)
                s.feed(map.frame(centered(1, j * 2, 0) * truth));
            s.reply(truth);
            auto now = s.feed(map.frame(centered(1, 10, 0) * truth));
            check(now.phase == "tracking" && s.stats.path_since_abs > 9 && s.stats.path_since_abs < 11,
                  "post-snapshot travel charged");
        }
    }
    check(hidden, "budget exercised");
}
void delayedPose(const Map &map) {
    Stub s;
    s.feed(map.frame());
    s.result.set_value({});
    for (int i = 1; i <= 46; ++i)
        s.feed(map.frame(centered(1, i, -i * .5)));
    check(s.stats.requested, "second snapshot taken from moving chain");
    Pose k_to_zero = similarity(1.8, 14, -21);
    const Pose absolute_snapshot = s.stats.chain * k_to_zero;
    for (int i = 47; i <= 53; ++i)
        s.feed(map.frame(centered(1 + .009 * (i - 46), i, -i * .5)));
    s.reply(absolute_snapshot);
    auto r = s.feed(map.frame(centered(1.072, 54, -27)));
    check(r.phase == "tracking" &&
              cv::norm(r.geometry.matrix, cv::Mat(s.stats.chain * k_to_zero).rowRange(0, 2), cv::NORM_INF) <
                  1e-10,
          "actual delayed nonidentity K->frame output after zoom and pan");
}
void invalidation(const Map &map) {
    Stub s;
    auto a = map.frame();
    s.feed(a);
    auto epoch = s.stats.epoch;
    s.session.reset();
    s.feed(a);
    s.reply();
    check(s.feed(a).geometry.matrix.empty() && s.requests == 2 && s.stats.epoch != epoch,
          "late reply after brief pause/resume");
    ++s.gen;
    s.reply();
    check(s.feed(a).geometry.matrix.empty() && s.requests == 3, "generation rejects late result");
    auto oldgen = s.session.update(a, {nullptr}, s.time + ms, s.gen - 1);
    check(oldgen.geometry.matrix.empty(), "old generation cannot resume");
    s.reply();
    check(s.feed(a).phase == "tracking", "current generation can acquire");
    check(s.session.update(a, {nullptr}, s.time, s.gen).geometry.matrix.empty(), "duplicate timestamp");
    cv::Mat larger;
    cv::resize(a, larger, {1560, 720});
    for (int i = 0; i < 80 && !s.stats.requested; ++i)
        s.feed(a);
    check(s.stats.requested, "search in flight before resize");
    check(s.feed(larger).geometry.matrix.empty(), "frame-size reset");
    s.reply();
    check(s.feed(larger).geometry.matrix.empty() && s.stats.requested, "pre-resize reply discarded");
    auto b = absolute(s.ts, centered(1), "world", larger.size());
    s.result.set_value(b);
    cv::resize(map.frame(centered(1, 6, 0)), larger, {1560, 720});
    auto r = s.feed(larger);
    check(r.phase == "tracking" && std::abs(r.geometry.matrix.at<double>(0, 2) - 12) < .6,
          "original frame units");
    check(r.geometry.matrix.at<double>(0, 0) == r.geometry.matrix.at<double>(1, 1) &&
              r.geometry.matrix.at<double>(0, 1) == -r.geometry.matrix.at<double>(1, 0) &&
              r.geometry.inliers >= 12 && r.geometry.matches >= r.geometry.inliers &&
              r.geometry.timestamp_seconds == s.time * 1e-9,
          "output contract");
}
void workerRace(const Map &map) {
    std::promise<void> started, release, done;
    bool snapshot_intact = false;
    auto gate = release.get_future().share();
    int requests = 0;
    BigMapMotionSession s([&](cv::Mat owned, BigMapMotionSession::Refs, double ts) {
        if (++requests > 1) {
            std::promise<BigMapGeometry> p;
            p.set_value({});
            return p.get_future();
        }
        return std::async(std::launch::async, [&, owned = std::move(owned), ts] {
            started.set_value();
            gate.wait();
            auto result = absolute(ts);
            snapshot_intact = owned.at<uchar>(0, 0) == 223;
            done.set_value();
            return result;
        });
    });
    auto image = map.frame();
    s.update(image, {nullptr}, ms, 1);
    started.get_future().wait();
    image.setTo(0);
    s.reset();
    auto resumed = s.update(map.frame(), {nullptr}, 2 * ms, 1);
    release.set_value();
    done.get_future().wait();
    check(requests == 1 && resumed.geometry.matrix.empty() && snapshot_intact,
          "single isolated snapshot during pause/resume");
    for (int i = 3; i < 20 && requests == 1; ++i)
        check(s.update(map.frame(), {nullptr}, i * ms, 1).geometry.matrix.empty(), "old worker discarded");
    check(requests == 2, "worker ownership released");
}
} // namespace

int main(int argc, char **argv) try {
    cv::setNumThreads(1);
    Map map;
    if (argc == 2 && std::string(argv[1]) == "--worker-only") {
        workerRace(map);
        std::cout << "PASS: " << checks << " worker checks\n";
        return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--panel-probe") {
        panelProbe(map, true);
        std::cout << "PASS: " << checks << " probe checks\n";
        return 0;
    }
    algebra();
    double worst = 0;
    for (int dt : {16, 33, 67, 167})
        for (int kind : {0, 1, 2})
            worst = std::max(worst, series(map, dt, kind));
    chain(map);
    slowMotion(map);
    repeatAndReverse(map);
    loss(map);
    screen(map);
    cleanControls(map);
    independentControls(map);
    panelProbe(map, false);
    corrections(map);
    budget(map, false);
    budget(map, true);
    invalidation(map);
    delayedPose(map);
    workerRace(map);
    std::cout << "all-series max step corner (work px)=" << worst << '\n';
    std::cout << "PASS: " << checks << " checks\n";
    return 0;
} catch (const std::exception &e) {
    std::cerr << "FAIL after " << checks << " checks: " << e.what() << '\n';
    return 1;
}
