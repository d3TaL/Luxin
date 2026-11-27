#include "bigmap_motion_math.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <deque>
#include <optional>
#include <random>
#include <opencv2/imgproc.hpp>
#include <opencv2/video/tracking.hpp>
#ifdef __linux__
#include <sys/resource.h>
#endif

namespace luxin::motion {
using Clock = std::chrono::steady_clock;
constexpr int kMin = 12;
double elapsed(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
Pose similarity(double s, double tx, double ty) {
    return {s, 0, tx, 0, s, ty, 0, 0, 1};
}
cv::Point2d project(const Pose &m, cv::Point2d p) {
    return {m(0, 0) * p.x + m(0, 1) * p.y + m(0, 2), m(1, 0) * p.x + m(1, 1) * p.y + m(1, 2)};
}
cv::Point2d shift(const Pose &m, cv::Size size) {
    cv::Point2d c(size.width * .5, size.height * .5);
    return project(m, c) - c;
}
Pose predict(const Pose &previous, double dt_ratio, cv::Size size) {
    const double a = std::clamp(dt_ratio, .25, 2.5), s = std::pow(previous(0, 0), a);
    auto d = a * shift(previous, size);
    return similarity(s, d.x - (s - 1) * size.width * .5, d.y - (s - 1) * size.height * .5);
}
Pose correction(const Pose &snapshot_chain, const Pose &absolute) {
    return snapshot_chain.inv() * absolute;
}

BigMapMotionStep fit(const Points &p, const Points &q, std::vector<uchar> &mask) {
    BigMapMotionStep r;
    const int n = r.matches = static_cast<int>(p.size());
    mask.assign(n, 0);
    r.reason = "insufficient_fb_matches";
    if (n < kMin || q.size() != p.size())
        return r;
    std::mt19937 rng(0);
    std::uniform_int_distribution<int> first(0, n - 1), offset(1, n - 1);
    double best_cost = 0;
    int best = 0;
    std::vector<uchar> candidate(n);
    auto score = [&](double s, cv::Point2d t, std::vector<uchar> &flags, double &cost, double *sum) {
        int count = 0;
        cost = 0;
        if (sum)
            *sum = 0;
        for (int k = 0; k < n; ++k) {
            const auto residual = s * p[k] + t - q[k];
            const double d = residual.dot(residual);
            if ((flags[k] = (d <= 4.0))) {
                ++count;
                cost += d;
                if (sum)
                    *sum += std::sqrt(d);
            }
        }
        return count;
    };
    r.reason = "insufficient_inliers";
    for (int iter = 0; iter < 300; ++iter) {
        const int i = first(rng), j = (i + offset(rng)) % n;
        const auto dp = p[j] - p[i];
        const double denom = dp.dot(dp);
        if (denom < 25)
            continue;
        const double s = dp.dot(q[j] - q[i]) / denom;
        if (!(s >= .5 && s <= 2))
            continue;
        const auto t = .5 * (q[i] + q[j] - s * (p[i] + p[j]));
        double cost;
        const int count = score(s, t, candidate, cost, nullptr);
        if (count > best || (count == best && cost < best_cost)) {
            mask.swap(candidate);
            best = count;
            best_cost = cost;
        }
    }
    for (int pass = 0; pass < 2; ++pass) {
        if (best < kMin)
            return r;
        cv::Point2d pm{}, qm{};
        for (int i = 0; i < n; ++i)
            if (mask[i]) {
                pm += p[i];
                qm += q[i];
            }
        pm *= 1. / best;
        qm *= 1. / best;
        double numerator = 0, denom = 0;
        for (int i = 0; i < n; ++i)
            if (mask[i]) {
                const auto dp = p[i] - pm;
                numerator += dp.dot(q[i] - qm);
                denom += dp.dot(dp);
            }
        if (denom <= 1e-9) {
            r.reason = "degenerate_inliers";
            return r;
        }
        const double s = numerator / denom;
        if (!(s >= .5 && s <= 2)) {
            r.reason = "scale_bounds";
            return r;
        }
        const auto t = qm - s * pm;
        double cost, sum;
        best = score(s, t, mask, cost, &sum);
        r.inliers = best;
        r.error_px = best ? sum / best : 0;
        r.work = similarity(s, t.x, t.y);
    }
    if (best < kMin)
        return r;
    if (best < .35 * n) {
        r.reason = "inlier_ratio";
        return r;
    }
    if (!(r.error_px <= 2)) {
        r.reason = "reprojection_error";
        return r;
    }
    r.ok = true;
    r.reason = "ok";
    return r;
}

void requireFixedAdvantage(MotionControlObservation &c, bool moving) {
    constexpr double kFixedHypothesisRatio = .5;
    if (moving && c.verdict == ControlVerdict::Overlay &&
        !(c.screen_fixed && c.fixed_residual <= kFixedHypothesisRatio * c.warped_residual &&
          c.fixed_changed_fraction <= kFixedHypothesisRatio * c.warped_changed_fraction))
        c.verdict = ControlVerdict::Unverifiable;
}

namespace {
cv::Mat gray360(const cv::Mat &frame) {
    cv::Mat gray, small;
    if (frame.channels() == 1)
        gray = frame;
    else
        cv::cvtColor(frame, gray, frame.channels() == 3 ? cv::COLOR_BGR2GRAY : cv::COLOR_BGRA2GRAY);
    if (gray.rows <= 360)
        return gray.clone();
    cv::resize(gray, small, {cvRound(gray.cols * (360. / gray.rows)), 360}, 0, 0, cv::INTER_AREA);
    return small;
}
struct Masks {
    cv::Mat ui, corners, control_source;
    void prepare(cv::Size size) {
        if (ui.size() == size)
            return;
        ui = bigMapGeometryMask(size);
        control_source = bigMapControlSourceMask(size);
        cv::erode(ui, corners, cv::getStructuringElement(cv::MORPH_RECT, {23, 23}), {-1, -1}, 1,
                  cv::BORDER_CONSTANT, cv::Scalar(0));
        if (cv::countNonZero(corners) < 64)
            corners = ui.clone();
    }
};
// Pixel evidence only, never a threshold on the fitted LK transform. Exact repeats and
// bounded, uncorrelated 8-bit dither are static; coherent subpixel motion must accumulate.
// Symmetric gradients avoid correlating a noisy previous frame with its own residual.
bool staticPixels(const cv::Mat &prev, const cv::Mat &now, const cv::Mat &mask) {
    constexpr int kNoiseDelta = 2; // independent +/-1 gray-level dither in each frame
    constexpr double kNoiseSigma = 6.;
    double energy = 0;
    cv::Vec3d dot{}, gradient_energy{};
    int count = 0;
    for (int y = 1; y < prev.rows - 1; ++y) {
        for (int x = 1; x < prev.cols - 1; ++x) {
            if (!mask.at<uchar>(y, x))
                continue;
            const double delta = int(now.at<uchar>(y, x)) - int(prev.at<uchar>(y, x));
            if (std::abs(delta) > kNoiseDelta)
                return false;
            auto average = [&](int row, int col) {
                return .5 * (int(now.at<uchar>(row, col)) + int(prev.at<uchar>(row, col)));
            };
            const double gx = average(y, x + 1) - average(y, x - 1);
            const double gy = average(y + 1, x) - average(y - 1, x);
            const cv::Vec3d g(gx, gy, gx * (x - prev.cols * .5) + gy * (y - prev.rows * .5));
            for (int k = 0; k < 3; ++k) {
                dot[k] += delta * g[k];
                gradient_energy[k] += g[k] * g[k];
            }
            energy += delta * delta;
            ++count;
        }
    }
    if (count < 64)
        return false;
    for (int k = 0; k < 3; ++k)
        if (dot[k] * dot[k] > kNoiseSigma * kNoiseSigma * (energy / count) * gradient_energy[k])
            return false;
    return true;
}
BigMapMotionStep track(const cv::Mat &prev, const cv::Mat &now, const Masks &masks,
                       const std::optional<Pose> &prediction, bool debug_points = false) {
    auto start = Clock::now();
    std::vector<cv::Point2f> points;
    cv::goodFeaturesToTrack(prev, points, 250, .01, std::max(6, prev.rows / 36), masks.corners, 7);
    auto attempt = [&](const Pose *pred) {
        BigMapMotionStep r;
        r.reason = "insufficient_corners";
        if (points.size() < kMin)
            return r;
        std::vector<cv::Point2f> forward, back;
        std::vector<uchar> valid, reverse;
        auto flow = [&](const cv::Mat &a, const cv::Mat &b, const auto &from, auto &to, auto &status,
                        const Pose *init) {
            if (init)
                for (auto p : from)
                    to.push_back(project(*init, p));
            cv::calcOpticalFlowPyrLK(a, b, from, to, status, cv::noArray(), {21, 21},
                                     !init && a.rows >= 540 ? 3 : 2,
                                     {cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 30, .01},
                                     init ? cv::OPTFLOW_USE_INITIAL_FLOW : 0);
        };
        flow(prev, now, points, forward, valid, pred);
        Pose inverse;
        if (pred)
            inverse = pred->inv();
        flow(now, prev, forward, back, reverse, pred ? &inverse : nullptr);
        Points p, q;
        for (size_t i = 0; i < points.size(); ++i) {
            if (!valid[i] || !reverse[i] || !(cv::norm(points[i] - back[i]) <= 1.5))
                continue;
            cv::Point pixel = forward[i];
            if (cv::Rect({}, now.size()).contains(pixel) && masks.ui.at<uchar>(pixel)) {
                p.push_back(points[i]);
                q.push_back(forward[i]);
            }
        }
        auto begin_fit = Clock::now();
        std::vector<uchar> inliers;
        r = fit(p, q, inliers);
        if (debug_points && r.ok)
            for (size_t i = 0; i < q.size() && r.debug_points.size() < 48; ++i)
                if (inliers[i] && q[i].x >= 0 && q[i].y >= 0 && q[i].x < now.cols && q[i].y < now.rows)
                    r.debug_points.push_back(q[i]);
        r.ransac_ms = elapsed(begin_fit);
        return r;
    };
    auto r = attempt(prediction ? &*prediction : nullptr);
    if (!r.ok && prediction) {
        const double first_fit = r.ransac_ms;
        r = attempt(nullptr);
        r.ransac_ms += first_fit;
    }
    r.corners = static_cast<int>(points.size());
    if (r.ok && staticPixels(prev, now, masks.corners)) {
        r.work = Pose::eye();
        r.reason = "static_pixels";
    }
    r.lk_ms = elapsed(start) - r.ransac_ms;
    return r;
}

double maxTravel(const Pose &m, cv::Size size) {
    double maximum = 0;
    for (double x : {0., double(size.width)})
        for (double y : {0., double(size.height)}) {
            cv::Point2d p(x, y);
            maximum = std::max(maximum, cv::norm(project(m, p) - p));
        }
    return maximum;
}
constexpr double kControlCoverage = .90, kControlMotionPx = 2.;
const std::array<cv::Rect, 2> kControls = {cv::Rect(2790, 500, 170, 450), cv::Rect(2020, 540, 120, 350)};

MotionControlObservation observeControl(cv::Rect base, const cv::Mat &prev, const cv::Mat &now,
                                        const cv::Mat &allowed, const Pose &inverse, bool moving) {
    const double sx = now.cols / 3120., sy = now.rows / 1440.;
    cv::Rect box(cvRound(base.x * sx), cvRound(base.y * sy), std::max(1, cvRound(base.width * sx)),
                 std::max(1, cvRound(base.height * sy)));
    box &= cv::Rect({}, now.size());
    MotionControlObservation result;
    if (box.empty())
        return result;
    int count = 0, changed = 0, fixed_changed = 0;
    double sum = 0, fixed_sum = 0;
    for (int y = box.y; y < box.br().y; ++y) {
        for (int x = box.x; x < box.br().x; ++x) {
            // Test the actual bilinear footprint: no extrapolation or UI-masked neighbours.
            const auto p = project(inverse, {double(x), double(y)});
            if (!(p.x >= 0 && p.x <= prev.cols - 1 && p.y >= 0 && p.y <= prev.rows - 1))
                continue;
            const int x0 = cvFloor(p.x), x1 = cvCeil(p.x), y0 = cvFloor(p.y), y1 = cvCeil(p.y);
            if (!(allowed.at<uchar>(y0, x0) && allowed.at<uchar>(y0, x1) && allowed.at<uchar>(y1, x0) &&
                  allowed.at<uchar>(y1, x1)))
                continue;
            const double dx = p.x - x0, dy = p.y - y0;
            const auto sample = [&](int row) {
                return (1 - dx) * prev.at<uchar>(row, x0) + dx * prev.at<uchar>(row, x1);
            };
            const double current = now.at<uchar>(y, x);
            const double d = std::abs(current - ((1 - dy) * sample(y0) + dy * sample(y1)));
            const double fixed = std::abs(current - prev.at<uchar>(y, x));
            ++count;
            sum += d;
            changed += d > 24.;
            fixed_sum += fixed;
            fixed_changed += fixed > 24.;
        }
    }
    result.coverage = double(count) / box.area();
    if (count) {
        result.warped_residual = sum / count;
        result.fixed_residual = fixed_sum / count;
        result.warped_changed_fraction = double(changed) / count;
        result.fixed_changed_fraction = double(fixed_changed) / count;
    }
    if (result.coverage < kControlCoverage)
        return result;
    auto matches = [&](double total, int different) {
        return total <= count * (moving ? 12. : 6.) && different <= .04 * count;
    };
    result.verdict = matches(sum, changed) ? ControlVerdict::Map : ControlVerdict::Overlay;
    result.screen_fixed =
        moving && result.verdict == ControlVerdict::Overlay && matches(fixed_sum, fixed_changed);
    // A poor map warp alone is not evidence of a screen-fixed panel (LOD/text redraw
    // and scale resampling also cause outliers). Both error measures must favour fixed UI.
    requireFixedAdvantage(result, moving);
    return result;
}
bool validSimilarity(const Pose &p) {
    const double scale = std::hypot(p(0, 0), p(1, 0));
    return cv::checkRange(cv::Mat(p)) && std::isfinite(scale * scale) && scale > 1e-8 &&
           std::abs(p(0, 0) - p(1, 1)) <= 1e-6 * scale && std::abs(p(1, 0) + p(0, 1)) <= 1e-6 * scale;
}
double scaleOf(const Pose &p) {
    return std::hypot(p(0, 0), p(1, 0));
}
Pose interpolate(const Pose &a, const Pose &b, double f) {
    if (f >= 1)
        return b;
    double s = scaleOf(a) * std::pow(scaleOf(b) / scaleOf(a), f);
    double angle = std::atan2(a(1, 0), a(0, 0));
    angle += f * std::remainder(std::atan2(b(1, 0), b(0, 0)) - angle, 2 * CV_PI);
    const double c = s * std::cos(angle), d = s * std::sin(angle);
    return {c, -d, a(0, 2) + f * (b(0, 2) - a(0, 2)), d, c, a(1, 2) + f * (b(1, 2) - a(1, 2)), 0, 0, 1};
}
// Conjugating by the work/frame resize preserves uniform scale even for a rounded width.
Pose changeUnits(Pose p, double x, double y) {
    p(0, 2) *= x;
    p(1, 2) *= y;
    return p;
}
constexpr int64_t ms = 1000000;
constexpr int64_t kBridge = 600 * ms, kRetain = 3000 * ms, kHistory = 4000 * ms;
constexpr int kFailures = 8;
constexpr int64_t kRefresh = 2000 * ms, kRetry = 1500 * ms, kIndependent = 1000 * ms;
constexpr double kSlow = 1, kPathBudget = 2400, kZoomBudget = 3;
constexpr double kSmall = 15, kLarge = 120, kScaleJump = .05, kAgree = 20, kTau = .15;
constexpr double kAlpha = .6, kBeta = .2;
struct Sample {
    int64_t ts;
    Pose chain;
    double path, zoom;
};
struct Snapshot {
    uint64_t epoch;
    int64_t ts;
    cv::Mat gray;
    bool connected;
};
struct Suspicion {
    int64_t ts;
    Pose pose;
};
struct Epoch {
    cv::Size size;
    cv::Mat good;
    Masks masks;
    Pose chain = Pose::eye(), previous = Pose::eye();
    int64_t good_ts = -1, previous_dt = 0, request_ts = -1, abs_ts = -1, lost_ts = -1;
    int failures = 0, blend_frames = 0;
    bool urgent = false;
    bool budget_was_exceeded = false;
    double path = 0, zoom = 0, abs_path = 0, abs_zoom = 0;
    unsigned conflicts = 0;
    std::optional<Pose> applied, target;
    std::optional<Suspicion> suspect;
    BigMapGeometry identity;
    cv::Vec3d measurement{}, filtered{}, velocity{};
    std::deque<Sample> history;
    bool overBudget() const { return path - abs_path > kPathBudget || zoom - abs_zoom > kZoomBudget; }
    void record(int64_t ts, const cv::Mat &gray) {
        good_ts = ts;
        good = gray;
        history.push_back({ts, chain, path, zoom});
        // 4s even at 240Hz, and at least 240 poses at slower normal capture rates.
        while (history.size() > 1024 || (history.size() > 240 && ts - history.front().ts > kHistory))
            history.pop_front();
    }
};
} // namespace
} // namespace luxin::motion

namespace luxin {
using namespace motion;

struct BigMapMotionSession::State {
    Launch launch;
    uint64_t epoch = 1, generation = 0;
    int64_t last_ts = -1;
    Epoch e;
    // Tracking loss/rebase or a delayed absolute result cannot clear an overlay latch.
    // Only a new capture context or a moving Map observation of that same ROI can.
    std::array<bool, 2> overlay{};
    std::optional<Snapshot> snapshot;
    std::future<BigMapGeometry> worker;
    bool debug_points = false;

