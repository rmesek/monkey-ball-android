package io.github.rmesek.monkeyball

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the game (Rust) has reported in this process: the GPU it runs on, the game states it went through and
 * fatal errors. MainActivity fills it from the JNI calls; the instrumented tests (app/src/androidTest) wait
 * on it. One game runs per process, so process-wide state is the game's state.
 */
object GameEvents {
    /** The adapter description once the GPU is up, for example "Adreno (Vulkan, IntegratedGpu)". */
    @Volatile
    var adapter: String? = null

    /** The top game states in order: "import", "menu", "loading", "play"... */
    val states = CopyOnWriteArrayList<String>()

    /** "Back" steps to the title screen in the state on top (`Game::nav_depth`); 0 at the title. */
    @Volatile
    var navDepth: Int = 0

    /** The game steers by device tilt: the activity listens to the orientation sensor and keeps the rotation. */
    @Volatile
    var tilt = false

    /** Orientation samples sent to the game. */
    val tiltSamples = AtomicInteger()

    /** Fatal errors the game showed; the run ends after one. */
    val errors = CopyOnWriteArrayList<String>()

    /** Messages shown to the player as a toast (a disc image that cannot be used). */
    val messages = CopyOnWriteArrayList<String>()

    /** The state on top now, or null before the first frame. */
    val state: String? get() = states.lastOrNull()
}
