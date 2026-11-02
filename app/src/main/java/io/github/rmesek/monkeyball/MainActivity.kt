package io.github.rmesek.monkeyball

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import com.google.androidgamesdk.GameActivity

/**
 * The game's activity. GameActivity loads libmonkeyball.so (the manifest's `android.app.lib_name`) and runs
 * its `android_main` on a thread of its own; the game draws into this activity's surface.
 *
 * This class does what only the Android side can: the document picker for the disc image.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Called from Rust (game thread, through JNI) when the import screen needs a disc image. Any file type:
     * .rvz and .gcm have no registered MIME type, and the game checks the content itself.
     */
    @Suppress("unused")
    fun pickImage() = runOnUiThread { pickImage.launch(arrayOf("*/*")) }

    /** The picked file's descriptor, or -1 when the player cancelled. Implemented in native/src/lib.rs. */
    private external fun nativeImagePicked(fd: Int)
}
