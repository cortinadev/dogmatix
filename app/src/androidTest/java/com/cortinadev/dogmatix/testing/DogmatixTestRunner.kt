package com.cortinadev.dogmatix.testing

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * `testInstrumentationRunner` of the app: every instrumented test runs in the sandboxed Hilt test
 * application ([SandboxApplication]), never in DogmatixApplication with the user's data.
 */
class DogmatixTestRunner : AndroidJUnitRunner() {

    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, DogmatixTestApplication_Application::class.java.name, context)

    override fun finish(resultCode: Int, results: Bundle?) {
        runCatching { SandboxApplication.wipe(targetContext) }
        super.finish(resultCode, results)
    }
}
