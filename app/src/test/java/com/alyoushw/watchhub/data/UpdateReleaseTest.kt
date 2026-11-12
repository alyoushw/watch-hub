package com.alyoushw.watchhub.data

import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private fun release(tag: String = "build-130", asset: String = "WatchHub.apk", extra: String = "") = """{
        "tag_name":"$tag", "body":"New release", $extra
        "assets":[{"name":"$asset","browser_download_url":"https://github.com/alyoushw/watch-hub/releases/download/$tag/$asset"}]
    }"""

    @Test fun `existing build tag and APK format is preserved`() {
        val update = checkNotNull(parseAppUpdate(release()))
        assertEquals(130, update.build)
        assertEquals("New release", update.notes)
        assertTrue(isNewerBuild(129, update.build))
        assertTrue(isNewerBuild(0, update.build))
        assertFalse(isNewerBuild(130, update.build))
        assertFalse(isNewerBuild(131, update.build))
    }

    @Test fun `drafts prereleases malformed tags and noncanonical assets are ignored`() {
        assertNull(parseAppUpdate(release(extra = "\"draft\":true,")))
        assertNull(parseAppUpdate(release(extra = "\"prerelease\":true,")))
        assertNull(parseAppUpdate(release("130")))
        assertNull(parseAppUpdate(release("build-0")))
        assertNull(parseAppUpdate(release(asset = "other.apk")))
        assertNull(parseAppUpdate("rate limited"))
        assertNull(parseAppUpdate("{}"))
    }

    @Test fun `APK must belong to this repository and build`() {
        assertNull(parseAppUpdate(release().replace("https://github.com/", "http://github.com/")))
        assertNull(parseAppUpdate(release().replace("github.com/alyoushw", "github.com/another")))
        assertNull(parseAppUpdate(release().replace("/download/build-130/", "/download/build-1/")))
    }
}
