#pragma once

#include "luxin/bigmap_session.hpp"

#include <array>
#include <cstdint>
#include <functional>
#include <memory>

namespace luxin {

struct BigMapMotionStep {
    const char *reason = "no_previous_frame";
    bool ok = false;
    int corners = 0, matches = 0, inliers = 0;
    double error_px = 0, lk_ms = 0, ransac_ms = 0;
    cv::Matx33d work = cv::Matx33d::eye(); // last good -> current, working pixels
};

enum class ControlVerdict { Unverifiable, Map, Overlay };
struct MotionControlObservation {
    ControlVerdict verdict = ControlVerdict::Unverifiable;
    double coverage = 0.;
    bool screen_fixed = false; // unwarped comparison passed while the map prediction failed during motion
    double warped_residual = -1., fixed_residual = -1.; // mean absolute gray-level error; -1 = no samples
    double warped_changed_fraction = -1., fixed_changed_fraction = -1.; // residual >24, same covered pixels
};

struct BigMapMotionStats {
    BigMapMotionStep step;
    cv::Matx33d chain = cv::Matx33d::eye(); // frame_0 -> current captured pixels; only valid on success
    cv::Vec3d velocity{};                   // d(log s)/s, dx/s, dy/s at the captured frame centre
    uint64_t epoch = 0;
    bool bridge = false, lost = false, overlay_hidden = false, requested = false;
    std::array<MotionControlObservation, 2> controls{};
    const char *absolute = "none";
    double jump_3120 = 0, path_since_abs = 0, zoom_since_abs = 0, abs_age_s = -1;
    unsigned abs_conflicts = 0;
};

// Single serialized owner. Workers own their snapshot/refs and never touch session state.
// update/reset do not wait for SIFT. Destruction joins the last outstanding worker.
class BigMapMotionSession {
  public:
    using Refs = std::vector<std::shared_ptr<LayerReference>>;
    using Launch = std::function<std::future<BigMapGeometry>(cv::Mat, Refs, double)>;
    explicit BigMapMotionSession(Launch launch = {});
    ~BigMapMotionSession();
    BigMapMotionSession(const BigMapMotionSession &) = delete;
    BigMapMotionSession &operator=(const BigMapMotionSession &) = delete;

    // Frame timestamp is monotonic capture time (ns); generation is monotonic across restarts.
    // Call reset on pause, reference mutation and external map invalidation, even within a generation.
    void reset();
    BigMapSessionResult update(const cv::Mat &frame, const Refs &refs, int64_t timestamp_ns,
                               uint64_t generation, BigMapMotionStats *stats = nullptr);

  private:
    struct State;
    std::unique_ptr<State> state_;
};

} // namespace luxin
