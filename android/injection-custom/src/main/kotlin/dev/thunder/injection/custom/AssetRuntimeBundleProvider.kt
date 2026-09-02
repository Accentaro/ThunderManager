package dev.thunder.injection.custom

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException

class AssetRuntimeBundleProvider(
    context: Context,
    private val runtimeVersion: String,
) : RuntimeBundleProvider {
    private val assets = context.applicationContext.assets

    override fun load(): RuntimeBundle = assets.open(ASSET_PATH).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BYTES) throw IOException("Packaged runtime exceeds its size limit")
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray().also {
            if (it.size < MIN_BYTES) throw IOException("Packaged runtime is unexpectedly small")
        }
        if (!STABLE_VERSION.matches(runtimeVersion)) throw IOException("Packaged runtime version is invalid")
        RuntimeBundle(runtimeVersion, bytes)
    }

    companion object {
        private const val ASSET_PATH = "thunder/backend/runtime.js"
        private const val MIN_BYTES = 128
        private const val MAX_BYTES = 576 * 1024
        private val STABLE_VERSION = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
    }
}
