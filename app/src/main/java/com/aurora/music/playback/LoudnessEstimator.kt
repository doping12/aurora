package com.aurora.music.playback

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Pure, block-based loudness estimate for the PCM16 meter's published ~100 ms windows. */
class LoudnessEstimator {
    private var elapsedSeconds = 0.0
    private var gatedSeconds = 0.0
    private var gatedEnergy = 0.0

    val measuredSeconds: Double get() = elapsedSeconds
    val isComplete: Boolean get() = elapsedSeconds >= WINDOW_SECONDS

    /** mean-square values are normalized to full scale; a mono signal is passed twice by the meter. */
    fun addBlock(leftMeanSquare: Double, rightMeanSquare: Double, seconds: Double): Double? {
        if (isComplete || !seconds.isFinite() || seconds <= 0.0 ||
            !leftMeanSquare.isFinite() || !rightMeanSquare.isFinite()) return estimateDb()
        val duration = min(seconds, WINDOW_SECONDS - elapsedSeconds)
        val power = max(0.0, (leftMeanSquare + rightMeanSquare) * 0.5)
        elapsedSeconds += duration
        if (power > SILENCE_POWER) {
            gatedEnergy += power * duration
            gatedSeconds += duration
        }
        return estimateDb()
    }

    /** RMS dBFS of non-silent blocks, available after enough measured playback. */
    fun estimateDb(): Double? {
        if (elapsedSeconds < MIN_ESTIMATE_SECONDS || gatedSeconds <= 0.0) return null
        return 10.0 * log10(gatedEnergy / gatedSeconds)
    }

    fun targetGainDb(): Double? = estimateDb()?.let { min(0.0, TARGET_DBFS - it) }

    companion object {
        const val TARGET_DBFS = -18.0
        const val SILENCE_GATE_DBFS = -60.0
        const val MIN_ESTIMATE_SECONDS = 3.0
        const val SAVE_MIN_SECONDS = 10.0
        const val WINDOW_SECONDS = 20.0
        private val SILENCE_POWER = 10.0.pow(SILENCE_GATE_DBFS / 10.0)

        fun slew(currentDb: Double, targetDb: Double, elapsedSeconds: Double, maxDbPerSecond: Double = 2.0): Double {
            if (!currentDb.isFinite() || !targetDb.isFinite() || !elapsedSeconds.isFinite() ||
                !maxDbPerSecond.isFinite() || elapsedSeconds <= 0.0 || maxDbPerSecond < 0.0) return currentDb
            val maxDelta = maxDbPerSecond * elapsedSeconds
            return currentDb + (targetDb - currentDb).coerceIn(-maxDelta, maxDelta)
        }

        private fun log10(value: Double): Double = ln(value) / ln(10.0)
    }
}
