package io.github.rmesek.monkeyball

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.google.androidgamesdk.GameActivity
import kotlin.system.exitProcess

/**
 * The game's activity. GameActivity loads libmonkeyball.so (the manifest's `android.app.lib_name`) and runs
 * its `android_main` on a thread of its own; the game draws into this activity's surface.
 *
 * This class does what only the Android side can: the document picker for the disc image, the system
 * "back", full screen and the space taken by system bars, the orientation sensor for tilt steering, messages
 * to the player; and it records what the game reports (GameEvents).
 */
class MainActivity : GameActivity() {

    /** The system's document picker. The chosen file goes to Rust as a file descriptor it then owns. */
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val fd = uri?.let { runCatching { contentResolver.openFileDescriptor(it, "r")?.detachFd() }.getOrNull() }
        nativeImagePicked(fd ?: -1)
    }

    /**
     * The system "back" (gesture or button) while the game has somewhere to go back to (a sub-menu, a
     * stage): one step back inside the game. Disabled at the title, where "back" leaves the app as usual.
     */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = nativeBack()
    }

    private val sensors by lazy { getSystemService(SensorManager::class.java) }

    /**
     * The device's orientation without the compass (it only needs to know which way is down, and magnets
     * nearby would turn it), or with it where there is no other.
     */
    private val orientationSensor by lazy {
        sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    /** The game steers by tilt (the player chose it, and a stage runs); set from Rust through setTilt. */
    private var tilt = false

    private val tiltListener = object : SensorEventListener {
        private val rotation = FloatArray(9)

        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            val (beta, gamma) = deviceTilt(rotation)
            // How far the screen is turned from the device's natural orientation, as the game's maths wants it.
            val screenAngle = (ContextCompat.getDisplayOrDefault(this@MainActivity).rotation) * 90
            nativeTilt(beta, gamma, screenAngle)
            GameEvents.tiltSamples.incrementAndGet()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, back)
        // The game's view has the focus and fills the screen. Without this, Android draws its translucent
        // focus highlight over all of it whenever the device is in keyboard (non-touch) mode: the game
        // looks pale.
        mSurfaceView.defaultFocusHighlightEnabled = false
        // Immersive: the game gets the whole screen; a swipe from the edge shows the bars for a moment.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // The sensor only while the game is in front: no samples (and no battery) for a game in the background.

    override fun onResume() {
        super.onResume()
        listenToTilt(tilt)
    }

    override fun onPause() {
        listenToTilt(false)
        super.onPause()
    }

    private fun listenToTilt(on: Boolean) {
        val sensor = orientationSensor ?: return
        sensors.unregisterListener(tiltListener)
        if (on) sensors.registerListener(tiltListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    /**
     * The activity is going away for good ("back" at the title, swiped away, closed by the system). winit
     * 0.30 does not pass this on to the game, so the game's thread would keep running, and GameActivity's
     * onDestroy waits for that thread: the app would hang, and the next start would stay on the splash
     * screen. The game runs once per process and saves as it goes, so end the process here; the next start
     * gets a fresh one. (The manifest's configChanges keep rotation and the like from destroying it.)
     * Hence no super.onDestroy(): that is the call that would wait.
     */
    @SuppressLint("MissingSuperCall")
    override fun onDestroy() {
        exitProcess(0)
    }

    /**
     * Called from Rust (game thread, through JNI) when the import screen needs a disc image. The game has
     * no fonts before it has the image, so a dialog says what to pick and why; then the system's document
     * picker opens. Any file type there: .rvz and .gcm have no registered MIME type, and the game checks
     * the content itself. Canceling ends the game, as canceling the picker does.
     */
    @Suppress("unused")
    fun pickImage() = runOnUiThread {
        val reason = refused.also { refused = null }
        AlertDialog.Builder(this)
            .setTitle(if (reason == null) R.string.pick_image_title else R.string.pick_image_refused_title)
            .setMessage(reason ?: getString(R.string.pick_image_message))
            .setPositiveButton(R.string.pick_image_choose) { _, _ -> pickImage.launch(arrayOf("*/*")) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> nativeImagePicked(-1) }
            .setOnCancelListener { nativeImagePicked(-1) }
            .show()
    }

    /**
     * Called from Rust (game thread) once, before the game starts: whether tilt can steer. The options show
     * the choice of controls only then.
     */
    @Suppress("unused")
    fun hasTiltSensor() = orientationSensor != null

    /**
     * Called from Rust (game thread) when the game starts or stops steering by tilt: a stage starts or ends
     * with tilt chosen in the options. While it steers, the screen keeps the rotation it has: turning the
     * phone to steer must not turn the picture as well. In the menus it rotates as usual, so the player can
     * pick the orientation to play in.
     */
    @Suppress("unused")
    fun setTilt(on: Boolean) = runOnUiThread {
        tilt = on
        GameEvents.tilt = on
        requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        listenToTilt(on && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    // Reports from Rust (game thread, through JNI). Kept in GameEvents for the instrumented tests.

    /** The GPU is up: the adapter's description. */
    @Suppress("unused")
    fun onGameReady(adapter: String) {
        GameEvents.adapter = adapter
    }

    /** Another game state is on top ("import", "menu", "play"...). */
    @Suppress("unused")
    fun onGameState(name: String) {
        GameEvents.states.add(name)
    }

    /** How many "back" steps the game has before its title screen (`Game::nav_depth`). */
    @Suppress("unused")
    fun onNavDepth(depth: Int) {
        GameEvents.navDepth = depth
        runOnUiThread { back.isEnabled = depth > 0 }
    }

    /** A fatal error; the game has ended. */
    @Suppress("unused")
    fun onGameError(message: String) {
        GameEvents.errors.add(message)
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    /** Why the file just picked cannot be used; the next pickImage dialog says it. */
    @Volatile
    private var refused: String? = null

    /** The picked file is not the game (called just before pickImage asks again). */
    @Suppress("unused")
    fun onImageRefused(reason: String) {
        GameEvents.messages.add(reason)
        refused = reason
    }

    /**
     * The window runs edge to edge (targetSdk 35+), under the status and navigation bars and around a
     * display cutout. GameActivity listens to inset changes itself; on top of that the game gets the
     * space those cover, in pixels, so it keeps its buttons and HUD out of it.
     */
    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val result = super.onApplyWindowInsets(view, insets)
        val covered = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        nativeInsets(covered.left, covered.top, covered.right, covered.bottom)
        return result
    }

    /** The picked file's descriptor, or -1 when the player canceled. Implemented in native/src/lib.rs. */
    private external fun nativeImagePicked(fd: Int)

    /** One step back inside the game (Cancel). Implemented in native/src/lib.rs. */
    private external fun nativeBack()

    /** Space covered by system bars and cutouts, in pixels. Implemented in native/src/lib.rs. */
    private external fun nativeInsets(left: Int, top: Int, right: Int, bottom: Int)

    /**
     * An orientation sample: beta and gamma in degrees (deviceTilt) and the screen's rotation (0, 90, 180,
     * 270). Implemented in native/src/lib.rs.
     */
    private external fun nativeTilt(beta: Float, gamma: Float, screenAngle: Int)
}
