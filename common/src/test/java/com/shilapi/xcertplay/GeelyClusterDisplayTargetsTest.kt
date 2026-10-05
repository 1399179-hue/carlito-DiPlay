package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 领克 03 preference is firmware data, so it must identify that unit's cluster without ever
 * becoming a filter that rejects every candidate on a different firmware.
 */
class GeelyClusterDisplayTargetsTest {
    @Test
    fun recognisesTheLynk03ClusterDisplay() {
        assertTrue(isLynk03Cluster("local:2", 1920, 720))
    }

    @Test
    fun rejectsAnythingElse() {
        // A renumbered display on another firmware, and the same id at another size.
        assertFalse(isLynk03Cluster("local:3", 1920, 720))
        assertFalse(isLynk03Cluster("local:2", 1280, 720))
        assertFalse(isLynk03Cluster("local:2", 1920, 480))
        assertFalse(isLynk03Cluster("", 1920, 720))
        assertFalse(isLynk03Cluster("local:2", 0, 0))
    }
}
