package dev.thunder.packageinstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class InstallInputRulesTest {
    @Test
    fun rejectsPackageNamesWithoutMultipleSegments() {
        val error = assertThrows(InstallException::class.java) {
            InstallInputRules.requirePackageName("discord")
        }
        assertEquals(InstallFailureCode.INVALID_PACKAGE_NAME, error.code)
    }

    @Test
    fun rejectsDuplicateArtifactNamesBeforeReadingFiles() {
        val missing = File("does-not-exist.apk")
        val error = assertThrows(InstallException::class.java) {
            InstallInputRules.requireArtifacts(
                listOf(
                    InstallArtifact(missing, "base.apk"),
                    InstallArtifact(missing, "base.apk"),
                ),
            )
        }
        assertEquals(InstallFailureCode.INVALID_ARTIFACT_SET, error.code)
    }

    @Test
    fun rejectsUnsafeSessionNames() {
        val error = assertThrows(InstallException::class.java) {
            InstallInputRules.requireArtifacts(listOf(InstallArtifact(File("missing"), "../base.apk")))
        }
        assertEquals(InstallFailureCode.INVALID_ARTIFACT_SET, error.code)
    }
}
