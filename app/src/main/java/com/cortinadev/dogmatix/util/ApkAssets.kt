package com.cortinadev.dogmatix.util

import android.content.Context
import android.content.pm.PackageManager
import java.util.zip.ZipFile

/**
 * Reads assets bundled inside *another* installed app's APK (a frontend's default
 * configuration, for the one-button setups).
 *
 * The package must be listed in this app's manifest `<queries>`: Android 11+ package
 * visibility otherwise hides it and everything here answers as if it were not installed.
 */
object ApkAssets {

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess

    /** Text of [entry] inside [packageName]'s APK; null when the app (or the entry) is missing. */
    fun readText(context: Context, packageName: String, entry: String): String? {
        val apk = try {
            context.packageManager.getApplicationInfo(packageName, 0).sourceDir
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return runCatching {
            ZipFile(apk).use { zip ->
                zip.getEntry(entry)?.let { found ->
                    zip.getInputStream(found).use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }
        }.getOrNull()
    }
}
