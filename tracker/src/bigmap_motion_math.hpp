#pragma once

#include "luxin/bigmap_motion.hpp"

namespace luxin::motion {
using Pose = cv::Matx33d;
using Points = std::vector<cv::Point2d>;
Pose similarity(double s, double tx, double ty);
Pose predict(const Pose &previous, double dt_ratio, cv::Size size);
// K -> snapshot = A. Chain(snapshot) maps epoch pixels -> snapshot pixels.
// Thus D = Chain(snapshot)^-1 * A maps K -> epoch pixels, independent of any
// motion while the search ran. Current pose = Chain(now) * D.
Pose correction(const Pose &snapshot_chain, const Pose &absolute);
BigMapMotionStep fit(const Points &src, const Points &dst, std::vector<uchar> &inliers);
} // namespace luxin::motion
