/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

import com.constructar.arengine.ai.AiDepthFusion
import com.constructar.arengine.ai.MetricDepthCalibrator
import com.constructar.arengine.ai.RelativeDepthMap
import com.constructar.arengine.ai.SpatialCalibrationGuard
import com.constructar.arengine.depth.DepthFrameSnapshot
import com.constructar.arengine.depth.DepthSource
import com.constructar.core.geo.DepthIntrinsics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic functional A/B fixture for the two AI-depth guards. This is not a wall-clock,
 * device-accuracy, thermal or runtime benchmark. It runs the production calibrator, fusion and
 * integration selector and publishes the control beside the guarded result as a CI JSON artifact.
 */
class DepthGuardBenchmarkTest {

    @Test
    fun `deterministic guard ab fixture publishes its JSON baseline`() {
        val first = runBenchmark()
        val second = runBenchmark()
        assertEquals("the same fixture must produce byte-identical metrics", first.json, second.json)

        assertEquals(2_304, first.spatial.controlFilledPixels)
        assertEquals(2_112, first.spatial.guardedFilledPixels)
        assertEquals(192, first.spatial.controlBadRegionFills)
        assertEquals(0, first.spatial.guardedBadRegionFills)
        assertEquals(192, first.spatial.guardedRejectedPixels)
        assertEquals(1, first.spatial.rejectedRegions)
        assertEquals(0, first.spatial.healthyFillsLost)
        assertEquals(0, first.spatial.measuredPixelsChanged)

        assertEquals(4, first.temporal.controlFusedAccepted)
        assertEquals(0, first.temporal.controlMeasuredFallbacks)
        assertEquals(2, first.temporal.guardedFusedAccepted)
        assertEquals(2, first.temporal.guardedMeasuredFallbacks)
        assertEquals(1, first.temporal.guardedContradictionRejections)
        assertEquals(1, first.temporal.guardedTerminalFallbacks)
        assertEquals(0, first.temporal.consistentFramesRejected)

        val output = File("build/reports/tests/depth-guard-benchmark.json")
        output.parentFile.mkdirs()
        output.writeText(first.json)
        assertTrue(output.isFile)
        println("Deterministic depth-guard A/B report: ${output.absolutePath}")
    }

    private data class SpatialMetrics(
        val controlFilledPixels: Int,
        val guardedFilledPixels: Int,
        val controlBadRegionFills: Int,
        val guardedBadRegionFills: Int,
        val guardedRejectedPixels: Int,
        val rejectedRegions: Int,
        val healthyFillsLost: Int,
        val measuredPixelsChanged: Int,
    ) {
        fun json(): String {
            val suppression = if (controlBadRegionFills == 0) 0f else
                (controlBadRegionFills - guardedBadRegionFills).toFloat() / controlBadRegionFills
            return """
                |"control":{"filledPixels":$controlFilledPixels,"badRegionFills":$controlBadRegionFills},
                |"guarded":{"filledPixels":$guardedFilledPixels,"badRegionFills":$guardedBadRegionFills,"rejectedPixels":$guardedRejectedPixels,"rejectedRegions":$rejectedRegions,"healthyFillsLost":$healthyFillsLost,"measuredPixelsChanged":$measuredPixelsChanged,"badFillSuppressionRatio":$suppression}
            """.trimMargin().replace("\n", "")
        }
    }

    private data class TemporalMetrics(
        val controlFusedAccepted: Int,
        val guardedFusedAccepted: Int,
        val controlMeasuredFallbacks: Int,
        val guardedMeasuredFallbacks: Int,
        val guardedContradictionRejections: Int,
        val guardedTerminalFallbacks: Int,
        val consistentFramesRejected: Int,
    ) {
        fun json(): String = """
            |"control":{"fusedAccepted":$controlFusedAccepted,"measuredFallbacks":$controlMeasuredFallbacks},
            |"guarded":{"fusedAccepted":$guardedFusedAccepted,"measuredFallbacks":$guardedMeasuredFallbacks,"contradictionRejections":$guardedContradictionRejections,"terminalFallbacks":$guardedTerminalFallbacks,"consistentFramesRejected":$consistentFramesRejected}
        """.trimMargin().replace("\n", "")
    }

    private data class BenchmarkReport(
        val spatial: SpatialMetrics,
        val temporal: TemporalMetrics,
        val json: String,
    )

