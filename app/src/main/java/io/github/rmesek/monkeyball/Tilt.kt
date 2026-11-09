package io.github.rmesek.monkeyball

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2

/**
 * The device's tilt as the web's `deviceorientation` event gives it, which is what the engine's tilt steering
 * (`mb_app::tilt::TiltSteering`) reads: `beta`, the front-to-back tilt in degrees (-180..180, positive when
 * the top edge rises), and `gamma`, the left-to-right tilt (-90..90, positive when the right edge drops).
 *
 * `r` is the 3x3 rotation matrix from device to world coordinates, row by row, as
 * `SensorManager.getRotationMatrixFromVector` fills it. The angles are its intrinsic Z-X'-Y'' Euler angles,
 * computed as Chromium does for the browser. `SensorManager.getOrientation` is not used: its pitch stops at
 * ±90°, so a phone tipped past upright would steer the wrong way.
 */
internal fun deviceTilt(r: FloatArray): Pair<Float, Float> {
    val beta: Double
    val gamma: Double
    if (r[8] >= 0) {
        // Screen facing up (or exactly upright).
        beta = asin(r[7].toDouble().coerceIn(-1.0, 1.0))
        gamma = atan2(-r[6].toDouble(), r[8].toDouble())
    } else {
        // Screen facing down: beta beyond ±90°, gamma back in -90..90.
        val b = -asin(r[7].toDouble().coerceIn(-1.0, 1.0))
        beta = if (b >= 0) b - PI else b + PI
        gamma = atan2(r[6].toDouble(), -r[8].toDouble())
    }
    return Pair(Math.toDegrees(beta).toFloat(), Math.toDegrees(gamma).toFloat())
}
