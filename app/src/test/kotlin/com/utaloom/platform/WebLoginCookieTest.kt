package com.utaloom.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLoginCookieTest {
    @Test fun matchesOnlyTheTargetSite() {
        assertTrue(WebLogin.cookieDomainMatches(".youtube.com", "music.youtube.com"))
        assertTrue(WebLogin.cookieDomainMatches("music.youtube.com", "music.youtube.com"))
        assertTrue(WebLogin.cookieDomainMatches(".spotify.com", "open.spotify.com"))
        assertFalse(WebLogin.cookieDomainMatches(".google.com", "music.youtube.com"))
        assertFalse(WebLogin.cookieDomainMatches(".tube.com", "music.youtube.com"))
        assertFalse(WebLogin.cookieDomainMatches(".youtube.com", "open.spotify.com"))
    }
}
