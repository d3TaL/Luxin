#include "luxin/split_tracker.hpp"

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

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
    double unit_scale = 0.5625;
};

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
    cv::Mat rgba(static_cast<int>(info.height), static_cast<int>(info.width), CV_8UC4, pixels, info.stride);
    cv::cvtColor(rgba, out_bgr, cv::COLOR_RGBA2BGR);
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

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_d3tal_luxin_tracker_NativeSplitTracker_nativeCreate(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jdouble unit_scale
) {
    auto* ctx = new NativeTrackerContext();
    ctx->unit_scale = static_cast<double>(unit_scale);
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
    jfloatArray excluded_circles_xyr
) {
    auto* ctx = reinterpret_cast<NativeTrackerContext*>(handle);
    if (!ctx || !frame_bitmap) {
        return env->NewStringUTF("{\"error\":\"null context or bitmap\"}");
    }

    cv::Mat frame_bgr;
    if (!bitmapToBgrMat(env, frame_bitmap, frame_bgr)) {
        return env->NewStringUTF("{\"error\":\"failed to lock RGBA_8888 bitmap\"}");
    }

    std::vector<luxin::MaskCircle> circles;
    if (excluded_circles_xyr != nullptr) {
        jsize len = env->GetArrayLength(excluded_circles_xyr);
        jfloat* elems = env->GetFloatArrayElements(excluded_circles_xyr, nullptr);
        if (elems != nullptr) {
            for (jsize i = 0; i + 2 < len; i += 3) {
                circles.push_back(luxin::MaskCircle{elems[i], elems[i + 1], elems[i + 2]});
            }
            env->ReleaseFloatArrayElements(excluded_circles_xyr, elems, JNI_ABORT);
        }
    }

    try {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        if (!ctx->tracker || ctx->models.empty()) {
            return env->NewStringUTF("{\"error\":\"no reference models loaded\"}");
        }
        auto spec = luxin::MinimapSpec::forFrameHeight(frame_bgr.rows);
        auto mm = luxin::MinimapCrop::fromBgrFrame(frame_bgr, spec, circles);
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
        std::string msg = std::string("{\"error\":\"") + e.what() + "\"}";
        return env->NewStringUTF(msg.c_str());
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
