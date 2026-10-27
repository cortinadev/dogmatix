package com.cortinadev.dogmatix.ui

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.local.SettingsKeys
import com.cortinadev.dogmatix.data.local.dataStore
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.testing.TestData
import com.cortinadev.dogmatix.ui.common.Gamepad
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The app with the pad, end to end: keys go in through the instrumentation (like `adb shell input
 * keyevent`, so through MainActivity.dispatchKeyEvent and the Gamepad shortcuts) and the checks
 * read the Compose focus and Gamepad.currentRoute. The app runs in the sandboxed Hilt test
 * application: its own empty DataStore (onboarding done) and Room database, seeded here with one
 * console of games and one failed download that points at a closed local port, so nothing goes to
 * the network for real.
 *
 * Kept to what holds on any screen size: B never closes the app, ZL/ZR switch sections, LB/RB move
 * the focus between the filter panel and the list (landscape), A on a failed download retries it
 * without a crash. The full focus walk is in the tester's manual guide.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class GamepadSmokeTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject
    lateinit var db: DogmatixDatabase

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = TestData.context
    private var scenario: ActivityScenario<MainActivity>? = null
    /** The installed app's background scan job: the test app (empty settings) cancels it, it is put back. */
    private var scanJob: JobInfo? = null

    @Before
    fun setUp() = runBlocking {
        hilt.inject()
        // Process-wide pad state left by an earlier test.
        Gamepad.pointerHidden.value = false
        Gamepad.currentRoute = "home"
        scanJob = context.getSystemService(JobScheduler::class.java)?.getPendingJob(AUTO_SCAN_JOB_ID)
        context.dataStore.edit {
            it.clear()
            it[SettingsKeys.ONBOARDING_DONE] = true
            it[SettingsKeys.NOTIFICATION_PERMISSION_ASKED] = true
            it[SettingsKeys.UPDATE_AUTO_CHECK] = false
        }
        db.clearAllTables()
        db.manufacturerDao().insertManufacturer(TestData.manufacturer("test", "Test"))
        db.consoleDao().insertConsole(TestData.console(CONSOLE, "Test Console", manufacturerId = "test", shortName = "TEST"))
        val games = (1..30).map { n -> "Crystal Quest %02d".format(n) } + listOf("Zebra Run", "Moon Patrol")
        db.downloadableFileDao().insertFilesWithTags(
            games.map { TestData.webFile(CONSOLE, it, "$it (USA).zip", folder = "http://127.0.0.1:9/$CONSOLE") },
            games.map { listOf("USA", "EN") }
        )
        db.downloadHistoryDao().upsert(
            TestData.history(FAILED_FILE, DownloadStatus.FAILED, consoleId = CONSOLE, startedAt = 1_000L)
                .copy(name = FAILED_NAME, downloadUrl = "http://127.0.0.1:9/$CONSOLE/$FAILED_FILE")
        )
    }

    @After
    fun tearDown() {
        scenario?.close()
        runBlocking { context.dataStore.edit { it.clear() } }
        scanJob?.let { context.getSystemService(JobScheduler::class.java)?.schedule(it) }
    }

    // ---- Tests ----------------------------------------------------------------------------------

    @Test
    fun bNeverClosesTheAppAndTheTriggersSwitchSections() {
        launch()
        // The first D-pad press lands the focus somewhere visible.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(TIMEOUT) { focusedText() != null }
        repeat(6) { press(KeyEvent.KEYCODE_BUTTON_B) }
        assertAlive()
        assertEquals("home", Gamepad.currentRoute)

        press(KeyEvent.KEYCODE_BUTTON_R2)
        compose.waitUntil(TIMEOUT) { Gamepad.currentRoute == "downloads" }
        press(KeyEvent.KEYCODE_BUTTON_R2)
        compose.waitUntil(TIMEOUT) { Gamepad.currentRoute == "collection" }
        press(KeyEvent.KEYCODE_BUTTON_L2)
        press(KeyEvent.KEYCODE_BUTTON_L2)
        compose.waitUntil(TIMEOUT) { Gamepad.currentRoute == "home" }
        // From the far end the triggers wrap around (home → settings).
        press(KeyEvent.KEYCODE_BUTTON_L2)
        compose.waitUntil(TIMEOUT) { Gamepad.currentRoute == "settings" }
        repeat(4) { press(KeyEvent.KEYCODE_BUTTON_B) }
        assertAlive()
    }

    @Test
    fun shouldersMoveTheFocusBetweenFiltersAndListInLandscape() {
        // A search from a deep link shows the list (not the recommendations) with the seeded games.
        launch("dogmatix://library?q=quest")
        landscape()
        compose.waitUntil(TIMEOUT) { exists("Crystal Quest 01") }

        press(KeyEvent.KEYCODE_BUTTON_R1)
        compose.waitUntil(TIMEOUT) { focusedText()?.contains("Crystal Quest") == true }
        press(KeyEvent.KEYCODE_BUTTON_L1)
        compose.waitUntil(TIMEOUT) { focusedText().let { it != null && !it.contains("Crystal Quest") } }
        press(KeyEvent.KEYCODE_BUTTON_R1)
        compose.waitUntil(TIMEOUT) { focusedText()?.contains("Crystal Quest") == true }

        repeat(6) { press(KeyEvent.KEYCODE_BUTTON_B) }
        assertAlive()
        assertEquals("home", Gamepad.currentRoute)
    }

    @Test
    fun aOnAFailedDownloadRetriesItWithoutCrashing() {
        launch()
        press(KeyEvent.KEYCODE_BUTTON_R2)
        compose.waitUntil(TIMEOUT) { Gamepad.currentRoute == "downloads" && exists(FAILED_NAME) }
        // After a section switch the first D-pad press only brings the focus back.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText(FAILED_NAME, substring = true) and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitUntil(TIMEOUT) { focusedText()?.contains(FAILED_NAME) == true }

        press(KeyEvent.KEYCODE_BUTTON_A)
        // The retry restarts the row (new start time, DOWNLOADING) and then fails again: no folder, closed port.
        TestData.eventually("the retry restarted the row", TIMEOUT) {
            runBlocking { db.downloadHistoryDao().getByFileName(FAILED_FILE)!!.startedAt } > 1_000L
        }
        Thread.sleep(3_000)
        assertAlive()
        assertTrue(exists(FAILED_NAME))
        val status = runBlocking { db.downloadHistoryDao().getByFileName(FAILED_FILE)!!.status }
        assertTrue("status after the retry: $status", status in setOf("DOWNLOADING", "QUEUED", "STOPPED", "FAILED"))
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private fun launch(link: String? = null) {
        val intent = Intent(context, MainActivity::class.java)
        if (link != null) intent.setAction(Intent.ACTION_VIEW).setData(Uri.parse(link))
        val launched = ActivityScenario.launch<MainActivity>(intent).also { scenario = it }
        // The shell is up once its section tabs are drawn (in the app's language).
        var downloadsTab = ""
        launched.onActivity { downloadsTab = it.getString(R.string.nav_downloads) }
        compose.waitUntil(TIMEOUT) { exists(downloadsTab) }
    }

    private fun landscape() {
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        compose.waitUntil(TIMEOUT) {
            var landscape = false
            scenario!!.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            landscape
        }
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        Thread.sleep(400)
    }

    private fun exists(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** Texts (and descriptions) of the focused element, merged as a screen reader reads them; null when nothing has focus. */
    private fun focusedText(): String? =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.let { node ->
            buildString {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { append(it.text).append(' ') }
                node.config.getOrNull(SemanticsProperties.EditableText)?.let { append(it.text).append(' ') }
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { append(it).append(' ') }
            }
        }

    private fun assertAlive() {
        val current = scenario!!
        assertEquals(Lifecycle.State.RESUMED, current.state)
        current.onActivity { assertFalse("B closed the app", it.isFinishing) }
    }

    companion object {
        private const val TIMEOUT = 20_000L
        private const val CONSOLE = "test_console"
        private const val FAILED_NAME = "Broken Download"
        private const val FAILED_FILE = "Broken Download (USA).zip"
        /** AutoScanScheduler's job id. */
        private const val AUTO_SCAN_JOB_ID = 7301
    }
}
