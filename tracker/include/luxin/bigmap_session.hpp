#pragma once

#include "luxin/bigmap_geometry.hpp"

#include <array>
#include <future>

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
    std::vector<cv::Point2f> debug_points; // accepted inlier destinations in working-image pixels, max 8x6
    bool geometry_accepted = false; // all sparse gates passed; NOT permission to publish
};

// Current follow-image coordinates; transform maps small-anchor -> small-current.
// Only fully supported INTER_LINEAR samples of map-only pixels are valid. This is
// independent of image differences: it must never hide a newly changed map region.
cv::Mat bigMapFollowComparisonMask(cv::Size size, const cv::Mat& anchor_to_current);

struct BigMapSessionResult {
    BigMapSessionResult() : snapshot_revision(0) {}
    BigMapGeometry geometry;
    std::string phase = "acquiring";
    bool absolute_attempted = false;
    const char* refresh = "none"; // started, adopted, discarded, conflict
    bool measured_motion = false; // accepted current-frame follow of a previously confirmed anchor
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
    bool appearance_suspended = false; // internal candidate retained, no geometry output
    int suspended_still_frames = 0;
    // Scheduling evidence only, fixed settling-origin -> current; never composed/published.
    BigMapFollowStats acquisition_follow;
    double acquisition_follow_ms = 0.0;
    double acquisition_movement_px = -1.0;
    int acquisition_still_frames = 0;
    double acquisition_still_seconds = 0.0;
    // Ревизия снимка: позволяет вызывающему сверить, что снимок не устарел.
    int snapshot_revision = 0;
};

// Synchronous, diagnostic-only observer immediately BEFORE registerBigMap can block.
// Context is borrowed only until update returns. Must not mutate/re-enter the session,
// retain references to the result, or throw; observer output never controls decisions.
using BigMapSessionDiagnosticSink = void (*)(const BigMapSessionResult&, void*) noexcept;

// Diagnostics only. No floor decision or permission to render item markers.
// Published geometry uses a direct absolute-anchor follow, never chained. Separate
// fixed settling-origin transforms may schedule SIFT but never contribute to geometry.
class BigMapSession {
public:
    using RefreshSearch = BigMapGeometry (*)(const cv::Mat&,
        const std::vector<std::shared_ptr<LayerReference>>&, double, BigMapRegistrationStats*);
    // Only the live owner opts in. Replay without a worker retains deterministic capture-time semantics.
    explicit BigMapSession(RefreshSearch refresh_search = nullptr) : refresh_search_(refresh_search) {}
    void reset();
    BigMapSessionResult update(const cv::Mat& frame,
        const std::vector<std::shared_ptr<LayerReference>>& references, double timestamp_seconds,
        BigMapSessionDiagnosticSink before_absolute = nullptr, void* diagnostic_context = nullptr,
        bool debug_points = false);

    // Атомарный снимок текущего состояния без запуска нового вычисления.
    // Возвращает копию снимка на момент вызова; вызывающий может сравнить
    // snapshot_revision() до и после, чтобы убедиться, что состояние не изменилось.
    BigMapSessionResult snapshot() const;
    // Текущая ревизия снимка; инкрементируется при каждом обновлении состояния
    // (успешном follow, ресете, абсолютной регистрации, истечении).
    int snapshot_revision() const;

private:
    friend struct BigMapSessionTestAccess; // synchronize deterministic tests at future readiness
    struct Renewal {
        BigMapGeometry absolute, expected;
        cv::Mat gray;
        std::vector<cv::KeyPoint> points;
        cv::Mat descriptors;
    };
    void renew(const cv::Mat& frame, const cv::Mat& small, const std::vector<std::shared_ptr<LayerReference>>& refs,
               BigMapSessionResult& result, bool debug_points);
    RefreshSearch refresh_search_;
    std::future<Renewal> renewal_;
    bool renewal_valid_ = false;
    bool renewal_can_adopt_ = false;
    bool confirmed_anchor_ = false;
    double last_refresh_ = -1;
    // Опубликованная абсолютная привязка (регистрация registerBigMap). Не перезаписывается follow'ом.
    BigMapGeometry geometry_anchor_;

    // ORB-признаки кадра абсолютной регистрации — фиксированы, не передвигаются на каждый follow-кадр.
    cv::Mat anchor_gray_;
    std::vector<cv::KeyPoint> anchor_keypoints_;
    cv::Mat anchor_descriptors_;
    BigMapGeometry anchor_;
    cv::Mat previous_transform_; // последний transform от кадра абсолютной регистрации к текущему
    double previous_timestamp_ = -1.0;
    double last_attempt_ = -1.0;
    cv::Size frame_size_;
    int stable_frames_ = 0;
    bool appearance_suspended_ = false;
    int suspended_still_frames_ = 0;

    // Счётчик последовательных неудач follow перед отказом от анкора.
    int not_ok_counter_ = 0;
    static constexpr int MAX_NOT_OK = 3;

    // Ревизия состояния сессии; инкрементируется при любом изменении (успешном follow,
    // ресете, абсолютной регистрации, истечении). Используется для проверки, что
    // снимок не устарел.
    int snapshot_revision_ = 0;

    cv::Mat acquisition_gray_;
    std::vector<cv::KeyPoint> acquisition_keypoints_;
    cv::Mat acquisition_descriptors_;
    double acquisition_timestamp_ = -1.0;
    double acquisition_last_timestamp_ = -1.0;
    int acquisition_still_frames_ = 0;
};

} // namespace luxin
