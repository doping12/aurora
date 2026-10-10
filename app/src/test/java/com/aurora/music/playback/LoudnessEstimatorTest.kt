package com.aurora.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoudnessEstimatorTest {
    @Test fun silenceOnlyHasNoEstimate() {
        val estimator = LoudnessEstimator()
        repeat(40) { estimator.addBlock(0.0, 0.0, 0.1) }
        assertNull(estimator.estimateDb())
    }

    @Test fun loudAndQuietSignalsProduceExpectedAttenuation() {
        val loud = LoudnessEstimator()
        repeat(31) { loud.addBlock(0.25, 0.25, 0.1) } // RMS 0.5 = about -6 dBFS
        assertEquals(-6.0206, loud.estimateDb()!!, 0.01)
        assertEquals(-11.9794, loud.targetGainDb()!!, 0.01)

        val quiet = LoudnessEstimator()
        repeat(31) { quiet.addBlock(0.01, 0.01, 0.1) } // RMS 0.1 = -20 dBFS
        assertEquals(-20.0, quiet.estimateDb()!!, 0.01)
        assertEquals(0.0, quiet.targetGainDb()!!, 0.0)
    }

    @Test fun gateExcludesSilentBlocksAndGainNeverBoosts() {
        val estimator = LoudnessEstimator()
        repeat(16) { estimator.addBlock(0.0, 0.0, 0.1) }
        repeat(15) { estimator.addBlock(0.25, 0.25, 0.1) }
        assertEquals(-6.0206, estimator.estimateDb()!!, 0.01)
        assertEquals(-11.9794, estimator.targetGainDb()!!, 0.01)
        assertEquals(0.0, LoudnessEstimator().let { quiet ->
            repeat(31) { quiet.addBlock(0.01, 0.01, 0.1) }
            quiet.targetGainDb()!!
        }, 0.0)
    }

    @Test fun slewLimitsTheGainChangeRate() {
        assertEquals(-1.0, LoudnessEstimator.slew(0.0, -10.0, 0.5), 0.0)
        assertEquals(-3.0, LoudnessEstimator.slew(-1.0, -10.0, 1.0), 0.0)
        assertEquals(0.0, LoudnessEstimator.slew(-1.0, 0.0, 1.0), 0.0)
    }
}
