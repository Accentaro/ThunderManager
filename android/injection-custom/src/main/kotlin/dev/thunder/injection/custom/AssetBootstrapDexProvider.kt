package dev.thunder.injection.custom

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException

class AssetBootstrapDexProvider(context: Context) : BootstrapDexProvider {
    private val assets = context.applicationContext.assets

    override fun load(): ByteArray = assets.open(ASSET_PATH).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BYTES) throw IOException("Packaged bootstrap DEX exceeds its size limit")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    companion object {
        private const val ASSET_PATH = "thunder/backend/bootstrap.dex"
        private const val MAX_BYTES = 12 * 1024 * 1024
    }
}
