package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.cortinadev.dogmatix.data.model.GitHubRelease
import com.cortinadev.dogmatix.util.ToastUtil
import com.cortinadev.dogmatix.util.VersionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VersionCheckerService @Inject constructor(
    private val gson: Gson
) {
    
    companion object {
        private const val GITHUB_API_URL = "https://api.github.com/repos/cortinadev/dogmatix/releases"
        private const val REQUEST_TIMEOUT = 10000 // 10 seconds
    }
    
    suspend fun checkForUpdates(context: Context) {
        try {
            val currentVersion = getCurrentVersion(context)
            val latestRelease = fetchLatestRelease()
            
            if (latestRelease != null && isNewerVersion(latestRelease.tagName, currentVersion)) {
                withContext(Dispatchers.Main) {
                    ToastUtil.showInfo(
                        context, 
                        "Update available! Latest version: ${latestRelease.tagName}"
                    )
                }
            }
        } catch (_: Exception) {
        }
    }
    
    private fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName?.takeIf { it.isNotEmpty() } ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0" 
        }
    }
    
    private suspend fun fetchLatestRelease(): GitHubRelease? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = REQUEST_TIMEOUT
                connection.readTimeout = REQUEST_TIMEOUT
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "Milou-Android-App")
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val releases = gson.fromJson<List<GitHubRelease>>(
                        response, 
                        object : TypeToken<List<GitHubRelease>>() {}.type
                    )
                    
                    // Return the first non-prerelease, non-draft release
                    releases.firstOrNull { !it.prerelease && !it.draft }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        return try {
            VersionUtils.compareVersions(latestVersion, currentVersion) > 0
        } catch (e: Exception) {
            false
        }
    }
}
