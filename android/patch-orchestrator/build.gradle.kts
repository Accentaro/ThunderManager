plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.thunder.patchorchestrator"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":android:injection-api"))
    implementation(project(":android:package-inspector"))
    implementation(project(":android:patch-domain"))
    implementation(project(":android:signing"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
