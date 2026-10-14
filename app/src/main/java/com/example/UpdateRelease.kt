package com.example

import org.json.JSONObject

/** Public releases use v<versionCode> tags and cleangram-<versionCode>.apk assets. */
data class UpdateRelease(val code: Int, val name: String, val url: String, val size: Long, val sha256: String) {
    companion object {
        const val API = "https://api.github.com/repos/akash-mehra/CleanGram/releases/latest"
        private const val PREFIX = "https://github.com/akash-mehra/CleanGram/releases/download/"
        const val MAX_APK_SIZE = 150L * 1024 * 1024

        fun parse(json: String, installedCode: Int): UpdateRelease? {
            val release = JSONObject(json)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
            val tag = release.optString("tag_name")
            val code = Regex("v([1-9][0-9]*)").matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (code <= installedCode) return null
            val assets = release.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") != "cleangram-$code.apk") continue
                val url = asset.optString("browser_download_url")
                val size = asset.optLong("size")
                val digest = asset.optString("digest").removePrefix("sha256:")
                if (!url.startsWith("$PREFIX$tag/") || size !in 1..MAX_APK_SIZE ||
                    !Regex("[a-fA-F0-9]{64}").matches(digest)) continue
                return UpdateRelease(code, release.optString("name").ifBlank { tag }, url, size, digest.lowercase())
            }
            return null
        }
    }
}
