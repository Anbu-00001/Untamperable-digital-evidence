package com.realitylock.app.forensics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the EXIF decision rules. */
class ExifRulesTest {

    @Test
    fun `known editors are recognised, case-insensitively`() {
        assertTrue(ExifRules.isEditorSoftware("Adobe Photoshop 25.0 (Windows)"))
        assertTrue(ExifRules.isEditorSoftware("GIMP 2.10.36"))
        assertTrue(ExifRules.isEditorSoftware("snapseed"))
    }

    @Test
    fun `a camera app is not flagged as an editor`() {
        // This is exactly what our own OnePlus captures carry — must not fire.
        assertFalse(ExifRules.isEditorSoftware("MediaTek Camera Application"))
        assertFalse(ExifRules.isEditorSoftware("HDR+ 1.0"))
    }

    @Test
    fun `absent or blank software is not an editor`() {
        assertFalse(ExifRules.isEditorSoftware(null))
        assertFalse(ExifRules.isEditorSoftware(""))
        assertFalse(ExifRules.isEditorSoftware("   "))
    }

    @Test
    fun `a later modify time than capture time is flagged`() {
        val result = ExifRules.modifyAfterOriginal(
            modifyDateTime = "2026:07:24 18:30:00",
            dateTimeOriginal = "2026:07:24 09:12:25",
        )
        assertTrue(result!!)
    }

    @Test
    fun `equal capture and modify times are not flagged`() {
        // A straight-from-camera JPEG has these equal — must not fire.
        val ts = "2026:07:25 00:59:47"
        assertFalse(ExifRules.modifyAfterOriginal(ts, ts)!!)
    }

    @Test
    fun `an earlier modify time than capture is not flagged`() {
        val result = ExifRules.modifyAfterOriginal(
            modifyDateTime = "2026:07:24 09:00:00",
            dateTimeOriginal = "2026:07:24 09:12:25",
        )
        assertFalse(result!!)
    }

    @Test
    fun `a missing or unparseable timestamp yields no signal, not a false positive`() {
        assertNull(ExifRules.modifyAfterOriginal(null, "2026:07:24 09:12:25"))
        assertNull(ExifRules.modifyAfterOriginal("2026:07:24 09:12:25", ""))
        assertNull(ExifRules.modifyAfterOriginal("not a date", "2026:07:24 09:12:25"))
    }
}
