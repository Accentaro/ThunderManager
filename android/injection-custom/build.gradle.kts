import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

abstract class PreparePinnedThunderRuntime : DefaultTask() {
    @get:Input
    abstract val runtimeVersion: Property<String>

    @get:Input
    abstract val runtimeUrl: Property<String>

    @get:Input
    abstract val expectedSize: Property<Long>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pinFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeOverride: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        val expectedBytes = expectedSize.get()
        val expectedDigest = expectedSha256.get()
        val sourceOverride = runtimeOverride.orNull?.asFile
        val bytes = if (sourceOverride != null) {
            if (!sourceOverride.isFile) {
                throw GradleException("Pinned Thunder runtime override is not a regular file: $sourceOverride")
            }
            if (sourceOverride.length() != expectedBytes) {
                throw GradleException(
                    "Pinned Thunder runtime size mismatch: expected $expectedBytes bytes, got ${sourceOverride.length()}",
                )
            }
            Files.readAllBytes(sourceOverride.toPath())
        } else {
            var current = URI.create(runtimeUrl.get())
            var redirects = 0
            var downloaded: ByteArray? = null
            while (downloaded == null) {
                if (!current.scheme.equals("https", ignoreCase = true)) {
                    throw GradleException("Pinned Thunder runtime download refused a non-HTTPS URL")
                }
                val connection = current.toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Accept", "application/octet-stream")
                connection.setRequestProperty("User-Agent", "ThunderManager-Gradle")
                try {
                    when (connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            val advertisedLength = connection.contentLengthLong
                            if (advertisedLength >= 0 && advertisedLength != expectedBytes) {
                                throw GradleException(
                                    "Pinned Thunder runtime server size mismatch: expected $expectedBytes bytes, got $advertisedLength",
                                )
                            }
                            downloaded = connection.inputStream.use { input ->
                                val outputBytes = ByteArrayOutputStream(expectedBytes.toInt())
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (outputBytes.size().toLong() + count > expectedBytes) {
                                        throw GradleException("Pinned Thunder runtime download exceeded its exact size")
                                    }
                                    outputBytes.write(buffer, 0, count)
                                }
                                outputBytes.toByteArray()
                            }
                        }

                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307,
                        308,
                        -> {
                            if (++redirects > 5) {
                                throw GradleException("Pinned Thunder runtime download exceeded redirect limit")
                            }
                            val location = connection.getHeaderField("Location")
                                ?: throw GradleException("Pinned Thunder runtime redirect omitted Location")
                            current = current.resolve(location)
                        }

                        else -> throw GradleException(
                            "Pinned Thunder runtime download failed with HTTP ${connection.responseCode}",
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }
            requireNotNull(downloaded)
        }

        if (bytes.size.toLong() != expectedBytes) {
            throw GradleException(
                "Pinned Thunder runtime size mismatch: expected $expectedBytes bytes, got ${bytes.size}",
            )
        }
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (actualSha256 != expectedDigest) {
            throw GradleException(
                "Pinned Thunder runtime SHA-256 mismatch: expected $expectedDigest, got $actualSha256",
            )
        }

        val destination = outputFile.get().asFile.toPath()
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".runtime-", ".part")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        logger.lifecycle(
            "Verified Thunder runtime v${runtimeVersion.get()} ($expectedBytes bytes, SHA-256 $expectedDigest)",
        )
    }
}

