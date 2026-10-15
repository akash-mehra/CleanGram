package com.example

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShellNavigationTest {
    @Test fun `shell routes are origin restricted and classify website navigation`() {
        assertEquals(ShellNavigation.Destination.HOME, ShellNavigation.destination("https://www.instagram.com/"))
        assertEquals(ShellNavigation.Destination.MESSAGES, ShellNavigation.destination("https://www.instagram.com/direct/t/123/"))
        assertEquals(ShellNavigation.Destination.PROFILE, ShellNavigation.destination("https://www.instagram.com/my.account/"))
        for (url in listOf("https://instagram.com.evil.test/", "http://www.instagram.com/", "https://www.instagram.com:444/", "file:///direct/inbox/", "https://www.instagram.com/accounts/login/", "https://www.instagram.com/p/shared/")) {
            assertNull(url, ShellNavigation.destination(url))
        }
    }

    @Test fun `profile fallback accepts only a username and never a route or URL`() {
        assertEquals("/my.account/", ShellNavigation.profilePath(" @my.account "))
        for (name in listOf("", ".", "..", "reels", "EXPLORE", "direct", "https://evil.test/", "../accounts/login", "name?param=x")) {
            assertNull(name, ShellNavigation.profilePath(name))
        }
    }

    @Test fun `IME insets hide the dock without stacking system and keyboard gaps`() {
        val result = ShellLayout.insets(0, 24, 0, 24, 300, true, false)
        assertEquals(300, result.bottom)
        assertFalse(result.showDock)
        assertEquals(24, ShellLayout.insets(0, 24, 0, 24, 0, false, false).bottom)
        assertTrue(ShellLayout.insets(0, 24, 0, 24, 0, false, false).showDock)
    }

    @Test fun `fullscreen preserves safe edges but never reserves dock or stale keyboard space`() {
        val result = ShellLayout.insets(32, 0, 24, 0, 300, true, true)
        assertEquals(32, result.left)
        assertEquals(24, result.right)
        assertEquals(0, result.bottom)
        assertFalse(result.showDock)
    }
}
