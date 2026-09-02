package dev.thunder.manager

import org.junit.Assert.assertThrows
import org.junit.Test

class ManagerApkIdentityPolicyTest {
    @Test
    fun acceptsOnlyANewerApkWithTheSamePackageVersionAndSigner() {
        ManagerApkIdentityPolicy.requireSafeUpdate(
            installed = identity(versionName = "0.0.1", versionCode = 1),
            candidate = identity(versionName = "0.0.2", versionCode = 2),
            releaseVersion = "0.0.2",
        )
    }

    @Test
    fun rejectsWrongPackageOrReleaseVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            ManagerApkIdentityPolicy.requireSafeUpdate(
                installed = identity(),
                candidate = identity(packageName = "dev.thunder.other", versionName = "0.0.2", versionCode = 2),
                releaseVersion = "0.0.2",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManagerApkIdentityPolicy.requireSafeUpdate(
                installed = identity(),
                candidate = identity(versionName = "0.0.3", versionCode = 2),
                releaseVersion = "0.0.2",
            )
        }
    }

    @Test
    fun rejectsEqualOlderOrMismatchedSignerUpdates() {
        for (versionCode in listOf(1L, 0L)) {
            assertThrows(IllegalArgumentException::class.java) {
                ManagerApkIdentityPolicy.requireSafeUpdate(
                    installed = identity(),
                    candidate = identity(versionName = "0.0.2", versionCode = versionCode),
                    releaseVersion = "0.0.2",
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManagerApkIdentityPolicy.requireSafeUpdate(
                installed = identity(),
                candidate = identity(versionName = "0.0.2", versionCode = 2, signerSha256 = setOf("b".repeat(64))),
                releaseVersion = "0.0.2",
            )
        }
    }

    @Test
    fun rejectsMultipleSigners() {
        assertThrows(IllegalArgumentException::class.java) {
            ManagerApkIdentityPolicy.requireSafeUpdate(
                installed = identity(),
                candidate = identity(
                    versionName = "0.0.2",
                    versionCode = 2,
                    signerSha256 = setOf("a".repeat(64), "b".repeat(64)),
                ),
                releaseVersion = "0.0.2",
            )
        }
    }

    private fun identity(
        packageName: String = "dev.thunder.manager",
        versionName: String = "0.0.1",
        versionCode: Long = 1,
        signerSha256: Set<String> = setOf("a".repeat(64)),
    ) = ManagerApkIdentity(packageName, versionName, versionCode, signerSha256)
}
