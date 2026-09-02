package dev.thunder.injection.custom

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException

class AssetBrandIconProvider(context: Context) : BrandIconProvider {
    private val assets = context.applicationContext.assets

    override fun load(): ByteArray = assets.open(ASSET_PATH).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BYTES) throw IOException("Packaged brand icon exceeds its size limit")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private companion object {
        const val ASSET_PATH = "thunder/backend/brand-icon.png"
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
