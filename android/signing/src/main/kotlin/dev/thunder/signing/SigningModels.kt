package dev.thunder.signing

import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

data class SigningIdentity(
    val keyId: String,
    val targetPackageName: String,
    val certificateSha256: String,
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
)

data class SignedApkArtifact(
    val inputFile: File,
    val outputFile: File,
    val size: Long,
    val sha256: String,
)

data class SignedApkSet(
    val keyId: String,
    val certificateSha256: String,
    val artifacts: List<SignedApkArtifact>,
)

enum class SigningFailureCode {
    INVALID_TARGET,
    IDENTITY_STORAGE_FAILED,
    IDENTITY_CORRUPT,
    KEYSTORE_UNAVAILABLE,
    INPUT_INVALID,
    OUTPUT_UNAVAILABLE,
    SIGNING_FAILED,
    VERIFICATION_FAILED,
    BACKUP_INVALID_FORMAT,
    BACKUP_INVALID_PASSWORD,
    BACKUP_TARGET_MISMATCH,
    BACKUP_ACTIVE_IDENTITY,
    BACKUP_FAILED,
}

class SigningException(
    val code: SigningFailureCode,
    cause: Throwable? = null,
) : Exception(code.name, cause)

data class SigningIdentityBackupMetadata(
    val keyId: String,
    val targetPackageName: String,
    val certificateSha256: String,
)

internal object IdentityNames {
    fun validatePackageName(packageName: String) {
        require(PACKAGE_PATTERN.matches(packageName)) { "Invalid Android package name" }
    }

    fun storageName(packageName: String): String {
        validatePackageName(packageName)
        return sha256(packageName.toByteArray(Charsets.UTF_8)) + ".json"
    }

    fun additionalAuthenticatedData(packageName: String): ByteArray {
        validatePackageName(packageName)
        return "thunder-signing-identity-v1\u0000$packageName".toByteArray(Charsets.UTF_8)
    }

    fun sha256(bytes: ByteArray): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
}
