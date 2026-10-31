/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device monocular depth: **Depth Anything V2 Small** (Apache-2.0), executed through LiteRT.
 *
 * ## Legal / licensing
 *
 * Only the *Small* variant is used and only the Apache-2.0 checkpoint is converted. The
 * Base/Large weights are CC-BY-NC (non-commercial) and must never be shipped; the build
 * verifies the model file's hash against `constructar.depthModelSha256` when one is configured.
 *
 * ## Why CPU/XNNPACK and nothing else
 *
 * The delegate ladder this class used to carry (NNAPI → Vulkan GPU → CPU) is gone, on purpose:
 *
 *  * **NNAPI is deprecated.** Google stopped shipping it as part of the standalone LiteRT
 *    runtime (`com.google.ai.edge.litert:litert`); the only current artefacts are the
 *    Play-services TFLite bindings, which are a network/telemetry dependency and therefore
 *    banned by ConstructAR's offline policy.
 *  * **The GPU delegate cannot execute an int8 model.** Its shader path is built around
 *    float16/float32 weights; quantised convolutions fall back or fail outright, so on the one
 *    model we are allowed to ship (int8, Apache-2.0) it would spend a Vulkan context and a
 *    few hundred milliseconds to hand the graph straight back to the CPU.
 *
 * XNNPACK with four threads executes Depth Anything V2 Small in tens of milliseconds, off the
 * render thread, at the 1–2 Hz cadence the fusion pass asks for. Fewer moving parts, no
 * accelerator-probing at startup, and nothing to go wrong on a device whose driver lies.
 *
 * ## Quantisation-awareness
 *
 * The graph's own tensors decide how frames are written and read: a float32 input gets
 * normalised RGB floats, a uint8/int8 input gets quantised channels using the tensor's
 * `scale`/`zeroPoint`, and the output is dequantised the same way. That makes the estimator
 * correct for the int8 export *and* for a float32 export of the same checkpoint, instead of
 * silently producing garbage for whichever one the builder dropped in.
 *
 * ## Threading & allocation
 *
 * Input and output buffers are direct `ByteBuffer`s allocated **once**; every frame is written
 * in place. Inference runs on the caller's single-thread executor, never on the render thread.
 */