    private fun runBenchmark(): BenchmarkReport {
        val spatial = runSpatialFixture()
        val temporalControl = runTemporalFixture(guardEnabled = false)
        val temporalGuarded = runTemporalFixture(guardEnabled = true)
        val temporal = TemporalMetrics(
            controlFusedAccepted = temporalControl.fusedAccepted,
            guardedFusedAccepted = temporalGuarded.fusedAccepted,
            controlMeasuredFallbacks = temporalControl.measuredFallbacks,
            guardedMeasuredFallbacks = temporalGuarded.measuredFallbacks,
            guardedContradictionRejections = temporalGuarded.contradictionRejections,
            guardedTerminalFallbacks = temporalGuarded.terminalFallbacks,
            consistentFramesRejected = temporalGuarded.consistentFramesRejected,
        )
        val commit = jsonString(System.getenv("GITHUB_SHA") ?: "local")
        val run = jsonString(System.getenv("GITHUB_RUN_ID") ?: "local")
        val json = """
            |{
            |  "schemaVersion":1,
            |  "fixture":"depth-guard-ab-v1",
            |  "build":{"commit":"$commit","workflowRun":"$run"},
            |  "deviceMeasurement":false,
            |  "wallClockBenchmark":false,
            |  "scenarios":{
            |    "spatialRegionalContamination":{${spatial.json()}},
            |    "temporalMovingObjectSequence":{${temporal.json()}}
            |  }
            |}
        """.trimMargin()
        return BenchmarkReport(spatial, temporal, json)
    }

