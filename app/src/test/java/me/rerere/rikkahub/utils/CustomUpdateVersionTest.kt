package me.rerere.rikkahub.utils

import org.junit.Assert.*
import org.junit.Test

class CustomUpdateVersionTest {
    @Test fun mikuRebrandRetainsCustomBuildUpdateProtection() {
        assertTrue(isCustomBuild("2.5.1-miku.20260916.5"))
        assertEquals(0, Version("2.5.1").compareTo(officialComparisonVersion("2.5.1-miku.20260916.5")))
        assertTrue(Version("2.5.2") > officialComparisonVersion("2.5.1-miku.20260916.5"))
        assertFalse(isCustomBuild("2.5.1-miku.other"))
    }

    @Test fun sameOfficialVersionDoesNotOfferFalseUpdateForCustomBuild() {
        val current = officialComparisonVersion("2.5.1-sakura.20260915.4-ui")
        assertEquals(0, Version("2.5.1").compareTo(current))
        assertFalse(Version("2.5.1") > current)
    }

    @Test fun newerOfficialVersionRemainsAvailable() {
        assertTrue(Version("2.5.2") > officialComparisonVersion("2.5.1-sakura.20260915.4-ui"))
        assertTrue(isCustomBuild("2.5.1-sakura.20260915.4"))
    }

    @Test fun ordinaryPrereleasesAndUnrelatedSakuraTagsRetainSemverMeaning() {
        listOf("2.5.1-beta.1", "2.5.1-sakura", "2.5.1-sakura.other", "2.5.1").forEach {
            assertFalse(isCustomBuild(it))
            assertEquals(Version(it), officialComparisonVersion(it))
        }
        assertTrue(Version("2.5.1") > officialComparisonVersion("2.5.1-beta.1"))
    }

    @Test fun customBuildOnTopOfPrereleasePreservesItsBasePrerelease() {
        assertEquals(Version("2.6.0-beta.1"), officialComparisonVersion("2.6.0-beta.1-sakura.20260915.4-ui"))
    }
}