class MonocularDepthEstimator private constructor(
    private val interpreter: Interpreter,
    private val backend: String,
) : AutoCloseable {

    data class Config(
        /** Asset path of the .tflite file (e.g. `models/depth_anything_v2_small_int8.tflite`). */
        val assetPath: String = "models/depth_anything_v2_small_int8.tflite",
        /** Alternative: a file already unpacked to app storage. */
        val modelFile: File? = null,
        /** XNNPACK worker threads. Four saturates a big core pair without starving the UI. */
        val numThreads: Int = 4,
        /** Expected SHA-256 of the model file, checked when non-null. */
        val expectedSha256: String? = null,
    )

    // ── Tensor descriptors, read once from the graph ────────────────────────────────────────
    private val inputTensor = interpreter.getInputTensor(0)
    private val outputTensor = interpreter.getOutputTensor(0)

    private val inputShape: IntArray = inputTensor.shape()
    private val outputShape: IntArray = outputTensor.shape()

    /**
     * Network input edge length (Depth Anything V2: 518, a multiple of 14).
     *
     * Taken as the largest non-batch dimension so the estimator works for an NHWC export
     * (`[1, 518, 518, 3]`) and an NCHW one (`[1, 3, 518, 518]`) alike; the *fill order* below
     * is NHWC, which is what every Depth Anything export uses.
     */
    val inputSize: Int = inputShape.filter { it > 1 }.maxOrNull() ?: DEFAULT_INPUT_SIZE

    /** Output map width, in network pixels. */
    val outputWidth: Int = outputShape.getOrNull(2)?.takeIf { it > 0 } ?: inputSize

    /** Output map height, in network pixels. */
    val outputHeight: Int = outputShape.getOrNull(1)?.takeIf { it > 0 } ?: inputSize

    private val inputIsFloat = inputTensor.dataType() == DataType.FLOAT32
    private val outputIsFloat = outputTensor.dataType() == DataType.FLOAT32

    private val inputQuant = inputTensor.quantizationParams()
    private val outputQuant = outputTensor.quantizationParams()

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(bytesOf(inputShape, inputTensor.dataType()))
            .order(ByteOrder.nativeOrder())
    private val outputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(bytesOf(outputShape, outputTensor.dataType()))
            .order(ByteOrder.nativeOrder())

    /** Scratch bitmap + pixel array reused for every frame (no per-frame allocation). */
    private val scratchBitmap: Bitmap =
        Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(inputSize * inputSize)
    private val resizePaint = Paint().apply { isFilterBitmap = true }

    /** Runtime metrics, surfaced on the diagnostics screen. */
    @Volatile
    var lastInferenceMillis: Int = 0
        private set

    @Volatile
    var lastBackend: String = backend
        private set

    /**
     * Runs inference on one camera frame.
     *
     * @param source a bitmap of the current camera view (already rotated upright and downscaled
     *               by the caller's `ImageReader`), or a texture snapshot.
     * @return the relative (disparity-like) depth map normalised to [0,1], or null on failure.
     */
    fun estimate(source: Bitmap): RelativeDepthMap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        return try {
            val started = System.nanoTime()
            fillInput(source)
            interpreter.run(inputBuffer, outputBuffer)
            val map = readOutput()
            lastInferenceMillis = ((System.nanoTime() - started) / 1_000_000L).toInt()
            RelativeDepthMap(map, outputWidth, outputHeight, lastInferenceMillis, backend)
        } catch (error: Throwable) {
            // An OOM on a low-end device must never crash the AR view: the scan simply
            // continues with ARCore depth only.
            Log.w(TAG, "monocular depth inference failed, falling back to ARCore depth", error)
            null
        }
    }

    /** Bitmap → the graph's input tensor, in place. */
    private fun fillInput(source: Bitmap) {
        // Nearest-neighbour-free resize into a fixed scratch bitmap: `createScaledBitmap`
        // would allocate a bitmap per frame, which is exactly what the hot path must not do.
        Canvas(scratchBitmap).drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            Rect(0, 0, inputSize, inputSize),
            resizePaint,
        )
        scratchBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputBuffer.rewind()

        if (inputIsFloat) {
            val floats = inputBuffer.asFloatBuffer()
            for (argb in pixels) {
                floats.put(((argb shr 16) and 0xFF) / 255f)
                floats.put(((argb shr 8) and 0xFF) / 255f)
                floats.put((argb and 0xFF) / 255f)
            }
        } else {
            // Quantised input: q = round(v / scale) + zeroPoint, with v in [0,1].
            val scale = inputQuant.scale.takeIf { it > 0f } ?: (1f / 255f)
            val zeroPoint = inputQuant.zeroPoint
            val signed = inputTensor.dataType() == DataType.INT8
            for (argb in pixels) {
                putQuantised(inputBuffer, scale, zeroPoint, signed, ((argb shr 16) and 0xFF) / 255f)
                putQuantised(inputBuffer, scale, zeroPoint, signed, ((argb shr 8) and 0xFF) / 255f)
                putQuantised(inputBuffer, scale, zeroPoint, signed, (argb and 0xFF) / 255f)
            }
        }
        inputBuffer.rewind()
    }

    /** Writes one quantised channel sample: `round(v / scale) + zeroPoint`, clamped. */
    private fun putQuantised(
        buffer: ByteBuffer,
        scale: Float,
        zeroPoint: Int,
        signed: Boolean,
        value: Float,
    ) {
        val q = (value / scale).toInt() + zeroPoint
        val clamped = if (signed) q.coerceIn(-128, 127) else q.coerceIn(0, 255)
        buffer.put(clamped.toByte())
    }

    /** Copy of the network output as a [0,1] row-major map (owns a fresh array per call). */
    private fun readOutput(): FloatArray {
        outputBuffer.rewind()
        val out = FloatArray(outputWidth * outputHeight)
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        if (outputIsFloat) {
            val floats = outputBuffer.asFloatBuffer()
            for (i in out.indices) {
                val v = floats.get(i)
                out[i] = v
                if (v < min) min = v
                if (v > max) max = v
            }
        } else {
            val scale = outputQuant.scale.takeIf { it > 0f } ?: (1f / 255f)
            val zeroPoint = outputQuant.zeroPoint
            val signed = outputTensor.dataType() == DataType.INT8
            for (i in out.indices) {
                val raw = outputBuffer.get(i).toInt() and 0xFF
                val q = if (signed && raw > 127) raw - 256 else raw
                val v = (q - zeroPoint) * scale
                out[i] = v
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        // Depth Anything's absolute scale is arbitrary; only the affine relation to inverse
        // depth is meaningful (see [MetricDepthCalibrator]), so the map is normalised here.
        val range = (max - min).takeIf { it > 1e-6f } ?: 1f
        for (i in out.indices) out[i] = (out[i] - min) / range
        return out
    }

    override fun close() {
        runCatching { interpreter.close() }
        if (!scratchBitmap.isRecycled) scratchBitmap.recycle()
    }

    companion object {
        private const val TAG = "MonocularDepth"

        /** Depth Anything V2 Small's native input edge (518 = 37 × 14). */
        private const val DEFAULT_INPUT_SIZE = 518

        /** True when a model is bundled; the feature is optional at runtime. */
        fun isModelPresent(context: Context, config: Config = Config()): Boolean =
            config.modelFile?.exists() == true || runCatching {
                context.assets.open(config.assetPath).close()
            }.isSuccess

        /**
         * Creates an estimator, or returns null when the model is absent/unusable. Callers
         * treat null as "AI depth disabled" — the app is fully functional without it.
         */
        fun create(context: Context, config: Config = Config()): MonocularDepthEstimator? {
            val modelFile = resolveModelFile(context, config) ?: return null
            val options = Interpreter.Options().apply {
                setNumThreads(config.numThreads)
                setUseXNNPACK(true)
            }
            return try {
                val interpreter = Interpreter(mapModel(modelFile), options)
                Log.i(TAG, "Depth Anything V2 Small running on CPU/XNNPACK (${config.numThreads} threads)")
                MonocularDepthEstimator(interpreter, "cpu/xnnpack")
            } catch (error: Throwable) {
                Log.e(TAG, "could not create the LiteRT interpreter", error)
                null
            }
        }

        /** Element count × element size, for a tensor described by [shape] and [dataType]. */
        private fun bytesOf(shape: IntArray, dataType: DataType): Int {
            var elements = 1
            for (dimension in shape) if (dimension > 0) elements *= dimension
            val elementSize = when (dataType) {
                DataType.FLOAT32, DataType.INT32 -> 4
                DataType.FLOAT16 -> 2
                else -> 1
            }
            return elements * elementSize
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
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }.getOrDefault("")

        /**
         * LiteRT requires a `MappedByteBuffer`; the model must therefore live in a file, not in
         * the APK's compressed asset stream. This unpacks it once into `filesDir`.
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
