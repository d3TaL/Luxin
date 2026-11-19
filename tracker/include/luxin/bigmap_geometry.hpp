#pragma once

#include "luxin/split_tracker.hpp"

namespace luxin {

// Independent of the selected floor and player position. Never authorizes item markers.
struct BigMapGeometry {
    std::string mode = "rejected";
    std::string space;
    std::string reference;
    cv::Mat matrix; // 2x3 CV_64F: Kongying coordinates -> ORIGINAL captured frame pixels
    int frame_width = 0;
    int frame_height = 0;
    double timestamp_seconds = 0.0;
    int inliers = 0;
    int matches = 0;
    double error_px = 0.0;
    double support = 0.0;
};

// Conservative union of plain/layer/panel UI masks, scaled from the research frame.
cv::Mat bigMapGeometryMask(cv::Size size);

// Both transforms map Kongying units to captured pixels. Compare over the entire frame.
bool equivalentBigMapGeometry(const BigMapGeometry& a, const BigMapGeometry& b);

// Shared quality gate, exposed for deterministic boundary regressions without private images.
bool bigMapGeometryQualityAccepted(int inliers, int matches, double error_px,
                                   double hull_area, int visible_pixels, cv::Size frame_size);

struct BigMapRegistrationStats {
    double features_ms = 0.0;
    double matching_ms = 0.0;
    double validation_ms = 0.0;
    int query_features = 0;
    int extraction_width = 0;
    int extraction_height = 0;
};

BigMapGeometry registerBigMap(
    const cv::Mat& frame,
    const std::vector<std::shared_ptr<LayerReference>>& references,
    double timestamp_seconds,
    BigMapRegistrationStats* stats = nullptr
);

} // namespace luxin
