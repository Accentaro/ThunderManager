package dev.thunder.patchdomain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotModelsTest {
    @Test
    fun assignsDeterministicPrivateArtifactNames() {
        assertEquals("base.apk", SnapshotFileNames.forArtifact(index = 0, isBase = true))
        assertEquals("split-001.apk", SnapshotFileNames.forArtifact(index = 1, isBase = false))
        assertEquals("split-042.apk", SnapshotFileNames.forArtifact(index = 42, isBase = false))
    }

    @Test
    fun rejectsBaseAtAnyNonzeroIndex() {
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotFileNames.forArtifact(index = 1, isBase = true)
        }
    }

    @Test
    fun reportsBoundedProgressFraction() {
        assertEquals(0.5f, SnapshotProgress(50, 100).fraction)
        assertEquals(1f, SnapshotProgress(0, 0).fraction)
        assertEquals(1f, SnapshotProgress(101, 100).fraction)
    }

    @Test
    fun rejectsUnsafeArtifactIndexMapping() {
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotFileNames.forArtifact(index = 0, isBase = false)
        }
    }
}
