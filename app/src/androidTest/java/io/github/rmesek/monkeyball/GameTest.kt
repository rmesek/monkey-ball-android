package io.github.rmesek.monkeyball

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * The game on a device or emulator: the whole app (Kotlin activity, Rust engine, GPU) as the player gets it.
 * Run from Android Studio (the green arrow next to the class or a test) or with
 * `./gradlew connectedDebugAndroidTest`.
 *
 * Each test runs in a process of its own (Android Test Orchestrator, app/build.gradle.kts): the engine runs
 * once per process. The tests that import need the disc image on the device, in the folder the document
 * picker shows or in Downloads (`adb push "Super Monkey Ball (USA).rvz" /sdcard/Download/`); without it they
 * are skipped, not failed. The document picker is another app, worked here through UiAutomation (part of
 * Android, no test library).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GameTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val pack = File(instrumentation.targetContext.filesDir, "game.pack")

    @After
    fun resetBackend() = setBackend(null)

    @Test
    fun firstStartAsksForTheDiscImage() {
        pack.delete()
        ActivityScenario.launch(MainActivity::class.java)
        waitFor("the GPU", 30) { GameEvents.adapter != null }
        waitFor("the import screen", 10) { GameEvents.state == "import" }
        waitFor("the document picker", 10) { pickerShown() }
        assertNoErrors()
    }

    @Test
    fun startsOnVulkan() = startsOn("vulkan", "Vulkan")

    @Test
    fun startsOnGles() = startsOn("gl", "Gl")

    @Test
    fun importsTheDiscImageAndShowsTheMenu() {
        pack.delete()
        ActivityScenario.launch(MainActivity::class.java)
        importDiscImage()
        assertTrue("game.pack is ${pack.length()} bytes", pack.length() > 100L shl 20)
        assertEquals(listOf("import", "menu"), GameEvents.states.toList())
        assertNoErrors()
        // The first save, written before the game starts: full volume on a phone (native/src/lib.rs).
        val saves = pack.parentFile!!.listFiles { f -> f.name.startsWith("profile") }.orEmpty()
        val save = saves.joinToString("\n") { it.readText() }
        assertTrue("first save: $save", "setting music_volume 100" in save && "setting sfx_volume 100" in save)
    }

    @Test
    fun laterStartsGoStraightToTheMenu() {
        // The game data of importsTheDiscImageAndShowsTheMenu, which runs before (FixMethodOrder).
        assumeTrue("no game data: run importsTheDiscImageAndShowsTheMenu first", pack.exists())
        ActivityScenario.launch(MainActivity::class.java)
        waitFor("the title menu", 30) { GameEvents.state == "menu" }
        assertEquals("first state", "menu", GameEvents.states.first())
        assertNoErrors()
    }

    @Test
    fun menuSystemBackStepsOutOfSubMenus() {
        assumeTrue("no game data: run importsTheDiscImageAndShowsTheMenu first", pack.exists())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the title menu", 30) { GameEvents.state == "menu" }
        assertEquals("at the title", 0, GameEvents.navDepth)
        shell("input keyevent KEYCODE_ENTER") // the highlighted entry: Course play
        waitFor("a sub-menu", 10) { GameEvents.navDepth > 0 }
        shell("input keyevent KEYCODE_BACK")
        waitFor("the title again", 10) { GameEvents.navDepth == 0 }
        Thread.sleep(500)
        assertEquals("the game is still open", Lifecycle.State.RESUMED, scenario.state)
        assertNoErrors()
        // At the title "back" leaves the app, which ends this process: not done here.
    }

    @Test
    fun playSurvivesGoingToTheBackground() {
        assumeTrue("no game data: run importsTheDiscImageAndShowsTheMenu first", pack.exists())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the title menu", 30) { GameEvents.state == "menu" }
        // Course play -> Beginner -> start: Enter until a stage runs.
        for (i in 0 until 8) {
            if (GameEvents.state == "play") break
            shell("input keyevent KEYCODE_ENTER")
            Thread.sleep(1500)
        }
        waitFor("a stage", 20) { GameEvents.state == "play" }
        Thread.sleep(2000)
        assertTrue("a stage is on screen", screenHasPicture())

        // Away (onStop: the window and its surface go) and back (a new surface).
        scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(2000)
        scenario.moveToState(Lifecycle.State.RESUMED)
        Thread.sleep(3000)

        assertNoErrors()
        assertEquals("still in the stage", "play", GameEvents.state)
        assertTrue("the stage is drawn again", screenHasPicture())
    }

    @Test
    fun aFileThatIsNotTheGameIsRefused() {
        pack.delete()
        val bad = "/sdcard/Download/not-a-disc-image.iso"
        // executeShellCommand runs no shell (no redirection): dd writes the file itself. 2 MB of noise.
        shell("dd if=/dev/urandom of=$bad bs=65536 count=32")
        // Make it visible to the picker (the media store indexes Download).
        shell("content call --uri content://media/external/file --method scan_file --arg $bad")
        try {
            ActivityScenario.launch(MainActivity::class.java)
            waitFor("the document picker", 30) { pickerShown() }
            val file = waitForNode(10) { find { it.text?.toString() == "not-a-disc-image.iso" } }
            assumeTrue("the test file is not shown in the picker", file != null)
            click(file!!)
            waitFor("a message to the player", 20) { GameEvents.messages.isNotEmpty() }
            waitFor("the document picker again", 10) { pickerShown() }
            assertEquals("still importing", "import", GameEvents.state)
            assertTrue("no game data", !pack.exists())
            assertNoErrors()
        } finally {
            shell("rm -f $bad")
            shell("content call --uri content://media/external/file --method scan_file --arg $bad")
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /**
     * Whether the screen shows a picture rather than one flat colour (black, a frozen clear colour): many
     * different colours in a coarse sample of it.
     */
    private fun screenHasPicture(): Boolean {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return false
        val colors = HashSet<Int>()
        for (y in 0 until shot.height step shot.height / 40) {
            for (x in 0 until shot.width step shot.width / 20) colors.add(shot.getPixel(x, y))
        }
        shot.recycle()
        return colors.size > 50
    }

    /**
     * Run a command as the shell user and wait for it to finish. No shell is involved: the command is split
     * at spaces, so no quotes, pipes or redirection.
     */
    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }

    /** Force a graphics backend (`debug.monkeyball.backend`, read by the Rust side at start), or none. */
    private fun setBackend(backend: String?) {
        shell("setprop debug.monkeyball.backend ${backend ?: "auto"}")
    }

    private fun startsOn(backend: String, expected: String) {
        setBackend(backend)
        ActivityScenario.launch(MainActivity::class.java)
        waitFor("the GPU", 30) { GameEvents.adapter != null }
        assertTrue("adapter ${GameEvents.adapter}", GameEvents.adapter!!.contains("($expected,"))
        waitFor("the first game state", 10) { GameEvents.state != null }
        assertNoErrors()
    }

    /** With the import screen asking: pick the disc image in the document picker, wait for the menu. */
    private fun importDiscImage() {
        waitFor("the document picker", 30) { pickerShown() }
        var image = findImage()
        if (image == null) {
            // Not in the folder the picker opened with: try Downloads.
            find { it.contentDescription?.toString() == "Show roots" }?.let(::click)
            Thread.sleep(1000)
            find { it.text?.toString() == "Downloads" }?.let(::click)
            image = waitForNode(10) { findImage() }
        }
        assumeTrue("no disc image (.rvz, .iso, .gcm) in the picker or in Downloads", image != null)
        click(image!!)
        waitFor("the title menu after the import", 180) { GameEvents.state == "menu" }
    }

    private fun findImage() = find {
        val name = it.text?.toString()?.lowercase() ?: return@find false
        name.endsWith(".rvz") || name.endsWith(".iso") || name.endsWith(".gcm")
    }

    private fun pickerShown() =
        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.contains("documentsui") == true

    /** The first node of the active window (depth first) that matches. */
    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (match(node)) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        return search(instrumentation.uiAutomation.rootInActiveWindow)
    }

    /**
     * Tap the middle of a node, as a finger would. The picker's file list reacts to touches, not to the
     * accessibility click action (that one does nothing there).
     */
    private fun click(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    private fun <T> waitForNode(seconds: Int, get: () -> T?): T? {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < until) {
            get()?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    /** Wait until `done`, failing early when the game reports a fatal error. */
    private fun waitFor(what: String, seconds: Int, done: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < until) {
            assertNoErrors()
            if (done()) return
            Thread.sleep(100)
        }
        fail("no $what after ${seconds}s (states so far: ${GameEvents.states})")
    }

    private fun assertNoErrors() {
        if (GameEvents.errors.isNotEmpty()) fail("the game reported: ${GameEvents.errors}")
    }
}