    /** 64×48 wall with one of twelve 16×16 regions carrying a wrong local network scale. */
    private fun runSpatialFixture(): SpatialMetrics {
        val width = 64
        val height = 48
        val badColumn = 3
        val badRow = 1
        val arcore = ShortArray(width * height)
        val truth = ShortArray(width * height)
        val relativeValues = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val depthMm = 2_000 + ((x / 16 + y / 16) % 3) * 500
                truth[index] = depthMm.toShort()
                if (x % 2 == 0 && y % 2 == 0) arcore[index] = depthMm.toShort()
                var network = 0.9f / (depthMm / 1000f) + 0.05f
                if (x / 16 == badColumn && y / 16 == badRow) network += 0.8f
                relativeValues[index] = network
            }
        }
        val relative = RelativeDepthMap(relativeValues, width, height, 0, "fixture")
        val confidence = ByteArray(width * height) { 0x7f }
        val samples = AiDepthFusion.calibrationSamples(
            arcoreMm = arcore,
            confidence = confidence,
            confidenceWidth = width,
            confidenceHeight = height,
            confidenceRowStride = width,
            width = width,
            height = height,
            relative = relative,
            stride = 2,
        )
        val calibration = MetricDepthCalibrator().calibrate(samples.relativeValues, samples.metricMeters)
        assertTrue("the globally dominant surface must remain usable", calibration.isUsable)
        val mask = SpatialCalibrationGuard.evaluate(samples, calibration)

        val control = fuseSpatial(arcore, confidence, relative, calibration, null)
        val guarded = fuseSpatial(arcore, confidence, relative, calibration, mask)
        fun isBad(x: Int, y: Int) = x / 16 == badColumn && y / 16 == badRow
        var controlBad = 0
        var guardedBad = 0
        var healthyLost = 0
        var measuredChanged = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (arcore[index] != 0.toShort()) {
                    if (control.depthMm[index] != truth[index] || guarded.depthMm[index] != truth[index]) {
                        measuredChanged++
                    }
                    continue
                }
                val controlFilled = (control.depthMm[index].toInt() and 0xFFFF) > 0
                val guardedFilled = (guarded.depthMm[index].toInt() and 0xFFFF) > 0
                if (isBad(x, y)) {
                    if (controlFilled) controlBad++
                    if (guardedFilled) guardedBad++
                } else if (controlFilled && !guardedFilled) {
                    healthyLost++
                }
            }
        }
        return SpatialMetrics(
            controlFilledPixels = control.filledPixels,
            guardedFilledPixels = guarded.filledPixels,
            controlBadRegionFills = controlBad,
            guardedBadRegionFills = guardedBad,
            guardedRejectedPixels = guarded.spatiallyRejectedPixels,
            rejectedRegions = mask.rejectedRegionCount,
            healthyFillsLost = healthyLost,
            measuredPixelsChanged = measuredChanged,
        )
    }

    private fun fuseSpatial(
        arcore: ShortArray,
        confidence: ByteArray,
        relative: RelativeDepthMap,
        calibration: MetricDepthCalibrator.Calibration,
        mask: com.constructar.arengine.ai.SpatialCalibrationMask?,
    ): AiDepthFusion.Result = AiDepthFusion.fuse(
        arcoreMm = arcore,
        confidence = confidence,
        confidenceWidth = relative.width,
        confidenceHeight = relative.height,
        confidenceRowStride = relative.width,
        width = relative.width,
        height = relative.height,
        relative = relative,
        calibration = calibration,
        out = ShortArray(relative.width * relative.height),
        confidenceOut = ByteArray(relative.width * relative.height),
        spatialMask = mask,
    )

    private data class TemporalRun(
        val fusedAccepted: Int = 0,
        val measuredFallbacks: Int = 0,
        val contradictionRejections: Int = 0,
        val terminalFallbacks: Int = 0,
        val consistentFramesRejected: Int = 0,
    )

    /**
     * Four moving-camera frames: two consistent views, vanished foreground, then a new occluder.
     * The guarded path mirrors TsdfScanPipeline's terminal CAS after a contradiction.
     */
    private fun runTemporalFixture(guardEnabled: Boolean): TemporalRun {
        val width = 32
        val height = 24
        val intrinsics = DepthIntrinsics(fx = 20f, fy = 20f, cx = 15.5f, cy = 11.5f)
        val sourcePose = FloatArray(16) { if (it == 0 || it == 5 || it == 10 || it == 15) 1f else 0f }
        val fused = FusedDepthFrame(
            depthMm = ShortArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if (x in 8..23 && y in 6..17) 1_000 else 3_000
            },
            confidence = ByteArray(width * height) { 96 },
            width = width,
            height = height,
            timestampNs = 100L,
            intrinsics = intrinsics,
            cameraToWorld = sourcePose,
            receivedAtMillis = 0L,
            depthTimestampNs = 90L,
            source = DepthSource.RAW,
        )
        val pending = AtomicReference<FusedDepthFrame?>(fused)
        var fusedAccepted = 0
        var measuredFallbacks = 0
        var contradictionRejections = 0
        var terminalFallbacks = 0
        var consistentFramesRejected = 0

        for (step in 1..4) {
            val cameraZ = step * 0.1f
            val pose = sourcePose.copyOf().also { it[14] = cameraZ }
            val depth = ShortArray(width * height) { index ->
                val x = index % width
                val y = index / width
                val cameraMm = (cameraZ * 1000f).toInt()
                when {
                    step <= 2 && x in 8..23 && y in 6..17 -> (1_000 + cameraMm).toShort()
                    step == 4 && x < 8 && y in 6..17 -> (800 + cameraMm).toShort()
                    else -> (3_000 + cameraMm).toShort()
                }
            }
            val current = DepthFrameSnapshot(
                depthMm = depth,
                width = width,
                height = height,
                rowStride = width,
                confidence = ByteArray(width * height) { 0x7f },
                confidenceWidth = width,
                confidenceHeight = height,
                confidenceRowStride = width,
                intrinsics = intrinsics,
                cameraToWorld = pose,
                timestampNs = 200L + step,
                depthTimestampNs = 150L + step,
                source = DepthSource.RAW,
            )
            val candidate = pending.get()
            val selected = DepthIntegrationSelector.select(
                snapshot = current,
                fused = candidate,
                nowMillis = step * 10L,
                fusionTtlMillis = 200L,
                temporalGuardEnabled = guardEnabled,
            )
            if (selected.temporalReport?.rejected == true) pending.compareAndSet(candidate, null)
            if (selected.usesFusedDepth) fusedAccepted++ else measuredFallbacks++
            if (selected.temporalReport?.reason == TemporalRejectionReason.CONTRADICTED) {
                contradictionRejections++
            }
            if (candidate == null) terminalFallbacks++
            if (step <= 2 && !selected.usesFusedDepth) consistentFramesRejected++
        }
        assertFalse("guard-disabled control must never remove the candidate", !guardEnabled && pending.get() == null)
        if (guardEnabled) assertTrue("a contradiction must be terminal", pending.get() == null)
        return TemporalRun(
            fusedAccepted, measuredFallbacks, contradictionRejections, terminalFallbacks, consistentFramesRejected,
        )
    }

    private fun jsonString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
