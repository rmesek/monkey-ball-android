package io.github.rmesek.monkeyball

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

/**
 * deviceTilt (Tilt.kt): the angles the engine's tilt steering expects, the browser's `deviceorientation`
 * beta and gamma, from the rotation matrix the orientation sensor gives. No game is started.
 */
@RunWith(AndroidJUnit4::class)
class TiltTest {
    @Test
    fun flatOnTheTableIsLevel() = assertTilt(0f, 0f, identity())

    @Test
    fun raisingTheTopEdgeIsPositiveBeta() = assertTilt(30f, 0f, aboutX(30.0))

    @Test
    fun droppingTheRightEdgeIsPositiveGamma() = assertTilt(0f, 20f, aboutY(20.0))

    @Test
    fun bothTogether() = assertTilt(25f, -15f, times(aboutX(25.0), aboutY(-15.0)))

    /** Past upright, screen facing away: beta keeps growing instead of turning back as a pitch would. */
    @Test
    fun tippedPastUpright() = assertTilt(120f, 0f, aboutX(120.0))

    @Test
    fun screenDownTippedPastUpright() = assertTilt(-120f, 10f, times(aboutX(-120.0), aboutY(10.0)))

    // ---- helpers -------------------------------------------------------------------------------------

    private fun assertTilt(beta: Float, gamma: Float, r: FloatArray) {
        val (b, g) = deviceTilt(r)
        assertEquals("beta", beta, b, 0.01f)
        assertEquals("gamma", gamma, g, 0.01f)
    }

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** Rotation by `deg` about the device's x axis (left to right), row by row. */
    private fun aboutX(deg: Double): FloatArray {
        val (c, s) = cosSin(deg)
        return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
    }

    /** Rotation by `deg` about the device's y axis (bottom to top). */
    private fun aboutY(deg: Double): FloatArray {
        val (c, s) = cosSin(deg)
        return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
    }

    private fun cosSin(deg: Double) = Math.toRadians(deg).let { Pair(cos(it).toFloat(), sin(it).toFloat()) }

    /** a * b: first `a` (about the world's axis), then `b` about the device's turned axis. */
    private fun times(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
        val (row, col) = Pair(i / 3, i % 3)
        (0 until 3).sumOf { k -> (a[row * 3 + k] * b[k * 3 + col]).toDouble() }.toFloat()
    }
}
