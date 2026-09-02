package dev.thunder.manager

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import dev.thunder.updateclient.ManagerUpdateCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale

internal data class ManagerApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
)

internal data class VerifiedManagerUpdate(
    val candidate: ManagerUpdateCandidate,
    val contentUri: Uri,
)

private const val MANAGER_PACKAGE_NAME = "dev.thunder.manager"

internal object ManagerApkIdentityPolicy {
    fun requireSafeUpdate(
        installed: ManagerApkIdentity,
        candidate: ManagerApkIdentity,
        releaseVersion: String,
    ) {
        require(installed.packageName == MANAGER_PACKAGE_NAME) {
            "The installed Manager package identity is unexpected."
        }
        require(candidate.packageName == installed.packageName) {
            "The downloaded APK belongs to a different application."
        }
        require(candidate.versionName == releaseVersion) {
            "The downloaded APK version does not match its release metadata."
        }
        require(candidate.versionCode > installed.versionCode) {
            "The downloaded Manager APK is not a newer Android version."
        }
        require(installed.signerSha256.size == 1 && candidate.signerSha256.size == 1) {
            "ThunderManager updates require exactly one signing identity."
        }
        require(candidate.signerSha256.single().equals(installed.signerSha256.single(), ignoreCase = true)) {
            "The downloaded Manager APK does not use the installed app's signing identity."
        }
    }
}

internal object ManagerApkUpdateVerifier {
    suspend fun verify(context: Context, update: ManagerUpdateCandidate): VerifiedManagerUpdate =
        withContext(Dispatchers.IO) {
            update.verifyIntegrity()
            val applicationContext = context.applicationContext
            val allowedDirectory = ManagerUpdateStorage.downloads(applicationContext).canonicalFile
            val apk = update.apk.canonicalFile
            require(apk.isFile && apk.parentFile == allowedDirectory) {
                "The downloaded Manager APK is outside private update storage."
            }

            val packageManager = applicationContext.packageManager
            val installed = packageManager.requireInstalledIdentity(applicationContext.packageName)
            val candidate = packageManager.requireArchiveIdentity(apk.absolutePath)
            ManagerApkIdentityPolicy.requireSafeUpdate(
                installed = installed,
                candidate = candidate,
                releaseVersion = update.manifest.version.toString(),
            )
            val uri = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.updates",
                apk,
            )
            VerifiedManagerUpdate(update, uri)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.requireInstalledIdentity(packageName: String): ManagerApkIdentity {
        val info = getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        return info.toIdentity()
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.requireArchiveIdentity(apkPath: String): ManagerApkIdentity {
        val info = getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IllegalArgumentException("Android could not verify the downloaded Manager APK.")
        return info.toIdentity()
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.toIdentity(): ManagerApkIdentity {
        val signers = signingInfo?.apkContentsSigners
            ?: throw IllegalArgumentException("Android could not read the APK signing identity.")
        return ManagerApkIdentity(
            packageName = packageName,
            versionName = versionName ?: throw IllegalArgumentException("The Manager APK has no version name."),
            versionCode = longVersionCode,
            signerSha256 = signers.map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString(separator = "") { byte ->
                        String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
                    }
            }.toSet(),
        )
    }
}
