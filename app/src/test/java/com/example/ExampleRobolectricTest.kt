package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("CleanGram", appName)
  }

  @Test
  fun `verify reels url detection`() {
    assertEquals(true, MainActivity.isReelsUrl("https://www.instagram.com/reels/"))
    assertEquals(false, MainActivity.isReelsUrl("https://www.instagram.com/reel/12345/"))
    assertEquals(true, MainActivity.isReelsUrl("https://www.instagram.com/reels/?source=nav"))
    assertEquals(false, MainActivity.isReelsUrl("https://example.com/reels/"))
    assertEquals(false, MainActivity.isReelsUrl("https://www.instagram.com/direct/t/123/?next=/reels/"))
    assertEquals(false, MainActivity.isReelsUrl("https://www.instagram.com/"))
  }

  @Test
  fun `verify instagram host detection`() {
    assertEquals(true, MainActivity.isInstagramHost("instagram.com"))
    assertEquals(true, MainActivity.isInstagramHost("www.instagram.com"))
    assertEquals(false, MainActivity.isInstagramHost("google.com"))
  }
}
