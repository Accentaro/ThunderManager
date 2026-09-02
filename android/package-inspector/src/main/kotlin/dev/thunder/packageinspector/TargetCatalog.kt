package dev.thunder.packageinspector

enum class DiscordChannel(val displayName: String) {
    STABLE("Stable"),
    BETA("Beta"),
    CANARY("Canary"),
}

data class DiscordTargetSpec(
    val packageName: String,
    val channel: DiscordChannel,
    val trustedSignerSha256: Set<String>,
)

data class ThunderCloneSpec(
    val sourcePackageName: String,
    val outputPackageName: String,
)

object DiscordTargetCatalog {
    private const val OFFICIAL_SIGNER_SHA256 =
        "3c39d23cf9367849a5c699395647fe0e5bfea5a1f1f40d8c717ddc70f8bfa113"

    val targets: List<DiscordTargetSpec> = listOf(
        DiscordTargetSpec("com.discord", DiscordChannel.STABLE, setOf(OFFICIAL_SIGNER_SHA256)),
        DiscordTargetSpec("com.discord.beta", DiscordChannel.BETA, setOf(OFFICIAL_SIGNER_SHA256)),
        DiscordTargetSpec("com.discord.canary", DiscordChannel.CANARY, setOf(OFFICIAL_SIGNER_SHA256)),
    )

    fun forPackage(packageName: String): DiscordTargetSpec = requireNotNull(
        targets.singleOrNull { it.packageName == packageName },
    ) { "Unsupported Discord package: $packageName" }
}

object ThunderCloneCatalog {
    const val OUTPUT_PACKAGE_NAME = "dev.thunder.app"

    fun forSource(sourcePackageName: String): ThunderCloneSpec {
        DiscordTargetCatalog.forPackage(sourcePackageName)
        return ThunderCloneSpec(
            sourcePackageName = sourcePackageName,
            outputPackageName = OUTPUT_PACKAGE_NAME,
        )
    }
}
