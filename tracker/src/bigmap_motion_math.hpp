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
void requireFixedAdvantage(MotionControlObservation& observation, bool moving);
bool controlMoving(const Pose &step, cv::Size work_size);
struct ControlLatch {
    bool hidden = false;
    int strong = 0, clean = 0;
    void observe(MotionControlObservation &observation, bool moving);
};
void classifyControl(MotionControlObservation &observation, bool moving);
} // namespace luxin::motion
