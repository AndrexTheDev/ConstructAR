// JNI surface of :native-slam.
//
// Contract with com.constructar.nativebridge.NativeSlamEngine (keep the two in sync):
//
//   nativeCreate(voxelSize, truncationVoxels, maxBlocks, workerThreads, minConfidence)
//   nativeDestroy(handle)
//   nativeReset(handle)
//   nativeIntegrateDepth(handle, depthShorts, w, h, rowStrideElements,
//                        confidenceBytes, confW, confH, confRowStride,
//                        fx, fy, cx, cy, cameraToWorld[16], timestampNs, pixelStep) -> IntArray
//   nativeIntegrateDepthBuffer(handle, depthDirect, ...same...) -> IntArray   (zero-copy)
//   nativeIntegrateDepthHardwareBuffer(handle, hwBuffer, ...) -> IntArray   (API 26+, may fail)
//   nativeLastBounds(handle) -> FloatArray[6]
//   nativeExtractMesh(handle) -> Array<Any> { FloatArray, IntArray }       (see below)
//   nativeReset(handle)
//   nativePoseGraphReset(handle)
//   nativePoseGraphAddNode(handle, pose[16], fixed)
//   nativePoseGraphAddEdge(handle, from, to, measurement[16], rotWeight, transWeight, loop)
//   nativePoseGraphOptimize(handle, iterations) -> FloatArray (report + flattened poses)
//   nativeLoopCandidates(handle) -> FloatArray (triples: from, to, distance)
//
// Mesh payload: FloatArray = [vertexCount, triangleCount, positions(3n), normals(3n)],
//               IntArray   = indices(3t). Both arrays come from the same snapshot, so they
//               are always consistent (unlike two separate JNI calls).
#include <jni.h>

#include <algorithm>

#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>

#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

#include "../slam/pose_graph.hpp"
#include "../slam/rtabmap_bridge.hpp"
#include "../tsdf/volume.hpp"

#define LOG_TAG "ConstructARSlam"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using constructar::DepthFrameView;
using constructar::Mat4;
using constructar::MeshBuffers;
using constructar::PoseGraph;
using constructar::RtabMapBridge;
using constructar::SlamUpdate;
using constructar::TsdfConfig;
using constructar::TsdfVolume;

struct Bounds {
  float minX = 0.f, minY = 0.f, minZ = 0.f;
  float maxX = 0.f, maxY = 0.f, maxZ = 0.f;
  bool valid = false;
};

/** Everything owned by one Kotlin NativeSlamEngine instance. */
struct EngineContext {
  std::unique_ptr<TsdfVolume> volume;
  PoseGraph poseGraph;
  RtabMapBridge rtabMap;
  Bounds lastBounds;
  int lastTriangleCount = 0;
  /**
   * Serialises *every* access to the volume.
   *
   * The Kotlin pipeline already funnels integration and extraction onto one worker thread, but
   * `reset()` arrives from the UI thread (a new room was started) and `destroy()` from whatever
   * thread closed the engine. Without this lock a reset racing an integration corrupts the
   * block table silently — the kind of bug that shows up as "the scan went weird" days later.
   */
  std::mutex volumeMutex;
};

EngineContext* contextFrom(jlong handle) { return reinterpret_cast<EngineContext*>(handle); }

