#pragma once
#include "luxin/bigmap_detector.hpp"
#include <opencv2/imgproc.hpp>

namespace luxin::motion {
// Store the full search ROI including the resampling footprint. A competing
// button pair can change the detector's winner outside the previous cue.
class MotionUiGuard {
    cv::Rect area;
    cv::Mat reference;
    cv::Size size;
public:
    bool check(const cv::Mat &rgba, bool force) {
        if (!force && !reference.empty() && rgba.size()==size &&
            cv::norm(rgba(area),reference,cv::NORM_INF)==0) return true;
        reference.release();
        if (rgba.empty()) return false;
        cv::Mat bgr; cv::cvtColor(rgba,bgr,cv::COLOR_RGBA2BGR);
        const auto hit=detectBigMap(bgr);
        if (!hit.detected) return false;
        const double sy=rgba.rows/720., sx=rgba.cols/double(cvRound(rgba.cols/sy));
        const auto tl=cv::Point(0,cvFloor(201*sy)-2);
        const auto br=cv::Point(cvCeil(230*sx)+2,cvCeil(518*sy)+2);
        size=rgba.size(); area=cv::Rect(tl,br)&cv::Rect({},size);
        reference=rgba(area).clone();
        return true;
    }
};
}
