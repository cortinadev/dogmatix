package com.cortinadev.dogmatix.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guard of every other instrumented test: they run in the app under test (the debug build,
 * `com.cortinadev.dogmatix.debug`) but inside the sandboxed application, so nothing they write
 * reaches the installed app's files, settings or databases.
 */
@RunWith(AndroidJUnit4::class)
class SandboxTest {

    @Test
    fun testsRunInTheDebugBuildInsideTheSandbox() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, target.packageName)
        assertTrue(target.packageName.endsWith(".debug"))

        val app = TestData.context
        assertTrue("runner not in use: ${app.javaClass.name}", app is SandboxApplication)
        val sandbox = SandboxApplication.sandboxOf(app).canonicalPath
        listOf(app.filesDir, app.cacheDir, app.noBackupFilesDir, app.getDatabasePath("dogmatix_db"), app.getDir("x", 0)).forEach {
            assertTrue("$it is outside the sandbox", it.canonicalPath.startsWith("$sandbox/"))
        }
        // The real data directory is still the package's own.
        assertEquals(target.dataDir.canonicalPath, SandboxApplication.sandboxOf(app).parentFile!!.canonicalPath)
    }
}
