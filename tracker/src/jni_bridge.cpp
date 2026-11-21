#include "luxin/split_tracker.hpp"
#include "luxin/bigmap_detector.hpp"
#include "luxin/bigmap_geometry.hpp"
#include "luxin/bigmap_session.hpp"

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <iomanip>
#include <locale>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LuxinTrackerJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LuxinTrackerJNI", __VA_ARGS__)

namespace {

struct NativeTrackerContext {
    std::mutex mutex;
    std::vector<std::shared_ptr<luxin::LayerReference>> models;
    std::unique_ptr<luxin::SplitTracker> tracker;
    luxin::BigMapSession bigmap{luxin::registerBigMap};
    double unit_scale_override = 0.0;
    double unit_scale = 0.75;
    int frame_width = 0;
    int frame_height = 0;
    luxin::MinimapSpec last_spec;
};

// Match fromBgrFrame's padded crop, before any potentially overflowing integer casts.
bool validCrop(const luxin::MinimapSpec& spec, int width, int height) {
    if (width <= 0 || height <= 0 ||
        !std::isfinite(spec.center_x) || !std::isfinite(spec.center_y) ||
        !std::isfinite(spec.radius) || spec.radius <= 0.0 ||
        spec.radius < 6.0 * height / 1440.0 ||
        spec.radius > (std::min(width, height) / 2.0)) {
        return false;
    }
    const double pad = std::floor(spec.radius) + 4.0;
    const double cx = std::round(spec.center_x);
    const double cy = std::round(spec.center_y);
    return cx >= pad && cy >= pad && cx + pad <= width && cy + pad <= height;
}

bool bitmapToBgrMat(JNIEnv* env, jobject bitmap, cv::Mat& out_bgr) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        return false;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return false;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        return false;
    }
    try {
        cv::Mat rgba(static_cast<int>(info.height), static_cast<int>(info.width), CV_8UC4, pixels, info.stride);
        cv::cvtColor(rgba, out_bgr, cv::COLOR_RGBA2BGR);
    } catch (...) {
        AndroidBitmap_unlockPixels(env, bitmap);
        throw;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

std::string jstringToStd(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string res(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(jstr, chars);
    return res;
}

std::string jsonString(const std::string& text) {
    std::ostringstream out;
    out << '"';
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') out << '\\' << c;
        else if (c < 0x20) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << int(c) << std::dec;
        else out << c;
    }
    out << '"';
    return out.str();
}

using DiagnosticClock = std::chrono::steady_clock;

double diagnosticMs(DiagnosticClock::time_point start) noexcept {
    return std::chrono::duration<double, std::milli>(DiagnosticClock::now() - start).count();
}

struct BigMapLogContext {
    double timestamp;
    int width;
    int height;
    double conversion_ms;
};

// Log only scalar quality/timing data. Never log pixels, reference paths, or JSON.
void logBigMapDiagnostics(const luxin::BigMapSessionResult& result,
    const BigMapLogContext& context, const char* stage, double session_ms = -1.0) noexcept {
    const auto& f = result.follow;
    LOGI("BigMap stage=%s ts=%.9f frame=%dx%d phase=%s mode=%s absolute=%d throttled=%d "
        "reset=%s anchorExpired=%d anchorAgeS=%.3f gapS=%.3f sinceAttemptS=%.3f "
        "follow=%s sparseOk=%d suspended=%d suspendedStill=%d followMs=%.1f movementPx=%.3f stable=%d convertMs=%.1f "
        "features=%.1f match=%.1f validate=%.1f ms keypoints=%d sessionMs=%.1f "
        "settle=%s settleMovementPx=%.3f settleStill=%d settleSpanS=%.3f settleMs=%.1f refresh=%s measuredMotion=%d "
        "tolerated=%d",
        stage, context.timestamp, context.width, context.height, result.phase.c_str(),
        result.geometry.mode.c_str(), result.absolute_attempted, result.absolute_throttled,
        result.reset_reason, result.anchor_expired, result.anchor_age_seconds,
        result.observation_gap_seconds, result.since_attempt_seconds, f.outcome,
        f.geometry_accepted, result.appearance_suspended, result.suspended_still_frames,
        result.follow_ms, result.movement_px, result.stable_frames, context.conversion_ms,
        result.registration.features_ms, result.registration.matching_ms,
        result.registration.validation_ms, result.registration.query_features, session_ms,
        result.acquisition_follow.outcome, result.acquisition_movement_px,
        result.acquisition_still_frames, result.acquisition_still_seconds, result.acquisition_follow_ms,
        result.refresh, result.measured_motion, result.appearance_tolerated);
    if (f.current_keypoints < 0) return; // follow was not attempted
    LOGI("BigMapFollow stage=%s ts=%.9f outcome=%s work=%dx%d kp=%d/%d desc=%d/%d "
        "matches=%d inliers=%d ratio=%.5f grid=%d errorPx=%.4f scale=%.5f angleDeg=%.4f "
        "hullFrac=%.5f overlap=%d overlapFrac=%.5f denseChanged=%d denseFrac=%.5f "
        "tilesVisited=%d worstVisitedTile=%d,%d,%d,%d tileChanged=%d tileValid=%d tileFrac=%.5f",
        stage, context.timestamp, f.outcome, f.working_size.width, f.working_size.height,
        f.anchor_keypoints, f.current_keypoints, f.anchor_descriptors, f.current_descriptors,
        f.matches, f.inliers, f.inlier_ratio, f.grid_cells, f.mean_error_px, f.scale, f.angle_degrees,
        f.hull_fraction, f.overlap_pixels, f.overlap_fraction, f.changed_pixels, f.changed_fraction,
        f.tiles_evaluated, f.worst_tile.x, f.worst_tile.y, f.worst_tile.width, f.worst_tile.height,
        f.worst_tile_changed_pixels, f.worst_tile_valid_pixels, f.worst_tile_changed_fraction);
    for (size_t i = 0; i < f.controls.size(); ++i) {
        const auto& c = f.controls[i];
        LOGI("BigMapControl stage=%s ts=%.9f roi=%d rect=%d,%d,%d,%d mean=%.4f changed=%d pixels=%d frac=%.5f",
            stage, context.timestamp, static_cast<int>(i), c.rect.x, c.rect.y, c.rect.width, c.rect.height,
            c.mean_delta, c.changed_pixels, c.pixels, c.changed_fraction);
    }
}

void logBeforeBigMapAbsolute(const luxin::BigMapSessionResult& result, void* context) noexcept {
    logBigMapDiagnostics(result, *static_cast<const BigMapLogContext*>(context), "before_absolute");
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_github_d3tal_luxin_tracker_NativeSplitTracker_nativeRegisterBigMapV2(
    JNIEnv* env, jobject, jlong handle, jobject bitmap, jdouble timestamp, jboolean debug_points
) {
    auto* ctx = reinterpret_cast<NativeTrackerContext*>(handle);
    if (!ctx) {
        LOGI("BigMapV2 stage=error ts=%.9f reason=closed_tracker", timestamp);
        return env->NewStringUTF("{\"error\":\"closed tracker\"}");
    }
    std::lock_guard<std::mutex> lock(ctx->mutex);
    if (!bitmap) {
        ctx->bigmap.reset();
        LOGI("BigMapV2 stage=error ts=%.9f reset=null_bitmap", timestamp);
        return env->NewStringUTF("{\"error\":\"null bitmap\"}");
    }
    try {
        const auto conversion_start = DiagnosticClock::now();
        cv::Mat bgr;
        if (!bitmapToBgrMat(env, bitmap, bgr)) {
            ctx->bigmap.reset();
            LOGI("BigMapV2 stage=error ts=%.9f reset=bitmap_read_failed", timestamp);
            return env->NewStringUTF("{\"error\":\"cannot read bitmap\"}");
        }
        BigMapLogContext diagnostics{timestamp, bgr.cols, bgr.rows, diagnosticMs(conversion_start)};
        const auto session_start = DiagnosticClock::now();
        const auto result = ctx->bigmap.update(bgr, ctx->models, timestamp, logBeforeBigMapAbsolute, &diagnostics, debug_points);
        const double session_ms = diagnosticMs(session_start);
        const auto& g = result.geometry;
        logBigMapDiagnostics(result, diagnostics, "return", session_ms);
        std::ostringstream out;
        out.imbue(std::locale::classic());
        out << std::setprecision(17) << "{\"mode\":" << jsonString(g.mode)
            << ",\"phase\":" << jsonString(result.phase)
            << ",\"version\":2,\"measuredMotion\":" << (result.measured_motion ? "true" : "false");
        if (g.mode == "found") {
            out << ",\"v2\":{"
                << "\"space\":" << jsonString(g.space)
                << ",\"reference\":" << jsonString(g.reference)
                << ",\"frameWidth\":" << g.frame_width
                << ",\"frameHeight\":" << g.frame_height
                << ",\"timestampSeconds\":" << g.timestamp_seconds
                << ",\"inliers\":" << g.inliers
                << ",\"matches\":" << g.matches
                << ",\"errorPx\":" << g.error_px
                << ",\"support\":" << g.support
                << ",\"matrix\":[";
            for (int i = 0; i < 6; ++i) {
                if (i) out << ',';
                out << g.matrix.at<double>(i / 3, i % 3);
            }
            out << ']';
            if (debug_points && (result.phase == "tracking" || result.measured_motion) && !result.follow.debug_points.empty()) {
                out << ",\"framePoints\":[";
                bool first = true;
                for (const auto p : result.follow.debug_points) {
                    if (!first) out << ',';
                    first = false;
                    out << p.x * double(g.frame_width) / result.follow.working_size.width << ','
                        << p.y * double(g.frame_height) / result.follow.working_size.height;
                }
                out << ']';
            }
            out << "}";
        }
        out << '}';
        return env->NewStringUTF(out.str().c_str());
    } catch (const std::exception&) {
        ctx->bigmap.reset();
        LOGI("BigMapV2 stage=error ts=%.9f reset=registration_exception", timestamp);
        return env->NewStringUTF("{\"error\":\"big-map registration failed\"}");
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_d3tal_luxin_tracker_NativeSplitTracker_nativeSnapshotBigMap(
    JNIEnv* env, jobject /*thiz*/, jlong handle
) {
    auto* ctx = reinterpret_cast<NativeTrackerContext*>(handle);
    if (!ctx) {
        LOGI("BigMapSnapshot stage=error reason=closed_tracker");
        return env->NewStringUTF("{\"error\":\"closed tracker\"}");
    }
    std::lock_guard<std::mutex> lock(ctx->mutex);
    const auto result = ctx->bigmap.snapshot();
    const auto& g = result.geometry;
    std::ostringstream out;
    out.imbue(std::locale::classic());
    out << std::setprecision(17) << "{\"mode\":" << jsonString(g.mode)
        << ",\"phase\":" << jsonString(result.phase)
        << ",\"version\":2"
        << ",\"snapshot_revision\":" << result.snapshot_revision;
    if (g.mode == "found") {
        out << ",\"v2\":{"
            << "\"space\":" << jsonString(g.space)
            << ",\"reference\":" << jsonString(g.reference)
            << ",\"frameWidth\":" << g.frame_width
            << ",\"frameHeight\":" << g.frame_height
            << ",\"timestampSeconds\":" << g.timestamp_seconds
            << ",\"inliers\":" << g.inliers
            << ",\"matches\":" << g.matches
            << ",\"errorPx\":" << g.error_px
            << ",\"support\":" << g.support
            << ",\"matrix\":[";
        for (int i = 0; i < 6; ++i) {
            if (i) out << ',';
            out << g.matrix.at<double>(i / 3, i % 3);
        }
        out << ']';
        out << "}";
    }
    out << '}';
    return env->NewStringUTF(out.str().c_str());
}

JNIEXPORT jboolean JNICALL
Java_io_github_d3tal_luxin_tracker_NativeSplitTracker_nativeIsBigMap(
    JNIEnv* env, jobject /*thiz*/, jobject bitmap
) {
    try {
        cv::Mat bgr;
        if (!bitmapToBgrMat(env, bitmap, bgr)) {
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Cannot read scene bitmap");
            return JNI_FALSE;
        }
        return luxin::detectBigMap(bgr).detected ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jlong JNICALL
Java_io_github_d3tal_luxin_tracker_NativeSplitTracker_nativeCreate(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jdouble unit_scale
) {
    auto* ctx = new NativeTrackerContext();
    if (std::isfinite(unit_scale) && unit_scale > 0.0) {
        ctx->unit_scale_override = unit_scale;
        ctx->unit_scale = unit_scale;
    }
    ctx->tracker = std::make_unique<luxin::SplitTracker>(ctx->models, ctx->unit_scale);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_io_github_d3tal_luxin_tracker_NativeSplitTracker_nativeDestroy(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* ctx = reinterpret_cast<NativeTrackerContext*>(handle);
    delete ctx;
    LOGI("BigMap stage=reset reason=tracker_destroy");
}

JNIEXPORT void JNICALL
Java_io_github_d3tal_luxin_tracker_NativeSplitTracker_nativeReset(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* ctx = reinterpret_cast<NativeTrackerContext*>(handle);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    if (ctx->tracker) {
        ctx->tracker->reset();
    }
    ctx->bigmap.reset();
    LOGI("BigMap stage=reset reason=tracker_reset");
}

JNIEXPORT void JNICALL
Java_io_github_d3tal_luxin_tracker_NativeSplitTracker_nativeResetBigMap(
    JNIEnv*, jobject, jlong handle
) {
    auto* ctx = reinterpret_cast<NativeTrackerContext*>(handle);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    ctx->bigmap.reset();
    LOGI("BigMap stage=reset reason=bigmap_reset");
}

JNIEXPORT jboolean JNICALL
Java_io_github_d3tal_luxin_tracker_NativeSplitTracker_nativeAddLayerReferenceFromFiles(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring jname,
    jstring jspace,
    jstring jlayer,
    jstring jgray_path,
    jstring jactive_mask_path,
    jstring jcontext_mask_path,
    jdouble units_per_px,
    jdouble origin_x,
    jdouble origin_y
) {
    auto* ctx = reinterpret_cast<NativeTrackerContext*>(handle);
    if (!ctx) return JNI_FALSE;

    const std::string name = jstringToStd(env, jname);
    const std::string space = jstringToStd(env, jspace);
    const std::string layer = jstringToStd(env, jlayer);
    const std::string gray_path = jstringToStd(env, jgray_path);
    const std::string active_path = jstringToStd(env, jactive_mask_path);
    const std::string context_path = jstringToStd(env, jcontext_mask_path);

    try {
        cv::Mat gray = cv::imread(gray_path, cv::IMREAD_GRAYSCALE);
        cv::Mat active = cv::imread(active_path, cv::IMREAD_GRAYSCALE);
        cv::Mat context = cv::imread(context_path, cv::IMREAD_GRAYSCALE);
        if (gray.empty() || active.empty() || context.empty()) {
            LOGE("Failed to load reference files for %s", layer.c_str());
            return JNI_FALSE;
        }
        auto ref = std::make_shared<luxin::LayerReference>(
            name, space, layer, gray, active, context,
            static_cast<double>(units_per_px),
            static_cast<double>(origin_x),
            static_cast<double>(origin_y)
        );
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->models.push_back(std::move(ref));
        ctx->bigmap.reset();
        LOGI("BigMap stage=reset reason=reference_added");
        ctx->tracker = std::make_unique<luxin::SplitTracker>(ctx->models, ctx->unit_scale);
        LOGI("Added layer reference %s/%s (total=%zu)", space.c_str(), layer.c_str(), ctx->models.size());
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Exception in nativeAddLayerReferenceFromFiles: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_d3tal_luxin_tracker_NativeSplitTracker_nativeUpdateFrame(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobject frame_bitmap,
    jdouble timestamp_s,
    jfloatArray excluded_circles_xyr,
    jfloatArray ring_xyr
) {
    auto* ctx = reinterpret_cast<NativeTrackerContext*>(handle);
    if (!ctx || !frame_bitmap) {
        return env->NewStringUTF("{\"error\":\"null context or bitmap\"}");
    }

    try {
        cv::Mat frame_bgr;
        if (!bitmapToBgrMat(env, frame_bitmap, frame_bgr)) {
            return env->NewStringUTF("{\"error\":\"failed to lock RGBA_8888 bitmap\"}");
        }

        auto spec = luxin::MinimapSpec::forFrameHeight(frame_bgr.rows);
        if (ring_xyr != nullptr) {
            if (env->GetArrayLength(ring_xyr) != 3) {
                return env->NewStringUTF("{\"error\":\"invalid minimap ring\"}");
            }
            jfloat ring[3];
            env->GetFloatArrayRegion(ring_xyr, 0, 3, ring);
            if (env->ExceptionCheck()) return nullptr;
            spec.center_x = ring[0];
            spec.center_y = ring[1];
            spec.radius = ring[2];
        }
        if (!validCrop(spec, frame_bgr.cols, frame_bgr.rows)) {
            return env->NewStringUTF("{\"error\":\"invalid minimap ring or crop bounds\"}");
        }

        std::vector<luxin::MaskCircle> circles;
        if (excluded_circles_xyr != nullptr) {
            const jsize len = env->GetArrayLength(excluded_circles_xyr);
            if (len % 3 != 0) {
                return env->NewStringUTF("{\"error\":\"invalid excluded circles\"}");
            }
            std::vector<jfloat> elems(len);
            if (len > 0) {
                env->GetFloatArrayRegion(excluded_circles_xyr, 0, len, elems.data());
                if (env->ExceptionCheck()) return nullptr;
            }
            // Keep fromBgrFrame's rounding and integer circle arithmetic in range.
            const double limit = std::numeric_limits<int>::max() / 4.0;
            for (jsize i = 0; i < len; i += 3) {
                if (!std::isfinite(elems[i]) || !std::isfinite(elems[i + 1]) ||
                    !std::isfinite(elems[i + 2]) || std::abs(elems[i]) > limit ||
                    std::abs(elems[i + 1]) > limit || elems[i + 2] < 0.0f ||
                    elems[i + 2] > limit) {
                    return env->NewStringUTF("{\"error\":\"invalid excluded circles\"}");
                }
                circles.push_back(luxin::MaskCircle{elems[i], elems[i + 1], elems[i + 2]});
            }
        }

        std::lock_guard<std::mutex> lock(ctx->mutex);
        if (!ctx->tracker || ctx->models.empty()) {
            return env->NewStringUTF("{\"error\":\"no reference models loaded\"}");
        }
        auto mm = luxin::MinimapCrop::fromBgrFrame(frame_bgr, spec, circles);
        if (ctx->frame_width != frame_bgr.cols || ctx->frame_height != frame_bgr.rows ||
            ctx->last_spec.center_x != spec.center_x ||
            ctx->last_spec.center_y != spec.center_y || ctx->last_spec.radius != spec.radius) {
            const double scale = ctx->unit_scale_override > 0.0
                ? ctx->unit_scale_override : 0.75 * frame_bgr.rows / 1440.0;
            // Recreate only tracking state; shared reference models remain loaded.
            ctx->tracker = std::make_unique<luxin::SplitTracker>(ctx->models, scale);
            ctx->unit_scale = scale;
            ctx->frame_width = frame_bgr.cols;
            ctx->frame_height = frame_bgr.rows;
            ctx->last_spec = spec;
        }
        auto res = ctx->tracker->update(mm, static_cast<double>(timestamp_s));

        std::ostringstream os;
        os << "{"
           << "\"hasPosition\":" << (res.has_position ? "true" : "false") << ","
           << "\"positionMode\":\"" << res.position_mode << "\","
           << "\"searchMode\":\"" << res.search_mode << "\","
           << "\"space\":\"" << (res.has_position ? res.position.space : "") << "\","
           << "\"x\":" << (res.has_position ? res.position.x : 0.0) << ","
           << "\"y\":" << (res.has_position ? res.position.y : 0.0) << ","
           << "\"scale\":" << (res.has_position ? res.position.scale : 0.0) << ","
           << "\"rotation\":" << (res.has_position ? res.position.rotation : 0.0) << ","
           << "\"support\":" << (res.has_position ? res.position.support : 0.0) << ","
           << "\"inliers\":" << (res.has_position ? res.position.inliers : 0) << ","
           << "\"layer\":\"" << res.layer.layer << "\","
           << "\"layerMode\":\"" << res.layer.mode << "\","
           << "\"layerReason\":\"" << res.layer.reason << "\","
           << "\"layerError\":" << res.layer.error << ","
           << "\"layerMargin\":" << res.layer.margin << ","
           << "\"overlayReady\":" << (res.overlayReady() ? "true" : "false")
           << "}";
        return env->NewStringUTF(os.str().c_str());
    } catch (const std::exception& e) {
        LOGE("Exception in nativeUpdateFrame: %s", e.what());
        return env->NewStringUTF("{\"error\":\"native frame update failed\"}");
    } catch (...) {
        return env->NewStringUTF("{\"error\":\"native frame update failed\"}");
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_d3tal_luxin_tracker_NativeSplitTracker_nativeRunSelfTest(
    JNIEnv* env,
    jobject /*thiz*/
) {
    try {
        luxin::SplitGates gates;
        luxin::PositionFix p1{"twt702", 50.0, 50.0, 1.125, 0.0, 0.5, 12, 0.3, 0.80};
        luxin::PositionFix p2{"twt702", 50.2, 50.0, 1.125, 0.0, 0.5, 12, 0.3, 0.81};
        luxin::PositionFix best;
        auto [ok, mode] = luxin::choosePosition({p1, p2}, gates, best);
        if (!ok || mode != "candidate") {
            return env->NewStringUTF("FAIL: choosePosition");
        }
        return env->NewStringUTF("OK: C++17 SplitTracker + OpenCV 4.10 SIFT JNI ready");
    } catch (const std::exception& e) {
        return env->NewStringUTF(e.what());
    }
}

} // extern "C"
