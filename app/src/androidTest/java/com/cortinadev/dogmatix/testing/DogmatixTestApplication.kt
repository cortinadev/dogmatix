package com.cortinadev.dogmatix.testing

import dagger.hilt.android.testing.CustomTestApplication

/**
 * Hilt's test application built on [SandboxApplication]: Hilt generates
 * `DogmatixTestApplication_Application`, which [DogmatixTestRunner] starts instead of the app's.
 * `@HiltAndroidTest` classes get a fresh component per test; the others do not touch Hilt.
 */
@CustomTestApplication(SandboxApplication::class)
interface DogmatixTestApplication
