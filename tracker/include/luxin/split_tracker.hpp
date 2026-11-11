#pragma once

#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>

namespace luxin {

struct SplitGates {
    int min_inliers = 6;
    double min_inlier_fraction = 0.35;
    double min_scene_support = 0.10;
    double max_error_px = 2.0;
    double position_margin = 0.10;
    int min_layer_pixels = 256;
    int min_active_pixels = 128;
    double max_layer_error = 20.0;
    double min_layer_margin = 5.0;
    double min_layer_correlation = 0.40;
};

struct MaskCircle {
    float x = 0.0f;
    float y = 0.0f;
    float radius = 0.0f;
};

struct MinimapSpec {
    double center_x = 399.5;
    double center_y = 207.5;
    double radius = 176.0;
    double base_scale = 0.75; // screen px per marker unit at frame height 1440, zoom x1

    static MinimapSpec forFrameHeight(int frame_height);
};

struct MinimapCrop {
    cv::Mat gray;
    cv::Mat mask;
    double lx = 0.0;
    double ly = 0.0;
    double scale_k = 1.0;

    static MinimapCrop fromBgrFrame(
        const cv::Mat& frame_bgr,
        const MinimapSpec& spec = MinimapSpec(),
        const std::vector<MaskCircle>& excluded_screen_circles = {}
    );

    static MinimapCrop fromGrayCrop(
        const cv::Mat& crop_gray,
        const cv::Mat& crop_mask,
        double lx,
        double ly,
        double scale_k = 1.0,
        const std::vector<MaskCircle>& excluded_local_circles = {}
    );
};

struct Registration {
    double x = 0.0;
    double y = 0.0;
    double scale = 0.0;
    double rotation = 0.0;
    int inliers = 0;
    int matches = 0;
    double error_px = 0.0;
    cv::Mat matrix;                  // 2x3 CV_64F: minimap native crop px -> reference px
    std::vector<cv::Point2f> inlier_points;
};

struct PositionFix {
    std::string space;
    double x = 0.0;
    double y = 0.0;
    double scale = 0.0;
    double rotation = 0.0;
    double support = 0.0;
    int inliers = 0;
    double error_px = 0.0;
    double score = 0.0;
};

struct LayerScore {
    std::string layer;
    double error = 0.0;
    double correlation = 0.0;
    int active_pixels = 0;
    double gain = 1.0;
    double offset = 0.0;
};

struct LayerDecision {
    std::string layer;               // empty when unknown or pending
    std::string mode = "unknown";    // "known", "pending", "candidate", "unknown"
    std::string reason = "no position";
    double error = -1.0;
    double margin = -1.0;
    std::vector<LayerScore> evidence;
};

struct TrackingStepResult {
    bool has_position = false;
    PositionFix position;
    std::string position_mode = "rejected"; // "found", "track", "pending", "rejected", "ambiguous", "hidden"
    std::string search_mode = "global";     // "global", "local"
    LayerDecision layer;
    std::vector<PositionFix> candidates;

    bool overlayReady() const {
        return has_position
            && (position_mode == "found" || position_mode == "track")
            && layer.mode == "known"
            && !layer.layer.empty();
    }
};

class LayerReference {
public:
    LayerReference(
        std::string name,
        std::string space,
        std::string layer,
        const cv::Mat& gray,
        const cv::Mat& active_mask,
        const cv::Mat& context_mask,
        double units_per_px,
        double origin_x,
        double origin_y
    );

    const std::string& name() const { return name_; }
    const std::string& space() const { return space_; }
    const std::string& layer() const { return layer_; }
    const cv::Mat& gray() const { return gray_; }
    const cv::Mat& activeMask() const { return active_mask_; }
    const cv::Mat& contextMask() const { return context_mask_; }
    double unitsPerPx() const { return upp_; }
    double originX() const { return ox_; }
    double originY() const { return oy_; }

    bool registerQuery(
        const std::vector<cv::Point2f>& query_points,
        const cv::Mat& query_descriptors,
        const MinimapCrop& mm,
        double unit_scale,
        const PositionFix* hint,
        double radius,
        Registration& out_reg
    ) const;

private:
    std::string name_;
    std::string space_;
    std::string layer_;
    cv::Mat gray_;
    cv::Mat active_mask_;
    cv::Mat context_mask_;
    double upp_ = 1.0;
    double ox_ = 0.0;
    double oy_ = 0.0;

    std::vector<cv::Point2f> points_;
    cv::Mat descriptors_;
    cv::Ptr<cv::FlannBasedMatcher> global_matcher_;
};

cv::Mat poseMatrix(const LayerReference& ref, const MinimapCrop& mm, const PositionFix& fix);

bool positionFromRegistration(
    const LayerReference& model,
    const Registration& reg,
    const MinimapCrop& mm,
    const SplitGates& gates,
    PositionFix& out_fix
);

std::pair<bool, std::string> choosePosition(
    const std::vector<PositionFix>& candidates,
    const SplitGates& gates,
    PositionFix& out_best
);

std::pair<std::vector<LayerScore>, std::string> appearanceErrors(
    const cv::Mat& query_gray,
    const std::vector<cv::Mat>& predictions,
    const std::vector<cv::Mat>& active_masks,
    const cv::Mat& minimap_mask,
    const SplitGates& gates
);

LayerDecision classifyLayer(
    const MinimapCrop& mm,
    const PositionFix& fix,
    const std::vector<std::shared_ptr<LayerReference>>& models,
    const SplitGates& gates
);

class LayerLatch {
public:
    void reset();
    LayerDecision update(LayerDecision decision, double timestamp_s);

private:
    bool has_last_ = false;
    std::string last_layer_;
    double last_time_s_ = 0.0;
};

class SplitTracker {
public:
    SplitTracker(
        std::vector<std::shared_ptr<LayerReference>> models,
        double unit_scale,
        SplitGates gates = SplitGates()
    );

    void reset();
    TrackingStepResult update(const MinimapCrop& mm, double timestamp_s);
    const SplitGates& gates() const { return gates_; }

private:
    std::vector<std::shared_ptr<LayerReference>> models_;
    double unit_ = 0.5625;
    SplitGates gates_;
    LayerLatch layer_latch_;

    bool has_hint_ = false;
    PositionFix hint_;
    bool has_last_t_ = false;
    double last_t_ = 0.0;
    bool has_pending_ = false;
    PositionFix pending_;
    double pending_t_ = 0.0;
    int failures_ = 0;
};

} // namespace luxin