    void invalidate(bool new_context = true) {
        ++epoch;
        e = {};
        if (new_context)
            overlay = {};
        snapshot.reset(); // keep the future: replacing an unfinished async future would join it
    }
    Pose toFrame(Pose p) const {
        return changeUnits(p, double(e.size.width) / e.masks.ui.cols, double(e.size.height) / e.masks.ui.rows);
    }
    Pose toWork(Pose p) const {
        return changeUnits(p, double(e.masks.ui.cols) / e.size.width, double(e.masks.ui.rows) / e.size.height);
    }
    void advance(const cv::Mat &gray, int64_t ts, BigMapMotionStep &step) {
        const auto dt = ts - e.good_ts;
        const auto frame_step = toFrame(step.work);
        e.chain = frame_step * e.chain;
        e.path += cv::norm(shift(step.work, gray.size()));
        e.zoom += std::abs(std::log(step.work(0, 0)));
        // Integrate centre displacements and log-scale; velocity is in original frame px/s.
        const auto d = shift(frame_step, e.size);
        const cv::Vec3d delta(std::log(frame_step(0, 0)), d.x, d.y);
        const double seconds = dt * 1e-9;
        e.measurement += delta;
        if (!e.previous_dt) {
            e.filtered = e.measurement;
            e.velocity = delta / seconds;
        } else {
            const auto prior = e.filtered + seconds * e.velocity;
            const auto residual = e.measurement - prior;
            e.filtered = prior + kAlpha * residual;
            e.velocity += (kBeta / seconds) * residual;
        }
        e.previous_dt = dt;
        e.previous = step.work;
        e.record(ts, gray);
    }
    std::optional<Pose> prediction(int64_t dt, bool bridge = false) const {
        if (e.previous_dt <= 0)
            return {};
        if (!bridge)
            return predict(e.previous, double(dt) / e.previous_dt, e.good.size());
        // Across missed frames use the alpha-beta velocity instead of extending the last noisy
        // measurement. Ordinary adjacent frames retain the Python constant-last-step predictor.
        const double seconds = std::clamp(double(dt) / e.previous_dt, .25, 2.5) * e.previous_dt * 1e-9;
        const double scale = std::exp(e.velocity[0] * seconds);
        const Pose forecast = similarity(scale, e.velocity[1] * seconds + (1 - scale) * e.size.width * .5,
                                         e.velocity[2] * seconds + (1 - scale) * e.size.height * .5);
        return validSimilarity(forecast) ? std::optional<Pose>(toWork(forecast)) : std::nullopt;
    }
    void request(const cv::Mat &frame, const cv::Mat &gray, const Refs &refs, int64_t ts, bool connected,
                 BigMapMotionStats &out) {
        if (worker.valid() || !launch || refs.empty())
            return;
        const bool slow = out.step.ok && maxTravel(out.step.work, gray.size()) <= kSlow;
        const int64_t age = ts - e.request_ts;
        if (!e.urgent && e.request_ts >= 0) {
            if (!e.applied || e.lost_ts >= 0 || e.overBudget()) {
                if (age < kRetry)
                    return;
            } else if (age < kRefresh || !slow)
                return;
        }
        cv::Mat owned;
        if (frame.channels() == 4)
            cv::cvtColor(frame, owned, cv::COLOR_BGRA2BGR);
        else
            owned = frame.clone();
        e.request_ts = ts;
        e.urgent = false;
        worker = launch(std::move(owned), refs, ts * 1e-9);
        if (worker.valid()) {
            snapshot = Snapshot{epoch, ts, gray, connected};
            out.requested = true;
        }
    }
    double jump(const Pose &from, const Pose &to) const {
        // Evaluate at the corners currently visible, not at Kongying coordinates (0,0)..(w,h).
        return maxTravel((e.chain * to) * (e.chain * from).inv(), e.size) * 3120. / e.size.width;
    }
    bool adopt(const Pose &candidate, const BigMapGeometry &a, const Sample &sample, BigMapMotionStats &out) {
        if (!validSimilarity(candidate))
            return false;
        if (e.applied) {
            const double distance = out.jump_3120 = jump(*e.applied, candidate);
            if (distance > kLarge || std::abs(scaleOf(candidate) / scaleOf(*e.applied) - 1) > kScaleJump) {
                if (!e.suspect || sample.ts - e.suspect->ts < kIndependent ||
                    jump(e.suspect->pose, candidate) > kAgree) {
                    if (e.suspect)
                        ++e.conflicts;
                    e.suspect = Suspicion{sample.ts, candidate};
                    out.absolute = "suspect";
                    return false;
                }
            } else if (e.suspect)
                ++e.conflicts;
            e.blend_frames = distance > kSmall ? 3 : 0;
            out.absolute = e.blend_frames ? "blend_three" : "blend_small";
        } else {
            e.applied = candidate;
            out.absolute = "initial";
        }
        e.suspect.reset();
        e.target = candidate;
        e.identity = a;
        // Charge motion that happened AFTER this snapshot, even if SIFT took seconds.
        e.abs_ts = sample.ts;
        e.abs_path = sample.path;
        e.abs_zoom = sample.zoom;
        e.lost_ts = -1;
        return true;
    }
    void collect(const cv::Mat &gray, int64_t ts, BigMapMotionStats &out) {
        if (!worker.valid() || worker.wait_for(std::chrono::seconds(0)) != std::future_status::ready)
            return;
        auto snap = std::move(snapshot);
        snapshot.reset();
        BigMapGeometry a;
        try {
            a = worker.get();
        } catch (const std::exception &) {
            out.absolute = "search_failed";
            return;
        }
        out.absolute = "discarded";
        if (!snap || snap->epoch != epoch || a.mode != "found" || a.timestamp_seconds != snap->ts * 1e-9 ||
            a.frame_width != e.size.width || a.frame_height != e.size.height || a.space.empty() ||
            a.reference.empty() || a.inliers < kMin || a.matches < a.inliers ||
            !(a.support >= 0 && a.support <= 1) || !std::isfinite(a.error_px) || a.error_px < 0 ||
            a.matrix.type() != CV_64F || a.matrix.size() != cv::Size(3, 2))
            return;
        Pose abs = Pose::eye();
        for (int i = 0; i < 2; ++i)
            for (int j = 0; j < 3; ++j)
                abs(i, j) = a.matrix.at<double>(i, j);
        if (!validSimilarity(abs))
            return;
        if (e.applied && e.identity.space != a.space) {
            invalidate(false);
            out.bridge = false;
            out.step.ok = false;
            out.step.reason = "space_change";
            out.absolute = "space_change";
            return;
        }
        auto it =
            std::find_if(e.history.begin(), e.history.end(), [&](const Sample &s) { return s.ts == snap->ts; });
        if (snap->connected && it != e.history.end() && e.lost_ts < 0) {
            adopt(correction(it->chain, abs), a, *it, out);
            return;
        }
        // No continuous chain. A fresh snapshot-to-current LK measurement is required;
        // never stamp the absolute result with the consumption time. Rebase only on success.
        if (ts <= snap->ts || ts - snap->ts > kRetain || (!snap->connected && e.lost_ts < 0))
            return;
        auto bridge = track(snap->gray, gray, e.masks, prediction(ts - snap->ts, true), debug_points);
        if (!bridge.ok)
            return;
        if (snap->connected && it != e.history.end() && e.applied) {
            // Retained history allows the normal conflict gate even after a dropout.
            const Sample s = *it;
            if (!adopt(correction(s.chain, abs), a, s, out))
                return;
            e.chain = s.chain;
            e.path = s.path;
            e.zoom = s.zoom;
            e.history.clear();
            e.good_ts = s.ts;
            e.previous_dt = 0;
        } else {
            // The old and new chains have no measured relation. No old D may cross this boundary.
            auto size = e.size;
            invalidate(false);
            e.size = size;
            e.masks.prepare(gray.size());
            e.record(snap->ts, snap->gray);
            adopt(abs, a, e.history.back(), out);
        }
        advance(gray, ts, bridge);
        e.failures = 0;
        out.step = bridge;
        out.bridge = true;
    }
    BigMapSessionResult update(const cv::Mat &frame, const Refs &refs, int64_t ts, uint64_t gen,
                               BigMapMotionStats &out) {
        BigMapSessionResult r;
        if (gen < generation) {
            out.step.reason = "old_generation";
            return r;
        }
        if (gen != generation) {
            invalidate();
            generation = gen;
            last_ts = -1;
        }
        if (ts < 0 || ts <= last_ts) {
            out.step.reason = "invalid_timestamp";
            return r;
        }
        const int64_t interval = last_ts < 0 ? 0 : ts - last_ts;
        last_ts = ts;
        if (frame.empty() || frame.cols <= frame.rows || frame.depth() != CV_8U ||
            (frame.channels() != 1 && frame.channels() != 3 && frame.channels() != 4)) {
            invalidate(false);
            out.step.reason = "invalid_frame";
            return r;
        }
        const int64_t retained_since = e.lost_ts >= 0 ? e.lost_ts : e.good_ts;
        if (!e.size.empty() && frame.size() != e.size)
            invalidate();
        else if (retained_since >= 0 && ts - retained_since > kRetain)
            invalidate(false);
        e.size = frame.size();
        auto gray = gray360(frame);
        e.masks.prepare(gray.size());
        // An LK bridge is measured against last_good (or a snapshot), not the preceding
        // capture. Never apply that transform to the intervening frame for ROI evidence.
        cv::Mat previous_gray;
        if (e.good.empty())
            e.record(ts, gray);
        else {
            const int64_t gap = ts - e.good_ts;
            out.step = track(e.good, gray, e.masks, prediction(gap, e.failures > 0), debug_points);
            if (out.step.ok && gap <= kBridge && e.failures < kFailures && e.lost_ts < 0) {
                out.bridge = e.failures > 0;
                e.failures = 0;
                if (!out.bridge)
                    previous_gray = e.good;
                advance(gray, ts, out.step);
                if (!validSimilarity(e.chain)) {
                    invalidate(false);
                    out.step.ok = false;
                    out.step.reason = "invalid_chain";
                    return r;
                }
            } else {
                if (out.step.ok) {
                    out.step.ok = false;
                    out.step.reason = "bridge_expired";
                }
                ++e.failures;
                if (e.lost_ts < 0 && (gap > kBridge || e.failures >= kFailures)) {
                    e.lost_ts = ts;
                    e.urgent = true;
                }
            }
        }
        const auto before = epoch;
        collect(gray, ts, out);
        if (before != epoch && !out.bridge)
            return r;
        if (!previous_gray.empty() && out.step.ok && !out.bridge) {
            const bool moving = maxTravel(out.step.work, gray.size()) >= kControlMotionPx;
            const Pose inv = out.step.work.inv();
            for (size_t i = 0; i < kControls.size(); ++i) {
                out.controls[i] =
                    observeControl(kControls[i], previous_gray, gray, e.masks.control_source, inv, moving);
                if (out.controls[i].verdict == ControlVerdict::Overlay)
                    overlay[i] = true;
                else if (moving && out.controls[i].verdict == ControlVerdict::Map)
                    overlay[i] = false;
            }
        }
        out.overlay_hidden = overlay[0] || overlay[1];
        if (e.target && e.applied && *e.applied != *e.target) {
            const double f = e.blend_frames ? 1. / e.blend_frames-- : -std::expm1(-interval * 1e-9 / kTau);
            e.applied = interpolate(*e.applied, *e.target, f);
        }
        if (e.overBudget() && !e.budget_was_exceeded)
            e.urgent = true;
        e.budget_was_exceeded = e.overBudget();
        request(frame, gray, refs, ts, e.good_ts == ts, out);
        out.chain = e.chain;
        out.path_since_abs = e.path - e.abs_path;
        out.zoom_since_abs = e.zoom - e.abs_zoom;
        out.abs_age_s = e.abs_ts < 0 ? -1 : (ts - e.abs_ts) * 1e-9;
        out.abs_conflicts = e.conflicts;
        out.lost = e.lost_ts >= 0;
        if (!out.step.ok || !e.applied || out.lost || e.overBudget() || out.overlay_hidden)
            return r;
        const Pose current = e.chain * *e.applied;
        if (!validSimilarity(current))
            return r;
        r.geometry = e.identity;
        r.geometry.matrix = cv::Mat(current).rowRange(0, 2).clone();
        r.geometry.timestamp_seconds = ts * 1e-9;
        r.geometry.matches = out.step.matches;
        r.geometry.inliers = out.step.inliers;
        r.geometry.error_px = out.step.error_px * e.size.height / gray.rows;
        r.phase = "tracking";
        r.follow.working_size = gray.size();
        r.follow.debug_points = out.step.debug_points;
        r.measured_motion = true;
        out.velocity = e.velocity;
        return r;
    }
};

BigMapMotionSession::BigMapMotionSession(Launch launch) : state_(std::make_unique<State>()) {
    state_->launch = launch ? std::move(launch) : Launch([](cv::Mat frame, Refs refs, double ts) {
        return std::async(std::launch::async, [frame = std::move(frame), refs = std::move(refs), ts] {
#ifdef __linux__
            setpriority(PRIO_PROCESS, 0, 10); // current worker thread, best effort
#endif
            return registerBigMap(frame, refs, ts);
        });
    });
}
BigMapMotionSession::~BigMapMotionSession() = default;
void BigMapMotionSession::reset() {
    state_->invalidate();
}
BigMapSessionResult BigMapMotionSession::update(const cv::Mat &frame, const Refs &refs, int64_t timestamp_ns,
                                                uint64_t generation, BigMapMotionStats *stats,
                                                bool debug_points) {
    BigMapMotionStats out;
    state_->debug_points = debug_points;
    auto r = state_->update(frame, refs, timestamp_ns, generation, out);
    out.epoch = state_->epoch;
    if (stats)
        *stats = out;
    return r;
}
} // namespace luxin
