import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.android)
}

val releaseStorePath = providers.environmentVariable("THUNDER_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("THUNDER_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("THUNDER_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("THUNDER_RELEASE_KEY_PASSWORD").orNull
val managerVersionEntries = rootProject.layout.projectDirectory.file("apps/manager/version.properties")
    .asFile.readLines()
    .map { line ->
        val separator = line.indexOf('=')
        require(separator > 0 && separator == line.lastIndexOf('=')) {
            "ThunderManager version file contains a malformed field"
        }
        line.substring(0, separator) to line.substring(separator + 1)
    }
require(managerVersionEntries.size == 2 && managerVersionEntries.toMap().size == 2) {
    "ThunderManager version file must contain exactly two unique fields"
}
val managerVersion = managerVersionEntries.toMap()
require(managerVersion.keys == setOf("versionName", "versionCode")) {
    "ThunderManager version file contains unknown or missing fields"
}
val managerVersionName = managerVersion.getValue("versionName")
require(Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$").matches(managerVersionName)) {
    "ThunderManager versionName must be a stable semantic version"
}
val managerVersionCodeText = managerVersion.getValue("versionCode")
require(Regex("^[1-9][0-9]*$").matches(managerVersionCodeText)) {
    "ThunderManager versionCode must be a positive base-10 integer"
}
val managerVersionCode = managerVersionCodeText.toIntOrNull()
require(managerVersionCode != null && managerVersionCode <= 2_100_000_000) {
    "ThunderManager versionCode is outside Android's supported range"
}
val bundledRuntimeVersion = Properties().run {
    rootProject.layout.projectDirectory.file("gradle/thunder-runtime-release.properties")
        .asFile.inputStream().buffered().use(::load)
    getProperty("version") ?: error("Pinned Thunder runtime version is missing")
}
val hasReleaseSigning = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
android {
    namespace = "dev.thunder.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.thunder.manager"
        minSdk = 28
        targetSdk = 36
        versionCode = managerVersionCode
        versionName = managerVersionName
        buildConfigField("String", "BUNDLED_THUNDER_RUNTIME_VERSION", "\"$bundledRuntimeVersion\"")
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStorePath
                ?.takeIf { hasReleaseSigning }
                ?.let(::file)
                ?: rootProject.layout.projectDirectory.file(".release-signing-required").asFile
            storePassword = releaseStorePassword.orEmpty()
            keyAlias = releaseKeyAlias.orEmpty()
            keyPassword = releaseKeyPassword.orEmpty()
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
    )
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":android:injection-custom"))
    implementation(project(":android:injection-api"))
    implementation(project(":android:package-inspector"))
    implementation(project(":android:package-installer"))
    implementation(project(":android:patch-domain"))
    implementation(project(":android:patch-orchestrator"))
    implementation(project(":android:signing"))
    implementation(project(":android:update-client"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.core)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
