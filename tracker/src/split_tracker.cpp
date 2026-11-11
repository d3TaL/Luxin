#include "luxin/split_tracker.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <map>
#include <numeric>
#include <set>
#include <stdexcept>

#include <opencv2/calib3d.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/flann.hpp>
#include <opencv2/imgproc.hpp>

namespace luxin {

namespace {

cv::Ptr<cv::SIFT> createSift() {
    return cv::SIFT::create(0, 3, 0.02);
}

double medianOf(std::vector<double> values) {
    if (values.empty()) {
        return 0.0;
    }
    const size_t n = values.size();
    const size_t mid = n / 2;
    std::nth_element(values.begin(), values.begin() + mid, values.end());
    double hi = values[mid];
    if (n % 2 == 1) {
        return hi;
    }
    auto max_it = std::max_element(values.begin(), values.begin() + mid);
    return 0.5 * (*max_it + hi);
}

void extractQueryFeatures(
    const MinimapCrop& mm,
    int up,
    std::vector<cv::Point2f>& out_points,
    cv::Mat& out_descriptors
) {
    out_points.clear();
    out_descriptors.release();
    if (mm.gray.empty() || mm.mask.empty() || up < 1) {
        return;
    }
    cv::Mat img_up;
    cv::Mat mask_up;
    cv::resize(mm.gray, img_up, cv::Size(), up, up, cv::INTER_CUBIC);
    cv::resize(mm.mask, mask_up, cv::Size(), up, up, cv::INTER_NEAREST);

    std::vector<cv::KeyPoint> kps;
    createSift()->detectAndCompute(img_up, mask_up, kps, out_descriptors);
    out_points.reserve(kps.size());
    const float inv_up = 1.0f / static_cast<float>(up);
    for (const auto& kp : kps) {
        out_points.emplace_back(kp.pt.x * inv_up, kp.pt.y * inv_up);
    }
}

cv::Mat warpToMinimap(const cv::Mat& image, const cv::Mat& matrix, cv::Size mm_size, bool is_mask) {
    cv::Mat dst;
    int flags = cv::WARP_INVERSE_MAP | (is_mask ? cv::INTER_NEAREST : cv::INTER_LINEAR);
    cv::warpAffine(image, dst, matrix, mm_size, flags);
    return dst;
}

} // namespace

MinimapSpec MinimapSpec::forFrameHeight(int frame_height) {
    MinimapSpec spec;
    const double k = static_cast<double>(frame_height) / 1440.0;
    spec.center_x = 399.5 * k;
    spec.center_y = 207.5 * k;
    spec.radius = 176.0 * k;
    spec.base_scale = 0.75 * k;
    return spec;
}

MinimapCrop MinimapCrop::fromBgrFrame(
    const cv::Mat& frame_bgr,
    const MinimapSpec& spec,
    const std::vector<MaskCircle>& excluded_screen_circles
) {
    if (frame_bgr.empty()) {
        throw std::invalid_argument("empty frame_bgr");
    }
    const double k = static_cast<double>(frame_bgr.rows) / 1440.0;
    const double cx = spec.center_x;
    const double cy = spec.center_y;
    const double r = spec.radius;
    const int pad = static_cast<int>(r) + 4;
    const int x0 = static_cast<int>(std::llround(cx)) - pad;
    const int y0 = static_cast<int>(std::llround(cy)) - pad;
    const int side = 2 * pad;

    if (x0 < 0 || y0 < 0 || x0 + side > frame_bgr.cols || y0 + side > frame_bgr.rows) {
        throw std::invalid_argument("minimap crop out of frame bounds");
    }

    cv::Mat crop_bgr = frame_bgr(cv::Rect(x0, y0, side, side));
    cv::Mat gray;
    cv::cvtColor(crop_bgr, gray, cv::COLOR_BGR2GRAY);

    const double lx = cx - x0;
    const double ly = cy - y0;
    cv::Mat mask = cv::Mat::zeros(gray.size(), CV_8UC1);
    cv::circle(mask, cv::Point(static_cast<int>(lx), static_cast<int>(ly)),
               static_cast<int>(r - 6.0 * k), cv::Scalar(255), -1);
    cv::circle(mask, cv::Point(static_cast<int>(lx), static_cast<int>(ly)),
               static_cast<int>(24.0 * k), cv::Scalar(0), -1);

    for (const auto& c : excluded_screen_circles) {
        const int mx = static_cast<int>(std::llround(c.x - x0));
        const int my = static_cast<int>(std::llround(c.y - y0));
        const int mr = static_cast<int>(std::ceil(c.radius));
        if (mr > 0) {
            cv::circle(mask, cv::Point(mx, my), mr, cv::Scalar(0), -1);
        }
    }

    MinimapCrop out;
    out.gray = gray;
    out.mask = mask;
    out.lx = lx;
    out.ly = ly;
    out.scale_k = k;
    return out;
}

MinimapCrop MinimapCrop::fromGrayCrop(
    const cv::Mat& crop_gray,
    const cv::Mat& crop_mask,
    double lx,
    double ly,
    double scale_k,
    const std::vector<MaskCircle>& excluded_local_circles
) {
    if (crop_gray.empty() || crop_mask.empty() || crop_gray.size() != crop_mask.size()) {
        throw std::invalid_argument("invalid crop_gray or crop_mask");
    }
    MinimapCrop out;
    out.gray = crop_gray.clone();
    out.mask = crop_mask.clone();
    out.lx = lx;
    out.ly = ly;
    out.scale_k = scale_k;
    for (const auto& c : excluded_local_circles) {
        const int mx = static_cast<int>(std::llround(c.x));
        const int my = static_cast<int>(std::llround(c.y));
        const int mr = static_cast<int>(std::ceil(c.radius));
        if (mr > 0) {
            cv::circle(out.mask, cv::Point(mx, my), mr, cv::Scalar(0), -1);
        }
    }
    return out;
}

LayerReference::LayerReference(
    std::string name,
    std::string space,
    std::string layer,
    const cv::Mat& gray,
    const cv::Mat& active_mask,
    const cv::Mat& context_mask,
    double units_per_px,
    double origin_x,
    double origin_y
)
    : name_(std::move(name)),
      space_(std::move(space)),
      layer_(std::move(layer)),
      gray_(gray.clone()),
      active_mask_(active_mask.clone()),
      context_mask_(context_mask.clone()),
      upp_(units_per_px),
      ox_(origin_x),
      oy_(origin_y) {
    if (space_.empty() || layer_.empty()) {
        throw std::invalid_argument("space and layer must be non-empty");
    }
    if (gray_.empty() || gray_.type() != CV_8UC1) {
        throw std::invalid_argument("reference gray must be CV_8UC1");
    }
    if (active_mask_.size() != gray_.size() || context_mask_.size() != gray_.size()) {
        throw std::invalid_argument("active_mask and context_mask must match reference size");
    }
    if (!(upp_ > 0.0 && std::isfinite(upp_))) {
        throw std::invalid_argument("units_per_px must be positive and finite");
    }

    std::vector<cv::KeyPoint> kps;
    createSift()->detectAndCompute(gray_, cv::noArray(), kps, descriptors_);
    points_.reserve(kps.size());
    for (const auto& kp : kps) {
        points_.push_back(kp.pt);
    }
    if (!descriptors_.empty() && points_.size() >= 2) {
        global_matcher_ = cv::makePtr<cv::FlannBasedMatcher>(
            cv::makePtr<cv::flann::KDTreeIndexParams>(5),
            cv::makePtr<cv::flann::SearchParams>(64)
        );
        global_matcher_->add(std::vector<cv::Mat>{descriptors_});
        global_matcher_->train();
    }
}

bool LayerReference::registerQuery(
    const std::vector<cv::Point2f>& query_points,
    const cv::Mat& query_descriptors,
    const MinimapCrop& mm,
    double unit_scale,
    const PositionFix* hint,
    double radius,
    Registration& out_reg
) const {
    if (descriptors_.empty() || points_.size() < 2 || query_descriptors.empty() || query_points.size() < 6) {
        return false;
    }

    std::vector<cv::Point2f> cand_points;
    cv::Mat cand_descriptors;

    if (hint != nullptr) {
        const double cx = (hint->x - ox_) / upp_;
        const double cy = (hint->y - oy_) / upp_;
        const double max_dim = static_cast<double>(std::max(mm.gray.rows, mm.gray.cols));
        const double r = (max_dim / (2.0 * hint->scale) + radius) / upp_;

        std::vector<int> keep_indices;
        keep_indices.reserve(points_.size());
        for (size_t i = 0; i < points_.size(); ++i) {
            if (std::abs(points_[i].x - cx) <= r && std::abs(points_[i].y - cy) <= r) {
                keep_indices.push_back(static_cast<int>(i));
            }
        }
        if (keep_indices.size() < 2) {
            return false;
        }
        cand_points.reserve(keep_indices.size());
        cand_descriptors.create(static_cast<int>(keep_indices.size()), descriptors_.cols, descriptors_.type());
        for (size_t k = 0; k < keep_indices.size(); ++k) {
            int idx = keep_indices[k];
            cand_points.push_back(points_[idx]);
            descriptors_.row(idx).copyTo(cand_descriptors.row(static_cast<int>(k)));
        }
    } else {
        cand_points = points_;
        cand_descriptors = descriptors_;
    }

    std::vector<std::vector<cv::DMatch>> pairs;
    if (hint != nullptr) {
        cv::BFMatcher local_matcher(cv::NORM_L2);
        local_matcher.knnMatch(query_descriptors, cand_descriptors, pairs, 2);
    } else if (global_matcher_) {
        global_matcher_->knnMatch(query_descriptors, pairs, 2);
    } else {
        return false;
    }

    std::vector<cv::DMatch> good;
    good.reserve(pairs.size());
    for (const auto& p : pairs) {
        if (p.size() == 2 && p[0].distance < 0.8f * p[1].distance) {
            good.push_back(p[0]);
        }
    }
    std::sort(good.begin(), good.end(), [](const cv::DMatch& a, const cv::DMatch& b) {
        return a.distance < b.distance;
    });

    std::vector<cv::DMatch> unique;
    std::set<std::pair<int, int>> used_q;
    std::set<std::pair<int, int>> used_r;
    for (const auto& m : good) {
        const auto& qp = query_points[m.queryIdx];
        const auto& rp = cand_points[m.trainIdx];
        auto qkey = std::make_pair(
            static_cast<int>(std::llround(qp.x * 2.0f)),
            static_cast<int>(std::llround(qp.y * 2.0f))
        );
        auto rkey = std::make_pair(
            static_cast<int>(std::llround(rp.x * upp_ * 2.0)),
            static_cast<int>(std::llround(rp.y * upp_ * 2.0))
        );
        if (used_q.find(qkey) == used_q.end() && used_r.find(rkey) == used_r.end()) {
            unique.push_back(m);
            used_q.insert(qkey);
            used_r.insert(rkey);
        }
    }

    if (unique.size() < 6) {
        return false;
    }

    std::vector<cv::Point2f> src;
    std::vector<cv::Point2f> dst;
    src.reserve(unique.size());
    dst.reserve(unique.size());
    for (const auto& m : unique) {
        src.push_back(query_points[m.queryIdx]);
        dst.push_back(cand_points[m.trainIdx]);
    }

    cv::Mat inliers;
    cv::Mat M = cv::estimateAffinePartial2D(
        src, dst, inliers, cv::RANSAC, 3.0 / upp_
    );
    if (M.empty() || inliers.empty() || M.rows != 2 || M.cols != 3) {
        return false;
    }

    const double m00 = M.at<double>(0, 0);
    const double m01 = M.at<double>(0, 1);
    const double m02 = M.at<double>(0, 2);
    const double m10 = M.at<double>(1, 0);
    const double m11 = M.at<double>(1, 1);
    const double m12 = M.at<double>(1, 2);
    if (!std::isfinite(m00) || !std::isfinite(m01) || !std::isfinite(m02) ||
        !std::isfinite(m10) || !std::isfinite(m11) || !std::isfinite(m12)) {
        return false;
    }

    const double size = std::hypot(m00, m10);
    if (size < 1e-6) {
        return false;
    }
    const double scale = 1.0 / (size * upp_);
    const double rotation = std::atan2(m10, m00) * (180.0 / CV_PI);
    if (std::abs(rotation) > 2.0 || !(scale / unit_scale >= 0.8 && scale / unit_scale <= 3.4)) {
        return false;
    }

    std::vector<cv::Point2f> inlier_pts;
    std::vector<double> errors;
    for (size_t i = 0; i < src.size(); ++i) {
        if (inliers.at<uchar>(static_cast<int>(i)) != 0) {
            inlier_pts.push_back(src[i]);
            const double px = m00 * src[i].x + m01 * src[i].y + m02;
            const double py = m10 * src[i].x + m11 * src[i].y + m12;
            const double err = std::hypot(px - dst[i].x, py - dst[i].y) / size;
            errors.push_back(err);
        }
    }
    if (inlier_pts.size() < 6) {
        return false;
    }

    const double center_rx = m00 * mm.lx + m01 * mm.ly + m02;
    const double center_ry = m10 * mm.lx + m11 * mm.ly + m12;
    const double x = center_rx * upp_ + ox_;
    const double y = center_ry * upp_ + oy_;
    if (hint != nullptr && std::hypot(x - hint->x, y - hint->y) > radius) {
        return false;
    }

    out_reg.x = x;
    out_reg.y = y;
    out_reg.scale = scale;
    out_reg.rotation = rotation;
    out_reg.inliers = static_cast<int>(inlier_pts.size());
    out_reg.matches = static_cast<int>(unique.size());
    out_reg.error_px = medianOf(std::move(errors));
    out_reg.matrix = M.clone();
    out_reg.inlier_points = std::move(inlier_pts);
    return true;
}

cv::Mat poseMatrix(const LayerReference& ref, const MinimapCrop& mm, const PositionFix& fix) {
    const double angle = fix.rotation * (CV_PI / 180.0);
    const double denom = fix.scale * ref.unitsPerPx();
    const double a = std::cos(angle) / denom;
    const double b = std::sin(angle) / denom;
    const double cx = (fix.x - ref.originX()) / ref.unitsPerPx();
    const double cy = (fix.y - ref.originY()) / ref.unitsPerPx();

    cv::Mat M(2, 3, CV_64F);
    M.at<double>(0, 0) = a;
    M.at<double>(0, 1) = -b;
    M.at<double>(0, 2) = cx - (a * mm.lx - b * mm.ly);
    M.at<double>(1, 0) = b;
    M.at<double>(1, 1) = a;
    M.at<double>(1, 2) = cy - (b * mm.lx + a * mm.ly);
    return M;
}

bool positionFromRegistration(
    const LayerReference& model,
    const Registration& reg,
    const MinimapCrop& mm,
    const SplitGates& gates,
    PositionFix& out_fix
) {
    cv::Mat projected = warpToMinimap(model.contextMask(), reg.matrix, mm.gray.size(), true);
    cv::Mat scene = cv::Mat::zeros(mm.gray.size(), CV_8UC1);
    for (int y = 0; y < scene.rows; ++y) {
        const uchar* p_row = projected.ptr<uchar>(y);
        const uchar* m_row = mm.mask.ptr<uchar>(y);
        uchar* s_row = scene.ptr<uchar>(y);
        for (int x = 0; x < scene.cols; ++x) {
            if (p_row[x] > 127 && m_row[x] > 0) {
                s_row[x] = 255;
            }
        }
    }

    std::vector<cv::Point> footprint;
    cv::findNonZero(scene, footprint);
    if (footprint.size() < 3) {
        return false;
    }
    std::vector<cv::Point> scene_hull;
    cv::convexHull(footprint, scene_hull);
    const double total_area = cv::contourArea(scene_hull);
    if (total_area <= 0.0) {
        return false;
    }

    cv::Mat near_scene;
    cv::dilate(scene, near_scene, cv::Mat::ones(5, 5, CV_8UC1));

    std::vector<cv::Point2f> pts;
    pts.reserve(reg.inlier_points.size());
    for (const auto& pt : reg.inlier_points) {
        const int ix = static_cast<int>(std::llround(pt.x));
        const int iy = static_cast<int>(std::llround(pt.y));
        if (ix >= 0 && ix < near_scene.cols && iy >= 0 && iy < near_scene.rows) {
            if (near_scene.at<uchar>(iy, ix) > 0) {
                pts.push_back(pt);
            }
        }
    }

    if (static_cast<int>(pts.size()) < gates.min_inliers) {
        return false;
    }

    std::vector<cv::Point2f> pts_hull;
    cv::convexHull(pts, pts_hull);
    const double support = std::min(1.0, cv::contourArea(pts_hull) / total_area);
    const double fraction = static_cast<double>(reg.inliers) / static_cast<double>(reg.matches);
    if (support < gates.min_scene_support
        || reg.error_px > gates.max_error_px
        || fraction < gates.min_inlier_fraction) {
        return false;
    }

    const double score = 0.5 * support + 0.3 * fraction + 0.2 * std::exp(-reg.error_px);
    out_fix.space = model.space();
    out_fix.x = reg.x;
    out_fix.y = reg.y;
    out_fix.scale = reg.scale;
    out_fix.rotation = reg.rotation;
    out_fix.support = support;
    out_fix.inliers = reg.inliers;
    out_fix.error_px = reg.error_px;
    out_fix.score = score;
    return true;
}

std::pair<bool, std::string> choosePosition(
    const std::vector<PositionFix>& candidates,
    const SplitGates& gates,
    PositionFix& out_best
) {
    if (candidates.empty()) {
        return {false, "rejected"};
    }
    std::vector<PositionFix> good = candidates;
    std::sort(good.begin(), good.end(), [](const PositionFix& a, const PositionFix& b) {
        return a.score > b.score;
    });
    const PositionFix& best = good[0];
    for (size_t i = 1; i < good.size(); ++i) {
        const auto& c = good[i];
        const bool conflicting = (c.space != best.space)
            || (std::hypot(c.x - best.x, c.y - best.y) > 3.0)
            || (std::abs(std::log(c.scale / best.scale)) > 0.03);
        if (conflicting) {
            if (best.score - c.score < gates.position_margin) {
                return {false, "ambiguous"};
            }
            break;
        }
    }
    out_best = best;
    return {true, "candidate"};
}

std::pair<std::vector<LayerScore>, std::string> appearanceErrors(
    const cv::Mat& query_gray,
    const std::vector<cv::Mat>& predictions,
    const std::vector<cv::Mat>& active_masks,
    const cv::Mat& minimap_mask,
    const SplitGates& gates
) {
    const size_t m_count = predictions.size();
    if (m_count < 2 || m_count > 8 || active_masks.size() != m_count) {
        return {{}, "need 2..8 layer models"};
    }
    if (query_gray.empty() || minimap_mask.size() != query_gray.size()) {
        return {{}, "invalid query or mask"};
    }

    const int rows = query_gray.rows;
    const int cols = query_gray.cols;

    std::vector<double> q_vals;
    std::vector<uint16_t> group_vals;
    std::vector<std::vector<double>> model_vals(m_count);
    std::vector<std::vector<bool>> model_active(m_count);

    for (int y = 0; y < rows; ++y) {
        const uchar* q_row = query_gray.ptr<uchar>(y);
        const uchar* m_row = minimap_mask.ptr<uchar>(y);
        for (int x = 0; x < cols; ++x) {
            if (m_row[x] == 0 || q_row[x] >= 210) {
                continue;
            }
            double min_p = std::numeric_limits<double>::infinity();
            double max_p = -std::numeric_limits<double>::infinity();
            uint16_t bitset = 0;
            for (size_t i = 0; i < m_count; ++i) {
                const double pv = static_cast<double>(predictions[i].at<uchar>(y, x));
                min_p = std::min(min_p, pv);
                max_p = std::max(max_p, pv);
                if (active_masks[i].at<uchar>(y, x) > 0) {
                    bitset |= static_cast<uint16_t>(1u << i);
                }
            }
            if ((max_p - min_p) > 8.0 && bitset != 0) {
                q_vals.push_back(static_cast<double>(q_row[x]));
                group_vals.push_back(bitset);
                for (size_t i = 0; i < m_count; ++i) {
                    model_vals[i].push_back(static_cast<double>(predictions[i].at<uchar>(y, x)));
                    model_active[i].push_back((bitset & static_cast<uint16_t>(1u << i)) != 0);
                }
            }
        }
    }

    const size_t n = q_vals.size();
    if (static_cast<int>(n) < gates.min_layer_pixels) {
        return {{}, "no visible layer difference"};
    }

    std::map<uint16_t, std::vector<size_t>> region_indices;
    for (size_t idx = 0; idx < n; ++idx) {
        region_indices[group_vals[idx]].push_back(idx);
    }
    std::vector<std::vector<size_t>> regions;
    for (auto& kv : region_indices) {
        if (kv.second.size() >= 32) {
            regions.push_back(std::move(kv.second));
        }
    }
    if (regions.size() < 2) {
        return {{}, "insufficient independent floor regions"};
    }

    auto solveWeightedLine = [&](const std::vector<double>& a, const std::vector<double>& w, double& gain, double& offset) {
        double sw = 0.0, sa = 0.0, sq = 0.0, saa = 0.0, saq = 0.0;
        for (size_t i = 0; i < n; ++i) {
            const double wi = w[i];
            const double ai = a[i];
            const double qi = q_vals[i];
            sw += wi;
            sa += wi * ai;
            sq += wi * qi;
            saa += wi * ai * ai;
            saq += wi * ai * qi;
        }
        const double det = sw * saa - sa * sa;
        if (std::abs(det) > 1e-9 && sw > 1e-9) {
            gain = (sw * saq - sa * sq) / det;
            offset = (saa * sq - sa * saq) / det;
        } else {
            gain = 1.0;
            offset = 0.0;
        }
    };

    std::vector<LayerScore> results(m_count);
    std::vector<double> weights(n, 1.0);
    std::vector<double> diff(n, 0.0);

    for (size_t m = 0; m < m_count; ++m) {
        const auto& a = model_vals[m];
        std::fill(weights.begin(), weights.end(), 1.0);
        double gain = 1.0, offset = 0.0;
        solveWeightedLine(a, weights, gain, offset);

        for (int iter = 0; iter < 4; ++iter) {
            for (size_t i = 0; i < n; ++i) {
                const double res = std::abs(q_vals[i] - (gain * a[i] + offset));
                weights[i] = std::min(1.0, 12.0 / std::max(res, 1e-5));
            }
            solveWeightedLine(a, weights, gain, offset);
            gain = std::clamp(gain, 0.2, 3.0);
            for (size_t i = 0; i < n; ++i) {
                diff[i] = q_vals[i] - gain * a[i];
            }
            offset = medianOf(diff);
        }

        std::vector<double> abs_err(n);
        for (size_t i = 0; i < n; ++i) {
            abs_err[i] = std::abs(q_vals[i] - (gain * a[i] + offset));
        }

        double sum_region_medians = 0.0;
        for (const auto& reg_idxs : regions) {
            std::vector<double> reg_errs;
            reg_errs.reserve(reg_idxs.size());
            for (size_t idx : reg_idxs) {
                reg_errs.push_back(abs_err[idx]);
            }
            sum_region_medians += medianOf(std::move(reg_errs));
        }
        const double group_error = sum_region_medians / static_cast<double>(regions.size());

        // Pearson correlation between a and q_vals
        double mean_a = std::accumulate(a.begin(), a.end(), 0.0) / static_cast<double>(n);
        double mean_q = std::accumulate(q_vals.begin(), q_vals.end(), 0.0) / static_cast<double>(n);
        double cov = 0.0, var_a = 0.0, var_q = 0.0;
        for (size_t i = 0; i < n; ++i) {
            const double da = a[i] - mean_a;
            const double dq = q_vals[i] - mean_q;
            cov += da * dq;
            var_a += da * da;
            var_q += dq * dq;
        }
        const double std_a = std::sqrt(var_a / static_cast<double>(n));
        const double std_q = std::sqrt(var_q / static_cast<double>(n));
        double corr = 0.0;
        if (std_a > 1.0 && std_q > 1.0) {
            corr = cov / std::sqrt(var_a * var_q);
        }

        int active_px = 0;
        for (bool act : model_active[m]) {
            if (act) {
                ++active_px;
            }
        }

        results[m].error = group_error;
        results[m].correlation = corr;
        results[m].active_pixels = active_px;
        results[m].gain = gain;
        results[m].offset = offset;
    }

    return {results, "scored"};
}

LayerDecision classifyLayer(
    const MinimapCrop& mm,
    const PositionFix& fix,
    const std::vector<std::shared_ptr<LayerReference>>& models,
    const SplitGates& gates
) {
    std::vector<std::shared_ptr<LayerReference>> available;
    for (const auto& m : models) {
        if (m->space() == fix.space) {
            available.push_back(m);
        }
    }
    if (available.empty()) {
        LayerDecision d;
        d.reason = "no models for position space";
        return d;
    }

    std::vector<cv::Mat> images;
    std::vector<cv::Mat> footprints;
    images.reserve(available.size());
    footprints.reserve(available.size());

    for (const auto& model : available) {
        cv::Mat M = poseMatrix(*model, mm, fix);
        images.push_back(warpToMinimap(model->gray(), M, mm.gray.size(), false));
        cv::Mat warped_active = warpToMinimap(model->activeMask(), M, mm.gray.size(), true);
        cv::Mat bin_active;
        cv::threshold(warped_active, bin_active, 127, 255, cv::THRESH_BINARY);
        footprints.push_back(bin_active);
    }

    auto [evidence, reason] = appearanceErrors(mm.gray, images, footprints, mm.mask, gates);
    if (evidence.empty()) {
        LayerDecision d;
        d.reason = reason;
        return d;
    }

    for (size_t i = 0; i < available.size(); ++i) {
        evidence[i].layer = available[i]->layer();
    }
    std::sort(evidence.begin(), evidence.end(), [](const LayerScore& a, const LayerScore& b) {
        return a.error < b.error;
    });

    const LayerScore& best = evidence[0];
    const LayerScore& runner = evidence[1];
    const double margin = runner.error - best.error;

    LayerDecision d;
    d.error = best.error;
    d.margin = margin;
    d.evidence = evidence;
    if (best.error > gates.max_layer_error
        || margin < gates.min_layer_margin
        || best.correlation < gates.min_layer_correlation
        || best.active_pixels < gates.min_active_pixels) {
        d.reason = "weak or ambiguous floor appearance";
        return d;
    }

    d.layer = best.layer;
    d.mode = "candidate";
    d.reason = "appearance";
    return d;
}

void LayerLatch::reset() {
    has_last_ = false;
    last_layer_.clear();
    last_time_s_ = 0.0;
}

LayerDecision LayerLatch::update(LayerDecision decision, double timestamp_s) {
    if (decision.layer.empty()) {
        reset();
        return decision;
    }
    const bool confirmed = has_last_
        && last_layer_ == decision.layer
        && (timestamp_s - last_time_s_) > 0.0
        && (timestamp_s - last_time_s_) <= 2.0;

    has_last_ = true;
    last_layer_ = decision.layer;
    last_time_s_ = timestamp_s;

    if (confirmed) {
        decision.mode = "known";
    } else {
        decision.layer.clear();
        decision.mode = "pending";
        decision.reason = "waiting for second consistent layer observation";
    }
    return decision;
}

SplitTracker::SplitTracker(
    std::vector<std::shared_ptr<LayerReference>> models,
    double unit_scale,
    SplitGates gates
)
    : models_(std::move(models)),
      unit_(unit_scale),
      gates_(gates) {
    reset();
}

void SplitTracker::reset() {
    has_hint_ = false;
    has_last_t_ = false;
    has_pending_ = false;
    failures_ = 0;
    layer_latch_.reset();
}

TrackingStepResult SplitTracker::update(const MinimapCrop& mm, double timestamp_s) {
    const double dt = has_last_t_ ? (timestamp_s - last_t_) : 0.0;
    if (dt > 2.0) {
        has_hint_ = false;
    }

    std::vector<cv::Point2f> query_points;
    cv::Mat query_descriptors;
    extractQueryFeatures(mm, 2, query_points, query_descriptors);

    const double radius = std::max(30.0, 35.0 * std::max(0.0, dt));
    const std::string search = has_hint_ ? "local" : "global";

    std::vector<PositionFix> positions;
    for (const auto& model : models_) {
        if (has_hint_ && model->space() != hint_.space) {
            continue;
        }
        const PositionFix* hint_ptr = has_hint_ ? &hint_ : nullptr;
        Registration reg;
        if (model->registerQuery(query_points, query_descriptors, mm, unit_, hint_ptr, radius, reg)) {
            PositionFix fix;
            if (positionFromRegistration(*model, reg, mm, gates_, fix)) {
                positions.push_back(fix);
            }
        }
    }

    PositionFix best;
    auto [has_best, mode] = choosePosition(positions, gates_, best);
    TrackingStepResult out;
    out.search_mode = search;
    out.candidates = positions;

    if (!has_best) {
        has_pending_ = false;
        ++failures_;
        if (failures_ >= 3) {
            has_hint_ = false;
        }
        layer_latch_.reset();
        out.has_position = false;
        out.position_mode = mode;
        out.layer.reason = "no confirmed position";
        return out;
    }

    const bool continuous = has_hint_
        && best.space == hint_.space
        && std::hypot(best.x - hint_.x, best.y - hint_.y) <= radius;

    bool confirmed = false;
    if (has_pending_) {
        const double elapsed = timestamp_s - pending_t_;
        confirmed = (best.space == pending_.space)
            && (elapsed > 0.0 && elapsed <= 2.0)
            && (std::hypot(best.x - pending_.x, best.y - pending_.y) <= 35.0 * elapsed + 2.0);
    }

    if (!(continuous || confirmed)) {
        has_pending_ = true;
        pending_ = best;
        pending_t_ = timestamp_s;
        ++failures_;
        if (failures_ >= 3) {
            has_hint_ = false;
        }
        layer_latch_.reset();
        out.has_position = false;
        out.position_mode = "pending";
        out.layer.reason = "position not confirmed";
        return out;
    }

    has_hint_ = true;
    hint_ = best;
    has_last_t_ = true;
    last_t_ = timestamp_s;
    has_pending_ = false;
    failures_ = 0;

    out.has_position = true;
    out.position = best;
    out.position_mode = continuous ? "track" : "found";
    out.layer = layer_latch_.update(classifyLayer(mm, best, models_, gates_), timestamp_s);
    return out;
}

} // namespace luxin
