package com.example

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateReleaseTest {
    private fun release(code: Int = 42): JSONObject = JSONObject().apply {
        put("tag_name", "v$code")
        put("name", "CleanGram 1.1.$code")
        put("assets", JSONArray().put(JSONObject().apply {
            put("name", "cleangram-$code.apk")
            put("browser_download_url", "https://github.com/akash-mehra/CleanGram/releases/download/v$code/cleangram-$code.apk")
            put("size", 1024)
            put("digest", "sha256:" + "a".repeat(64))
        }))
    }

    @Test fun `offer only a strictly newer published release`() {
        assertEquals(42, UpdateRelease.parse(release().toString(), 41)?.code)
        assertNull(UpdateRelease.parse(release().toString(), 42))
        assertNull(UpdateRelease.parse(release().toString(), 43))
        assertNull(UpdateRelease.parse(release().put("draft", true).toString(), 41))
        assertNull(UpdateRelease.parse(release().put("prerelease", true).toString(), 41))
        assertNull(UpdateRelease.parse(release().put("tag_name", "v1.1.42").toString(), 41))
    }

    @Test fun `reject missing assets and preview packages`() {
        assertNull(UpdateRelease.parse(release().put("assets", JSONArray()).toString(), 41))
        val json = release()
        json.getJSONArray("assets").getJSONObject(0).put("name", "app-debug.apk")
        assertNull(UpdateRelease.parse(json.toString(), 41))
    }

    @Test fun `reject untrusted download URLs invalid digests and oversized downloads`() {
        for ((field, value) in listOf(
            "browser_download_url" to "https://example.com/cleangram-42.apk",
            "browser_download_url" to "http://github.com/akash-mehra/CleanGram/releases/download/v42/cleangram-42.apk",
            "digest" to "sha256:invalid",
            "size" to 0,
            "size" to UpdateRelease.MAX_APK_SIZE + 1
        )) {
            val json = release()
            json.getJSONArray("assets").getJSONObject(0).put(field, value)
            assertNull("Must reject $field=$value", UpdateRelease.parse(json.toString(), 41))
        }
    }
}
