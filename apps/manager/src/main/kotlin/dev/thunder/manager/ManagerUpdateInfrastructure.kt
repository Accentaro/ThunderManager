package dev.thunder.manager

import android.content.Context
import dev.thunder.updateclient.CheckedRelease
import dev.thunder.updateclient.HttpsArtifactDownloader
import dev.thunder.updateclient.ManagerSelfUpdateDownloader
import dev.thunder.updateclient.ManagerUpdateCandidate
import dev.thunder.updateclient.ManagerUpdateDownloadState
import dev.thunder.updateclient.ReleaseCheckCache
import dev.thunder.updateclient.ReleaseCheckSource
import dev.thunder.updateclient.ReleaseProduct
import dev.thunder.updateclient.SemanticVersion
import dev.thunder.updateclient.StableReleaseManifest
import dev.thunder.updateclient.StableReleaseChecker
import dev.thunder.updateclient.ThunderManagerReleaseManifest
import dev.thunder.updateclient.ThunderReleaseManifest
import dev.thunder.updateclient.ThunderRuntimeUpdateDownloader
import dev.thunder.updateclient.VerifiedRuntimeArtifact
import dev.thunder.updateclient.VerifiedRuntimeStore
import kotlinx.coroutines.flow.StateFlow

internal object ManagerUpdateStorage {
    const val RUNTIME_STORE_DIRECTORY = "verified-thunder-runtimes"
    private const val CHECK_CACHE_DIRECTORY = "release-checks"
    private const val DOWNLOAD_DIRECTORY = "release-downloads"

    fun checkCache(context: Context) = context.noBackupFilesDir.resolve(CHECK_CACHE_DIRECTORY)
    fun downloads(context: Context) = context.cacheDir.resolve(DOWNLOAD_DIRECTORY)
}

internal data class ProductReleaseCheck<T : StableReleaseManifest>(
    val manifest: T,
    val source: ReleaseCheckSource,
) {
    fun isNewerThan(version: SemanticVersion): Boolean = manifest.version > version
}

internal interface ManagerUpdateGateway {
    val bundledRuntimeVersion: SemanticVersion
    val managerVersion: SemanticVersion
    val managerDownloadState: StateFlow<ManagerUpdateDownloadState>

    suspend fun checkThunder(forceRefresh: Boolean = false): ProductReleaseCheck<ThunderReleaseManifest>
    suspend fun checkManager(forceRefresh: Boolean = false): ProductReleaseCheck<ThunderManagerReleaseManifest>
    suspend fun downloadThunder(
        manifest: ThunderReleaseManifest,
        installedRuntimeVersion: SemanticVersion?,
    ): VerifiedRuntimeArtifact
    suspend fun downloadManager(manifest: ThunderManagerReleaseManifest): ManagerUpdateCandidate
    suspend fun clearManagerDownload()
}

/**
 * Owns only update transport and verified artifact state. Application startup and UI code can call
 * [checkThunder] and [checkManager] without blocking startup, then feed the resulting state into
 * the existing Manager screen and patch/install flows.
 */
internal class ManagerUpdateInfrastructure(context: Context) : ManagerUpdateGateway {
    private val applicationContext = context.applicationContext
    private val downloader = HttpsArtifactDownloader()
    private val checker = StableReleaseChecker(
        downloader,
        ReleaseCheckCache(ManagerUpdateStorage.checkCache(applicationContext)),
    )
    private val runtimeStore = VerifiedRuntimeStore(
        applicationContext.filesDir.resolve(ManagerUpdateStorage.RUNTIME_STORE_DIRECTORY),
    )
    private val runtimeUpdater = ThunderRuntimeUpdateDownloader(
        downloader,
        ManagerUpdateStorage.downloads(applicationContext),
        runtimeStore,
    )
    override val bundledRuntimeVersion = SemanticVersion.parseStableRelease(
        BuildConfig.BUNDLED_THUNDER_RUNTIME_VERSION,
    )
    override val managerVersion = SemanticVersion.parse(BuildConfig.VERSION_NAME)
    private val managerUpdater = ManagerSelfUpdateDownloader(
        downloader,
        ManagerUpdateStorage.downloads(applicationContext),
        managerVersion,
    )

    override val managerDownloadState: StateFlow<ManagerUpdateDownloadState> = managerUpdater.state

    override suspend fun checkThunder(forceRefresh: Boolean): ProductReleaseCheck<ThunderReleaseManifest> =
        checker.check(ReleaseProduct.THUNDER, forceRefresh).requireManifest()

    override suspend fun checkManager(forceRefresh: Boolean): ProductReleaseCheck<ThunderManagerReleaseManifest> =
        checker.check(ReleaseProduct.THUNDER_MANAGER, forceRefresh).requireManifest()

    override suspend fun downloadThunder(
        manifest: ThunderReleaseManifest,
        installedRuntimeVersion: SemanticVersion?,
    ): VerifiedRuntimeArtifact {
        val installedFloor = listOfNotNull(
            bundledRuntimeVersion,
            installedRuntimeVersion,
        ).maxOrNull() ?: bundledRuntimeVersion
        require(manifest.version > installedFloor) {
            "Thunder runtime update is not newer than the installed version"
        }
        return runtimeUpdater.downloadAndStore(manifest, installedFloor)
    }

    suspend fun latestVerifiedRuntime(): VerifiedRuntimeArtifact? = runtimeStore.latest()

    override suspend fun downloadManager(manifest: ThunderManagerReleaseManifest): ManagerUpdateCandidate =
        managerUpdater.download(manifest)

    override suspend fun clearManagerDownload() = managerUpdater.clear()

    private inline fun <reified T : StableReleaseManifest> CheckedRelease.requireManifest(): ProductReleaseCheck<T> {
        val typed = manifest as? T ?: error("Update checker returned the wrong product manifest")
        return ProductReleaseCheck(typed, source)
    }
}
