package io.github.rmesek.monkeyball

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.androidgamesdk.GameActivity
import kotlin.system.exitProcess

/**
 * The game's activity. GameActivity loads libmonkeyball.so (the manifest's `android.app.lib_name`) and runs
 * its `android_main` on a thread of its own; the game draws into this activity's surface.
 *
 * This class does what only the Android side can: the document picker for the disc image, the system
 * "back", full screen and the space taken by system bars, messages to the player; and it records what the
 * game reports (GameEvents).
 */
class MainActivity : GameActivity() {

    /** The system's document picker. The chosen file goes to Rust as a file descriptor it then owns. */
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val fd = uri?.let {
            try {
                contentResolver.openFileDescriptor(it, "r")?.detachFd()
            } catch (e: Exception) {
                null
            }
        }
        nativeImagePicked(fd ?: -1)
    }

    /**
     * The system "back" (gesture or button) while the game has somewhere to go back to (a sub-menu, a
     * stage): one step back inside the game. Disabled at the title, where "back" leaves the app as usual.
     */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = nativeBack()
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

    /**
     * The activity is going away for good ("back" at the title, swiped away, closed by the system). winit
     * 0.30 does not pass this on to the game, so the game's thread would keep running, and GameActivity's
     * onDestroy waits for that thread: the app would hang, and the next start would stay on the splash
     * screen. The game runs once per process and saves as it goes, so end the process here; the next start
     * gets a fresh one. (The manifest's configChanges keep rotation and the like from destroying it.)
     */
    override fun onDestroy() {
        exitProcess(0)
    }

    /**
     * Called from Rust (game thread, through JNI) when the import screen needs a disc image. The game has
     * no fonts before it has the image, so a dialog says what to pick and why; then the system's document
     * picker opens. Any file type there: .rvz and .gcm have no registered MIME type, and the game checks
     * the content itself. Cancelling ends the game, as cancelling the picker does.
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

    /** The picked file's descriptor, or -1 when the player cancelled. Implemented in native/src/lib.rs. */
    private external fun nativeImagePicked(fd: Int)

    /** One step back inside the game (Cancel). Implemented in native/src/lib.rs. */
    private external fun nativeBack()

    /** Space covered by system bars and cutouts, in pixels. Implemented in native/src/lib.rs. */
    private external fun nativeInsets(left: Int, top: Int, right: Int, bottom: Int)
}