android {
    namespace = "dev.thunder.injection.custom"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val bootstrapAar = project(":android:bootstrap").layout.buildDirectory.file("outputs/aar/bootstrap-release.aar")
val extractedBootstrap = layout.buildDirectory.dir("intermediates/thunder-bootstrap/classes")
val bootstrapDexDirectory = layout.buildDirectory.dir("intermediates/thunder-bootstrap/dex")
val generatedBootstrapAssets = layout.buildDirectory.dir("generated/thunder-bootstrap/assets")
val generatedRuntimeAssets = layout.buildDirectory.dir("generated/thunder-runtime/assets")

val runtimeReleasePinFile = rootProject.layout.projectDirectory.file("gradle/thunder-runtime-release.properties")
val runtimeReleasePin = Properties().apply {
    runtimeReleasePinFile.asFile.inputStream().buffered().use(::load)
}
val expectedRuntimePinKeys = setOf("schemaVersion", "version", "url", "size", "sha256")
check(runtimeReleasePin.stringPropertyNames() == expectedRuntimePinKeys) {
    "gradle/thunder-runtime-release.properties must contain exactly ${expectedRuntimePinKeys.sorted()}"
}
fun runtimePin(name: String): String = runtimeReleasePin.getProperty(name)
    ?.takeIf(String::isNotBlank)
    ?: error("Missing runtime release pin: $name")

val pinnedRuntimeSchema = runtimePin("schemaVersion")
val pinnedRuntimeVersion = runtimePin("version")
val pinnedRuntimeUrl = runtimePin("url")
val pinnedRuntimeSize = runtimePin("size").toLongOrNull()
    ?: error("Runtime release size pin is not an integer")
val pinnedRuntimeSha256 = runtimePin("sha256").lowercase()
check(pinnedRuntimeSchema == "1") { "Unsupported runtime release pin schema: $pinnedRuntimeSchema" }
check(Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$").matches(pinnedRuntimeVersion)) {
    "Runtime release pin must use a stable SemVer"
}
check(pinnedRuntimeUrl == "https://github.com/Accentaro/Thunder/releases/download/v$pinnedRuntimeVersion/runtime.js") {
    "Runtime release URL must identify the pinned immutable Thunder release"
}
check(pinnedRuntimeSize in 128..(576 * 1024)) { "Runtime release size pin is outside its allowed bounds" }
check(Regex("^[0-9a-f]{64}$").matches(pinnedRuntimeSha256)) { "Runtime release SHA-256 pin is invalid" }

val extractBootstrapClasses by tasks.registering(Copy::class) {
    dependsOn(":android:bootstrap:bundleReleaseAar")
    from(bootstrapAar.map { zipTree(it) })
    include("classes.jar")
    into(extractedBootstrap)
}

val compileBootstrapDex by tasks.registering(Exec::class) {
    dependsOn(extractBootstrapClasses)
    val sdkDirectory = android.sdkDirectory
    val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "d8.bat" else "d8"
    val d8 = sdkDirectory.resolve("build-tools/${android.buildToolsVersion}/$executableName")
    val androidJar = sdkDirectory.resolve("platforms/android-${android.compileSdk}/android.jar")
    val classesJar = extractedBootstrap.map { it.file("classes.jar") }
    val output = bootstrapDexDirectory.get().asFile
    inputs.file(classesJar)
    outputs.file(bootstrapDexDirectory.map { it.file("classes.dex") })
    doFirst {
        output.deleteRecursively()
        output.mkdirs()
        commandLine(
            d8.absolutePath,
            "--release",
            "--min-api", "28",
            "--lib", androidJar.absolutePath,
            "--output", output.absolutePath,
            classesJar.get().asFile.absolutePath,
        )
    }
}

val generateBootstrapAssets by tasks.registering(Sync::class) {
    dependsOn(compileBootstrapDex)
    from(bootstrapDexDirectory.map { it.file("classes.dex") })
    into(generatedBootstrapAssets.map { it.dir("thunder/backend") })
    rename("classes.dex", "bootstrap.dex")
}

val runtimeFileOverride = providers.gradleProperty("thunder.runtimeFile")
    .orElse(providers.environmentVariable("THUNDER_RUNTIME_FILE"))

val generateRuntimeAssets by tasks.registering(PreparePinnedThunderRuntime::class) {
    group = "build"
    description = "Fetches and verifies the exact immutable Thunder runtime release pinned for this Manager version."
    runtimeVersion.set(pinnedRuntimeVersion)
    runtimeUrl.set(pinnedRuntimeUrl)
    expectedSize.set(pinnedRuntimeSize)
    expectedSha256.set(pinnedRuntimeSha256)
    pinFile.set(runtimeReleasePinFile)
    runtimeFileOverride.orNull?.let { overridePath ->
        runtimeOverride.fileValue(rootProject.file(overridePath))
    }
    outputFile.set(generatedRuntimeAssets.map { it.file("thunder/backend/runtime.js") })
}

android.sourceSets.getByName("main").assets.srcDir(generatedBootstrapAssets)
android.sourceSets.getByName("main").assets.srcDir(generatedRuntimeAssets)
tasks.configureEach {
    if ((name.startsWith("merge") && name.endsWith("Assets")) || name.contains("Lint") || name.startsWith("lint")) {
        dependsOn(generateBootstrapAssets)
        dependsOn(generateRuntimeAssets)
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":android:injection-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.smali.dexlib2)
    testImplementation(libs.junit)
}
