#pragma once

#include "luxin/bigmap_geometry.hpp"

namespace luxin {

struct BigMapSessionResult {
    BigMapGeometry geometry; // mode=found only after a fresh stationary confirmation
    std::string phase = "acquiring";
    bool absolute_attempted = false;
    BigMapRegistrationStats registration;
    double follow_ms = 0.0;
};

// Diagnostics only. No floor decision or permission to render item markers.
// Every follow transform is measured directly against an absolute anchor, never chained.
class BigMapSession {
public:
    void reset();
    BigMapSessionResult update(const cv::Mat& frame,
        const std::vector<std::shared_ptr<LayerReference>>& references, double timestamp_seconds);

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
