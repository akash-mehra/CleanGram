package com.example

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** A published build newer than the running one. */
data class Update(val versionCode: Int, val title: String, val apkUrl: String)

/**
 * Self-update against the repository's GitHub releases.
 *
 * Releases are used rather than Actions artifacts because artifact downloads require
 * authentication, which would mean shipping a token inside the APK. Release assets on a public
 * repository are anonymous, so nothing secret is embedded here.
 */
object Updater {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/akash-mehra/CleanGram/releases/latest"
    private const val APK_FILE_NAME = "CleanGram-update.apk"

    private val client = OkHttpClient()

    /** The latest release when it is newer than this build, otherwise null. */
    suspend fun findUpdate(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)

                // CI tags each release "v<versionCode>", matching the APK's versionCode.
                val versionCode = json.optString("tag_name").removePrefix("v").toIntOrNull()
                    ?: return@use null
                if (versionCode <= BuildConfig.VERSION_CODE) return@use null

                val assets = json.optJSONArray("assets") ?: return@use null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val url = asset.optString("browser_download_url")
                    if (asset.optString("name").endsWith(".apk") && url.isNotEmpty()) {
                        return@use Update(
                            versionCode = versionCode,
                            title = json.optString("name").ifEmpty { "v$versionCode" },
                            apkUrl = url
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }

    /** Downloads into app-specific external storage, so no storage permission is needed. */
    fun enqueueDownload(context: Context, update: Update): Long {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, APK_FILE_NAME).delete() }

        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle(context.getString(R.string.app_name))
            .setDescription(context.getString(R.string.update_downloading))
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        return context.getSystemService(DownloadManager::class.java).enqueue(request)
    }

    fun install(context: Context, downloadId: Long) {
        val uri = context.getSystemService(DownloadManager::class.java)
            .getUriForDownloadedFile(downloadId) ?: return

        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