void copyMatrix(JNIEnv* env, jfloatArray array, float out[16]) {
  if (array == nullptr) {
    std::memset(out, 0, sizeof(float) * 16);
    out[0] = out[5] = out[10] = out[15] = 1.f;
    return;
  }
  env->GetFloatArrayRegion(array, 0, 16, out);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativeCreate(JNIEnv*, jobject, jfloat voxelSize,
                                                               jfloat truncationVoxels,
                                                               jint maxBlocks, jint workerThreads,
                                                               jfloat minConfidence) {
  auto context = std::make_unique<EngineContext>();
  TsdfConfig config;
  config.voxelSize = voxelSize;
  config.truncationVoxels = truncationVoxels;
  config.maxBlocks = static_cast<size_t>(maxBlocks > 0 ? maxBlocks : 160000);
  config.workerThreads = workerThreads;
  config.minConfidence = static_cast<uint8_t>(minConfidence < 0.f ? 0.f : (minConfidence > 255.f ? 255.f : minConfidence));
  context->volume = std::make_unique<TsdfVolume>(config);
  LOGI("TSDF created: voxel=%.3f m trunc=%.1f vox maxBlocks=%d threads=%d",
       static_cast<double>(voxelSize), static_cast<double>(truncationVoxels),
       static_cast<int>(config.maxBlocks), workerThreads);
  return reinterpret_cast<jlong>(context.release());
}

JNIEXPORT void JNICALL Java_com_constructar_nativebridge_NativeSlamEngine_nativeDestroy(JNIEnv*,
                                                                                       jobject,
                                                                                       jlong handle) {
  delete contextFrom(handle);
}

JNIEXPORT void JNICALL Java_com_constructar_nativebridge_NativeSlamEngine_nativeReset(JNIEnv*,
                                                                                     jobject,
                                                                                     jlong handle) {
  EngineContext* context = contextFrom(handle);
  if (context == nullptr) return;
  std::lock_guard<std::mutex> lock(context->volumeMutex);
  context->volume->reset();
  context->lastBounds = Bounds{};
  context->lastTriangleCount = 0;
}

/** Shared implementation for the ByteBuffer and AHardwareBuffer ingestion paths. */
static jintArray integrateCommon(JNIEnv* env, EngineContext* context, const uint16_t* depth,
                                 jint width, jint height, jint rowStrideElements,
                                 const uint8_t* confidence, jint confidenceWidth,
                                 jint confidenceHeight, jint confidenceRowStride, jfloat fx,
                                 jfloat fy, jfloat cx, jfloat cy, jfloatArray cameraToWorld,
                                 jlong timestampNs, jint pixelStep) {
  if (context == nullptr || depth == nullptr || width <= 0 || height <= 0) return nullptr;

  DepthFrameView frame;
  frame.depth = depth;
  frame.width = width;
  frame.height = height;
  frame.rowStrideElements = rowStrideElements > 0 ? rowStrideElements : width;
  frame.confidence = confidence;
  frame.confidenceWidth = confidenceWidth;
  frame.confidenceHeight = confidenceHeight;
  frame.confidenceRowStride = confidenceRowStride;
  frame.intrinsics = constructar::CameraIntrinsics{fx, fy, cx, cy};
  float matrix[16];
  copyMatrix(env, cameraToWorld, matrix);
  for (int i = 0; i < 16; ++i) frame.cameraToWorld.m[i] = matrix[i];
  frame.timestampNs = timestampNs;

  constructar::IntegrationStats stats;
  {
    std::lock_guard<std::mutex> lock(context->volumeMutex);
    stats = context->volume->integrate(frame, pixelStep);
  }

  jint values[8] = {
      stats.raysProcessed,
      stats.voxelUpdates,
      stats.blocksAllocated,
      stats.blocksTotal,
      stats.hitBlockBudget ? 1 : 0,
      static_cast<jint>(stats.milliseconds),
      static_cast<jint>(context->volume->voxelCount()),
      static_cast<jint>(context->volume->truncationMeters() * 1000.f),
  };
  jintArray result = env->NewIntArray(8);
  if (result != nullptr) env->SetIntArrayRegion(result, 0, 8, values);
  return result;
}

/**
 * Primary path: a Kotlin `ShortArray` (pooled by `DepthBufferPool`).
 *
 * `GetShortArrayElements` pins the Java array, so this is a zero-copy view on ART for the
 * duration of the integration — the only copy is the one `DepthCapture` already did on the
 * render thread to survive `Frame.update()`. Use this overload unless ARCore hands you a
 * direct `ByteBuffer`/`AHardwareBuffer`.
 */
JNIEXPORT jintArray JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativeIntegrateDepth(
    JNIEnv* env, jobject, jlong handle, jshortArray depthArray, jint width, jint height,
    jint rowStrideElements, jbyteArray confidenceArray, jint confidenceWidth, jint confidenceHeight,
    jint confidenceRowStride, jfloat fx, jfloat fy, jfloat cx, jfloat cy,
    jfloatArray cameraToWorld, jlong timestampNs, jint pixelStep) {
  EngineContext* context = contextFrom(handle);
  if (context == nullptr || depthArray == nullptr) return nullptr;

  jshort* rawDepth = env->GetShortArrayElements(depthArray, nullptr);
  if (rawDepth == nullptr) return nullptr;
  const uint16_t* depth = reinterpret_cast<const uint16_t*>(rawDepth);

  jbyte* rawConfidence = confidenceArray != nullptr
                             ? env->GetByteArrayElements(confidenceArray, nullptr)
                             : nullptr;
  const uint8_t* confidence = reinterpret_cast<const uint8_t*>(rawConfidence);

  jintArray result = integrateCommon(env, context, depth, width, height, rowStrideElements,
                                     confidence, confidenceWidth, confidenceHeight,
                                     confidenceRowStride, fx, fy, cx, cy, cameraToWorld,
                                     timestampNs, pixelStep);

  // JNI_ABORT: we only read the arrays, so the VM must not copy anything back.
  env->ReleaseShortArrayElements(depthArray, rawDepth, JNI_ABORT);
  if (rawConfidence != nullptr) {
    env->ReleaseByteArrayElements(confidenceArray, rawConfidence, JNI_ABORT);
  }
  return result;
}

/** Zero-copy variant for callers that already own a direct `ByteBuffer` (ARCore `Frame`). */
JNIEXPORT jintArray JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativeIntegrateDepthBuffer(
    JNIEnv* env, jobject, jlong handle, jobject depthBuffer, jint width, jint height,
    jint rowStrideElements, jobject confidenceBuffer, jint confidenceWidth, jint confidenceHeight,
    jint confidenceRowStride, jfloat fx, jfloat fy, jfloat cx, jfloat cy,
    jfloatArray cameraToWorld, jlong timestampNs, jint pixelStep) {
  EngineContext* context = contextFrom(handle);
  if (context == nullptr || depthBuffer == nullptr) return nullptr;
  auto* depth = static_cast<const uint16_t*>(env->GetDirectBufferAddress(depthBuffer));
  auto* confidence = confidenceBuffer != nullptr
                         ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(confidenceBuffer))
                         : nullptr;
  if (depth == nullptr) {
    LOGW("Depth buffer is not direct — copy the image into a direct ByteBuffer first");
    return nullptr;
  }
  return integrateCommon(env, context, depth, width, height, rowStrideElements, confidence,
                         confidenceWidth, confidenceHeight, confidenceRowStride, fx, fy, cx, cy,
                         cameraToWorld, timestampNs, pixelStep);
}

