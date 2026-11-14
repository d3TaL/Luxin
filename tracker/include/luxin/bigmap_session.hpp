#pragma once

#include "luxin/bigmap_geometry.hpp"

#include <array>

namespace luxin {

// Aggregate diagnostics only: no pixels, coordinates of the player, or layer decision.
// -1 means not evaluated (signed angle availability follows scale).
// Distances/rectangles use the <=720-high follow image, fractions are 0..1.
// Early returns are preserved: worst_tile covers visited tiles only,
// and controls after the first rejected ROI remain unevaluated.
struct BigMapControlStats {
    cv::Rect rect;
    double mean_delta = -1.0;
    int changed_pixels = -1;
    int pixels = 0;
    double changed_fraction = -1.0;
};

struct BigMapFollowStats {
    const char* outcome = "not_attempted"; // accepted, or the FIRST failed gate
    cv::Size working_size;
    int anchor_keypoints = -1;
    int current_keypoints = -1;
    int anchor_descriptors = -1;
    int current_descriptors = -1;
    int matches = -1;
    int inliers = -1;
    int grid_cells = -1;
    double inlier_ratio = -1.0;
    double mean_error_px = -1.0;
    double scale = -1.0;
    double angle_degrees = -1.0; // signed; evaluated iff scale >= 0
    double hull_fraction = -1.0;
    int overlap_pixels = -1;
    double overlap_fraction = -1.0;
    int changed_pixels = -1;
    double changed_fraction = -1.0;
    int tiles_evaluated = 0;
    cv::Rect worst_tile;
    int worst_tile_valid_pixels = -1;
    int worst_tile_changed_pixels = -1;
    double worst_tile_changed_fraction = -1.0;
    std::array<BigMapControlStats, 2> controls{};
};

struct BigMapSessionResult {
    BigMapGeometry geometry; // mode=found only after a fresh stationary confirmation
    std::string phase = "acquiring";
    bool absolute_attempted = false;
    BigMapRegistrationStats registration;
    double follow_ms = 0.0;
    BigMapFollowStats follow;
    const char* reset_reason = "none";
    bool anchor_expired = false; // skipped, not a new reset (preserves existing behavior)
    double anchor_age_seconds = -1.0; // ages before any reset on this update
    double observation_gap_seconds = -1.0;
    double since_attempt_seconds = -1.0;
    double movement_px = -1.0;
    int stable_frames = 0;
    bool absolute_throttled = false;
};

// Synchronous, diagnostic-only observer immediately BEFORE registerBigMap can block.
// Context is borrowed only until update returns. Must not mutate/re-enter the session,
// retain references to the result, or throw; observer output never controls decisions.
using BigMapSessionDiagnosticSink = void (*)(const BigMapSessionResult&, void*) noexcept;

// Diagnostics only. No floor decision or permission to render item markers.
// Every follow transform is measured directly against an absolute anchor, never chained.
class BigMapSession {
public:
    void reset();
    BigMapSessionResult update(const cv::Mat& frame,
        const std::vector<std::shared_ptr<LayerReference>>& references, double timestamp_seconds,
        BigMapSessionDiagnosticSink before_absolute = nullptr, void* diagnostic_context = nullptr);

private:
    cv::Mat anchor_gray_;
    cv::Mat anchor_descriptors_;
    std::vector<cv::KeyPoint> anchor_keypoints_;
    BigMapGeometry anchor_;
    cv::Mat previous_transform_;
    double previous_timestamp_ = -1.0;
    double last_attempt_ = -1.0;
    cv::Size frame_size_;
    int stable_frames_ = 0;
};

} // namespace luxin
