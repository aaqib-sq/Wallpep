package dev.wallpep.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CutoutCacheNamesTest {
    private val photo = "background-3b70bf09-554e-42fb-80dc-45f67be67335.img"

    @Test fun sidecarsAndTemporaryFilesMapBackToTheirOwner() {
        val cutout = cutoutFileName(photo)!!
        val failure = cutoutFailureFileName(photo)!!
        assertEquals(photo, cutoutBackgroundName(cutout))
        assertEquals(photo, cutoutBackgroundName(failure))
        assertEquals(photo, cutoutBackgroundName(".$cutout.tmp"))
    }

    @Test fun pathsAndUnrelatedFilesAreNeverRecognized() {
        assertNull(cutoutFileName("../../private"))
        assertNull(cutoutFailureFileName("content://gallery/photo"))
        assertNull(cutoutBackgroundName("cutout-../../private.png"))
        assertNull(cutoutBackgroundName("notes.png"))
        assertNull(cutoutBackgroundName("cutout-$photo.png/other"))
    }
}