/**
 * Zero-copy path: ARCore hands us an AHardwareBuffer (Image.getHardwareBuffer(), API 26+).
 * We lock it for CPU reads instead of copying through a JVM ByteBuffer. Some devices expose
 * the depth buffer as a PRIVATE format that cannot be locked; the caller then falls back to
 * the ByteBuffer path — hence the nullable return.
 */
JNIEXPORT jintArray JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativeIntegrateDepthHardwareBuffer(
    JNIEnv* env, jobject, jlong handle, jobject hardwareBuffer, jint width, jint height,
    jobject confidenceBuffer, jint confidenceWidth, jint confidenceHeight,
    jint confidenceRowStride, jfloat fx, jfloat fy, jfloat cx, jfloat cy,
    jfloatArray cameraToWorld, jlong timestampNs, jint pixelStep) {
  EngineContext* context = contextFrom(handle);
  if (context == nullptr || hardwareBuffer == nullptr) return nullptr;

  AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
  if (buffer == nullptr) return nullptr;

  AHardwareBuffer_Desc description;
  AHardwareBuffer_describe(buffer, &description);
  if (description.format != AHARDWAREBUFFER_FORMAT_BLOB) {
    LOGW("Depth AHardwareBuffer is not a BLOB (format %u) — falling back", description.format);
    return nullptr;
  }

  void* mapped = nullptr;
  const int lockResult = AHardwareBuffer_lock(
      buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OCCASIONALLY, -1, nullptr, &mapped);
  if (lockResult != 0 || mapped == nullptr) {
    LOGW("AHardwareBuffer_lock failed (%d) — falling back to the ByteBuffer path", lockResult);
    return nullptr;
  }

  const uint32_t strideElements = description.stride / 2u;
  auto* depth = static_cast<const uint16_t*>(mapped);
  auto* confidence = confidenceBuffer != nullptr
                         ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(confidenceBuffer))
                         : nullptr;

  jintArray result = integrateCommon(env, context, depth, static_cast<jint>(description.width),
                                     static_cast<jint>(description.height),
                                     static_cast<jint>(strideElements), confidence, confidenceWidth,
                                     confidenceHeight, confidenceRowStride, fx, fy, cx, cy,
                                     cameraToWorld, timestampNs, pixelStep);

  AHardwareBuffer_unlock(buffer, nullptr);
  return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativeExtractMesh(JNIEnv* env, jobject,
                                                                     jlong handle, jfloat isoLevel) {
  EngineContext* context = contextFrom(handle);
  if (context == nullptr) return nullptr;

  MeshBuffers mesh;
  {
    std::lock_guard<std::mutex> lock(context->volumeMutex);
    mesh = context->volume->extractMesh(isoLevel);
    context->lastTriangleCount = static_cast<int>(mesh.triangleCount());
    if (!mesh.positions.empty()) {
      Bounds bounds;
      bounds.valid = true;
      bounds.minX = bounds.minY = bounds.minZ = 1e30f;
      bounds.maxX = bounds.maxY = bounds.maxZ = -1e30f;
      for (size_t i = 0; i + 2 < mesh.positions.size(); i += 3) {
        bounds.minX = std::min(bounds.minX, mesh.positions[i]);
        bounds.minY = std::min(bounds.minY, mesh.positions[i + 1]);
        bounds.minZ = std::min(bounds.minZ, mesh.positions[i + 2]);
        bounds.maxX = std::max(bounds.maxX, mesh.positions[i]);
        bounds.maxY = std::max(bounds.maxY, mesh.positions[i + 1]);
        bounds.maxZ = std::max(bounds.maxZ, mesh.positions[i + 2]);
      }
      context->lastBounds = bounds;
    }
  }

  const jsize vertexCount = static_cast<jsize>(mesh.vertexCount());
  const jsize triangleCount = static_cast<jsize>(mesh.triangleCount());

  jfloatArray vertexArray = env->NewFloatArray(2 + mesh.positions.size() + mesh.normals.size());
  if (vertexArray == nullptr) return nullptr;
  {
    std::vector<float> header(2 + mesh.positions.size() + mesh.normals.size());
    header[0] = static_cast<float>(vertexCount);
    header[1] = static_cast<float>(triangleCount);
    std::memcpy(header.data() + 2, mesh.positions.data(), mesh.positions.size() * sizeof(float));
    std::memcpy(header.data() + 2 + mesh.positions.size(), mesh.normals.data(),
                mesh.normals.size() * sizeof(float));
    env->SetFloatArrayRegion(vertexArray, 0, static_cast<jsize>(header.size()), header.data());
  }

  jintArray indexArray = env->NewIntArray(static_cast<jsize>(mesh.indices.size()));
  if (indexArray == nullptr) {
    env->DeleteLocalRef(vertexArray);
    return nullptr;
  }
  if (!mesh.indices.empty()) {
    env->SetIntArrayRegion(indexArray, 0, static_cast<jsize>(mesh.indices.size()),
                           reinterpret_cast<const jint*>(mesh.indices.data()));
  }

  jclass objectClass = env->FindClass("java/lang/Object");
  jobjectArray result = env->NewObjectArray(2, objectClass, nullptr);
  if (result != nullptr) {
    env->SetObjectArrayElement(result, 0, vertexArray);
    env->SetObjectArrayElement(result, 1, indexArray);
  }
  env->DeleteLocalRef(vertexArray);
  env->DeleteLocalRef(indexArray);
  env->DeleteLocalRef(objectClass);
  return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativeLastBounds(JNIEnv* env, jobject,
                                                                    jlong handle) {
  EngineContext* context = contextFrom(handle);
  if (context == nullptr || !context->lastBounds.valid) return nullptr;
  const Bounds& b = context->lastBounds;
  jfloat values[6] = {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
  jfloatArray result = env->NewFloatArray(6);
  if (result != nullptr) env->SetFloatArrayRegion(result, 0, 6, values);
  return result;
}

// ── Pose graph ────────────────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativePoseGraphReset(JNIEnv*, jobject,
                                                                       jlong handle) {
  EngineContext* context = contextFrom(handle);
  if (context != nullptr) context->poseGraph.reset();
}

JNIEXPORT jint JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativePoseGraphAddNode(JNIEnv* env, jobject,
                                                                         jlong handle,
                                                                         jfloatArray pose,
                                                                         jboolean fixed) {
  EngineContext* context = contextFrom(handle);
  if (context == nullptr) return -1;
  float matrix[16];
  copyMatrix(env, pose, matrix);
  Mat4 transform;
  std::memcpy(transform.m, matrix, sizeof(matrix));
  return context->poseGraph.addNode(transform, fixed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativePoseGraphAddEdge(
    JNIEnv* env, jobject, jlong handle, jint from, jint to, jfloatArray measurement,
    jdouble rotationWeight, jdouble translationWeight, jboolean loopClosure) {
  EngineContext* context = contextFrom(handle);
  if (context == nullptr) return;
  float matrix[16];
  copyMatrix(env, measurement, matrix);
  Mat4 transform;
  std::memcpy(transform.m, matrix, sizeof(matrix));
  context->poseGraph.addEdge(from, to, transform, rotationWeight, translationWeight,
                            loopClosure == JNI_TRUE);
}

/**
 * Runs the optimiser and returns
 * `[initialCost, finalCost, rmsTranslation, rmsRotation, iterations, convergedMs,
 *   pose0(16), pose1(16), …]` so one call refreshes the whole scene graph.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativePoseGraphOptimize(JNIEnv* env, jobject,
                                                                          jlong handle,
                                                                          jint iterations) {
  EngineContext* context = contextFrom(handle);
  if (context == nullptr) return nullptr;
  const constructar::OptimizationReport report = context->poseGraph.optimize(iterations);
  const auto poses = context->poseGraph.poses();

  const jsize size = static_cast<jsize>(6 + poses.size() * 16);
  jfloatArray result = env->NewFloatArray(size);
  if (result == nullptr) return nullptr;

  std::vector<float> payload(static_cast<size_t>(size));
  payload[0] = static_cast<float>(report.initialCost);
  payload[1] = static_cast<float>(report.finalCost);
  payload[2] = static_cast<float>(report.rmsTranslationMeters);
  payload[3] = static_cast<float>(report.rmsRotationRadians);
  payload[4] = static_cast<float>(report.iterations);
  payload[5] = static_cast<float>(report.milliseconds);
  size_t write = 6;
  for (const auto& pose : poses) {
    std::memcpy(payload.data() + write, pose.m, sizeof(pose.m));
    write += 16;
  }
  env->SetFloatArrayRegion(result, 0, size, payload.data());
  return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativeLoopCandidates(JNIEnv* env, jobject,
                                                                       jlong handle) {
  EngineContext* context = contextFrom(handle);
  if (context == nullptr) return nullptr;
  const constructar::ProximityLoopClosureDetector detector;
  const auto candidates = detector.findCandidates(context->poseGraph.poses());
  if (candidates.empty()) return nullptr;

  const jsize size = static_cast<jsize>(candidates.size() * 3);
  jfloatArray result = env->NewFloatArray(size);
  if (result == nullptr) return nullptr;
  std::vector<float> payload;
  payload.reserve(candidates.size() * 3);
  for (const auto& candidate : candidates) {
    payload.push_back(static_cast<float>(candidate.from));
    payload.push_back(static_cast<float>(candidate.to));
    payload.push_back(static_cast<float>(candidate.distanceMeters));
  }
  env->SetFloatArrayRegion(result, 0, size, payload.data());
  return result;
}

JNIEXPORT jboolean JNICALL
Java_com_constructar_nativebridge_NativeSlamEngine_nativeRtabMapAvailable(JNIEnv*, jobject,
                                                                        jlong handle) {
  EngineContext* context = contextFrom(handle);
  return (context != nullptr && context->rtabMap.isAvailable()) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
