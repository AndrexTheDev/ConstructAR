package com.constructar.arengine.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device monocular depth: **Depth Anything V2 Small** (Apache-2.0), quantised, executed
 * through LiteRT.
 *
 * ## Legal / licensing
 *
 * Only the *Small* variant is used and only the Apache-2.0 checkpoint is converted. The
 * Base/Large weights are CC-BY-NC (non-commercial) and must never be shipped; the build
 * verifies the model file's hash against `constructar.depthModelSha256`.
 *
 * ## Execution
 *
 * The interpreter is created with a delegate ladder — **NNAPI** (NPU/DSP) → **GPU (Vulkan)**
 * → **CPU (XNNPACK, 4 threads)** — falling back whenever a delegate fails to initialise.
 * Inference runs on a dedicated single-thread executor so it never blocks the render thread;
 * only the *metric* calibration and the TSDF integration touch the frame.
 *
 * ## Threading & allocation
 *
 * The input `ByteBuffer` and the output array are allocated **once**; every frame is written
 * in place. Inference is skipped (and the previous map reused) when a new frame is not yet
 * available, so a slow device degrades in frame-rate, never in memory.
 */
class MonocularDepthEstimator private constructor(
    private val interpreter: Interpreter,
    private val inputSize: Int,
    private val outputSize: Int,
    private val backend: String,
    private val closeableDelegates: List<AutoCloseable>,
) : AutoCloseable {

    data class Config(
        /** Asset path of the .tflite file (e.g. `models/depth_anything_v2_small_int8.tflite`). */
        val assetPath: String = "models/depth_anything_v2_small_int8.tflite",
        /** Alternative: a file already unpacked to app storage. */
        val modelFile: File? = null,
        /** Square network input (Depth Anything V2 Small: 518, multiple of 14). */
        val inputSize: Int = 518,
        val numThreads: Int = 4,
        /** Try the NNAPI delegate first (NPU/DPU). */
        val preferNnapi: Boolean = true,
        /** Then the Vulkan GPU delegate. */
        val preferGpu: Boolean = true,
        /** Expected SHA-256 of the model file, checked when non-null. */
        val expectedSha256: String? = null,
    )

    /** Runtime metrics, surfaced on the diagnostics screen. */
    @Volatile
    var lastInferenceMillis: Int = 0
        private set

    @Volatile
    var lastBackend: String = backend
        private set

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
    private val outputArray = Array(1) { Array(outputSize) { Array(outputSize) { FloatArray(1) } } }

    /** Scratch bitmap reused for every frame (no per-frame allocation). */
    private val scratchBitmap: Bitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(inputSize * inputSize)

    /**
     * Runs inference on a camera frame.
     *
     * @param source a bitmap of the current camera view (already rotated upright and
     *               downscaled by the caller's `ImageReader`), or a texture snapshot.
     * @return the relative (disparity-like) depth map, or null when inference failed.
     */
    fun estimate(source: Bitmap): RelativeDepthMap? {
        return try {
            val started = System.nanoTime()
            fillInput(source)
            interpreter.run(inputBuffer, outputArray)
            val values = FloatArray(outputSize * outputSize)
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            for (y in 0 until outputSize) {
                val row = outputArray[0][y]
                for (x in 0 until outputSize) {
                    val v = row[x][0]
                    values[y * outputSize + x] = v
                    if (v < min) min = v
                    if (v > max) max = v
                }
            }
            // Normalise to [0,1]: Depth Anything's absolute scale is arbitrary, only the
            // affine relation to inverse depth is meaningful (see MetricDepthCalibrator).
            val range = (max - min).takeIf { it > 1e-6f } ?: 1f
            for (i in values.indices) values[i] = (values[i] - min) / range
            lastInferenceMillis = ((System.nanoTime() - started) / 1_000_000L).toInt()
            RelativeDepthMap(values, outputSize, outputSize, lastInferenceMillis, backend)
        } catch (error: Throwable) {
            // A failing delegate or an OOM on a low-end device must never crash the AR view:
            // the scan simply continues with ARCore depth only.
            Log.w(TAG, "monocular depth inference failed, falling back to ARCore depth", error)
            null
        }
    }

    /** ARGB bitmap → normalised NCHW-free NHWC float input (0..1 RGB). */
    private fun fillInput(source: Bitmap) {
        // Nearest-neighbour resize into the scratch bitmap: the network is trained on
        // resampled crops, and bilinear `createScaledBitmap` allocates a bitmap per frame.
        val canvas = android.graphics.Canvas(scratchBitmap)
        canvas.drawBitmap(
            source,
            android.graphics.Rect(0, 0, source.width, source.height),
            android.graphics.Rect(0, 0, inputSize, inputSize),
            android.graphics.Paint().apply { isFilterBitmap = true },
        )
        scratchBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputBuffer.rewind()
        val floatBuffer = inputBuffer.asFloatBuffer()
        for (argb in pixels) {
            floatBuffer.put(((argb shr 16) and 0xFF) / 255f)
            floatBuffer.put(((argb shr 8) and 0xFF) / 255f)
            floatBuffer.put((argb and 0xFF) / 255f)
        }
        inputBuffer.rewind()
    }

    override fun close() {
        runCatching { interpreter.close() }
        closeableDelegates.forEach { runCatching { it.close() } }
        if (!scratchBitmap.isRecycled) scratchBitmap.recycle()
    }

    companion object {
        private const val TAG = "MonocularDepth"

        /** True when a model is bundled; the feature is optional at runtime. */
        fun isModelPresent(context: Context, config: Config = Config()): Boolean =
            config.modelFile?.exists() == true || runCatching {
                context.assets.open(config.assetPath).close()
            }.isSuccess

        /**
         * Creates an estimator or returns null when the model is absent/unusable. Callers
         * treat null as "AI depth disabled" — the app is fully functional without it.
         *
         * Delegate ladder: NNAPI → GPU (Vulkan) → CPU. Each attempt maps the model afresh:
         * an `Interpreter` takes ownership of its `MappedByteBuffer`, and a delegate may only
         * belong to one interpreter, so reusing either across attempts is undefined.
         */
        fun create(context: Context, config: Config = Config()): MonocularDepthEstimator? {
            val modelFile = resolveModelFile(context, config) ?: return null

            if (config.preferNnapi) {
                val delegate = try {
                    NnApiDelegate(NnApiDelegate.Options().apply { setUseNnapiCpu(false) })
                } catch (error: Throwable) {
                    Log.w(TAG, "NNAPI delegate unavailable: ${error.message}")
                    null
                }
                if (delegate != null) {
                    try {
                        val interpreter = Interpreter(mapModel(modelFile), baseOptions(config).apply { addDelegate(delegate) })
                        Log.i(TAG, "Depth Anything V2 running on NNAPI")
                        return MonocularDepthEstimator(
                            interpreter, config.inputSize, outputSizeOf(interpreter, config.inputSize),
                            "nnapi", listOf(delegate),
                        )
                    } catch (error: Throwable) {
                        Log.w(TAG, "NNAPI interpreter creation failed: ${error.message}")
                        runCatching { delegate.close() }
                    }
                }
            }

            if (config.preferGpu) {
                val delegate = try {
                    GpuDelegate(GpuDelegate.Options().apply { setPrecisionLossAllowed(true) })
                } catch (error: Throwable) {
                    Log.w(TAG, "GPU delegate unavailable: ${error.message}")
                    null
                }
                if (delegate != null) {
                    try {
                        val interpreter = Interpreter(mapModel(modelFile), baseOptions(config).apply { addDelegate(delegate) })
                        Log.i(TAG, "Depth Anything V2 running on the Vulkan GPU delegate")
                        return MonocularDepthEstimator(
                            interpreter, config.inputSize, outputSizeOf(interpreter, config.inputSize),
                            "gpu/vulkan", listOf(delegate),
                        )
                    } catch (error: Throwable) {
                        Log.w(TAG, "GPU interpreter creation failed: ${error.message}")
                        runCatching { delegate.close() }
                    }
                }
            }

            return try {
                val interpreter = Interpreter(mapModel(modelFile), baseOptions(config))
                Log.i(TAG, "Depth Anything V2 running on CPU (XNNPACK, ${config.numThreads} threads)")
                MonocularDepthEstimator(
                    interpreter, config.inputSize, outputSizeOf(interpreter, config.inputSize), "cpu/xnnpack",
                    emptyList(),
                )
            } catch (error: Throwable) {
                Log.e(TAG, "could not create interpreter", error)
                null
            }
        }

        private fun baseOptions(config: Config): Interpreter.Options =
            Interpreter.Options().apply {
                setNumThreads(config.numThreads)
                setUseXNNPACK(true)
            }

        /** Reads the output tensor shape, defaulting to [fallback]. */
        private fun outputSizeOf(interpreter: Interpreter, fallback: Int): Int {
            val shape = runCatching { interpreter.getOutputTensor(0).shape() }.getOrNull() ?: return fallback
            // [1, H, W, 1] or [1, 1, H, W]
            return shape.getOrNull(1)?.takeIf { it > 1 } ?: shape.getOrNull(2) ?: fallback
        }

        /** Resolves the model file and verifies its checksum when one is configured. */
        private fun resolveModelFile(context: Context, config: Config): File? {
            val file = config.modelFile?.takeIf { it.exists() } ?: unpackAsset(context, config.assetPath)
                ?: return null
            val expected = config.expectedSha256
            if (expected != null) {
                val actual = sha256Of(file)
                if (!actual.equals(expected, ignoreCase = true)) {
                    // A tampered or wrong-variant checkpoint (e.g. Base/Large, which is
                    // CC-BY-NC) must never reach the renderer.
                    Log.e(TAG, "depth model checksum mismatch: expected $expected, got $actual")
                    return null
                }
            }
            return file
        }

        private fun mapModel(file: File): MappedByteBuffer =
            FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ).use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }

        private fun sha256Of(file: File): String = runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")

        /**
         * LiteRT requires a `MappedByteBuffer`; the model must therefore live in a file, not
         * in the APK's compressed asset stream. This unpacks it once into `filesDir`.
         */
        private fun unpackAsset(context: Context, assetPath: String): File? {
            val target = File(context.filesDir, assetPath.substringAfterLast('/'))
            if (target.exists() && target.length() > 0) return target
            return runCatching {
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                }
                target
            }.getOrElse {
                Log.w(TAG, "depth model asset missing ($assetPath)", it)
                null
            }
        }
    }
}
