package com.example

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** All downloads and installation require an explicit tap. No background service. */
class AppUpdates(private val activity: AppCompatActivity) {
    private val prefs = activity.getSharedPreferences("updates", 0)
    private val apk get() = File(activity.filesDir, "updates/latest.apk")
    private var checking = false
    private var downloading: Job? = null
    private var prompt: AlertDialog? = null
    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canInstall()) installDownloaded() else message("Installation permission was not enabled. You can try again from Settings.")
    }

    fun showSettings() {
        val automatic = prefs.getBoolean("automatic", true)
        val choices = mutableListOf("Check for updates", "Automatic checks: ${if (automatic) "on" else "off"}")
        if (apk.exists()) choices.add("Install downloaded update")
        AlertDialog.Builder(activity)
            .setTitle("Settings · CleanGram ${BuildConfig.VERSION_NAME}")
            .setItems(choices.toTypedArray()) { _, index ->
                when (index) {
                    0 -> checkForUpdates(manual = true)
                    1 -> {
                        prefs.edit().putBoolean("automatic", !automatic).apply()
                        message(if (automatic) "Automatic update checks turned off" else "Automatic update checks turned on")
                    }
                    2 -> requestInstall()
                }
            }.setNegativeButton("Close", null).show()
    }

    fun onResume() {
        if (prefs.getBoolean("automatic", true) &&
            System.currentTimeMillis() - prefs.getLong("last_check", 0) >= 24 * 60 * 60 * 1000L) {
            checkForUpdates(manual = false)
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        if (checking || downloading?.isActive == true || prompt?.isShowing == true) {
            if (manual) message("An update check or download is already in progress")
            return
        }
        checking = true
        // Avoid repeatedly calling GitHub on each resume, including during outages.
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        if (manual) message("Checking for updates…")
        activity.lifecycleScope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    val connection = open(UpdateRelease.API)
                    try {
                        when (connection.responseCode) {
                            404 -> null // No published release yet.
                            200 -> {
                                val text = connection.inputStream.bufferedReader().use { reader ->
                                    val chars = CharArray(1024 * 1024 + 1)
                                    var count = 0
                                    while (count < chars.size) {
                                        val read = reader.read(chars, count, chars.size - count)
                                        if (read == -1) break
                                        count += read
                                    }
                                    check(count <= 1024 * 1024) { "Release response is too large" }
                                    String(chars, 0, count)
                                }
                                UpdateRelease.parse(text, BuildConfig.VERSION_CODE)
                            }
                            403, 429 -> error("Update checks are temporarily limited. Try again later.")
                            else -> error("Could not reach the update server. Try again later.")
                        }
                    } finally { connection.disconnect() }
                }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                if (release == null) {
                    if (manual) message("You’re up to date. No newer published release is available.")
                } else {
                    prompt = AlertDialog.Builder(activity)
                        .setTitle("Update available")
                        .setMessage("${release.name} is available. Download it now? Android will ask you to confirm installation.")
                        .setPositiveButton("Download update") { _, _ -> download(release) }
                        .setNegativeButton("Later", null).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (manual) message("Couldn’t check for updates. Check your connection and try again later.")
            } finally { checking = false }
        }
    }

    private fun download(release: UpdateRelease) {
        if (downloading?.isActive == true) return
        val progress = AlertDialog.Builder(activity).setTitle("Downloading update")
            .setMessage("Please keep CleanGram open while the APK downloads.")
            .setNegativeButton("Cancel") { _, _ -> downloading?.cancel() }
            .setCancelable(false).create()
        progress.show()
        downloading = activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    apk.parentFile!!.mkdirs()
                    val partial = File(apk.parentFile, "download.part")
                    try {
                        val connection = open(release.url)
                        try {
                            check(connection.responseCode == 200) { "Download failed" }
                            check(connection.url.protocol == "https") { "Download must use HTTPS" }
                            var total = 0L
                            val digest = MessageDigest.getInstance("SHA-256")
                            connection.inputStream.use { input ->
                                partial.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count == -1) break
                                        total += count
                                        check(total <= release.size && total <= UpdateRelease.MAX_APK_SIZE) { "Unexpected APK size" }
                                        output.write(buffer, 0, count)
                                        digest.update(buffer, 0, count)
                                    }
                                }
                            }
                            check(total == release.size) { "Incomplete download" }
                            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                            check(hash == release.sha256) { "Download checksum did not match" }
                            verifyApk(partial, release.code)
                            apk.delete()
                            check(partial.renameTo(apk)) { "Could not save APK" }
                        } finally { connection.disconnect() }
                    } finally { partial.delete() }
                }
                progress.dismiss()
                requestInstall()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message("Update download failed or could not be verified. Please try again.")
            } finally { progress.dismiss() }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, expectedCode: Int? = null) {
        val pm = activity.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: error("Invalid APK")
        val installed = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(archive.packageName == activity.packageName && code > BuildConfig.VERSION_CODE &&
            (expectedCode == null || code == expectedCode.toLong())) { "Wrong application or version" }
        val expected = installed.signatures?.map { it.toCharsString() }?.toSet()
        val actual = archive.signatures?.map { it.toCharsString() }?.toSet()
        check(!expected.isNullOrEmpty() && expected == actual) { "Signing certificate does not match" }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()

    private fun requestInstall() {
        if (!apk.exists()) { message("No downloaded update is available"); return }
        if (!canInstall()) {
            AlertDialog.Builder(activity).setTitle("Allow CleanGram updates")
                .setMessage("On the next Android screen, allow CleanGram to install apps. Then return here to confirm the update.")
                .setPositiveButton("Open Android settings") { _, _ ->
                    try {
                        permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")))
                    } catch (_: Exception) { message("Open Android settings and allow CleanGram to install apps.") }
                }.setNegativeButton("Later", null).show()
        } else installDownloaded()
    }

    private fun installDownloaded() {
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { verifyApk(apk) }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { message("Cannot install this update. Download it again from Settings.") }
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "CleanGram/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", if (url == UpdateRelease.API) "application/vnd.github+json" else "application/octet-stream")
    }

    fun close() {
        downloading?.cancel()
        prompt?.dismiss()
    }

    private fun message(text: String) {
        if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
