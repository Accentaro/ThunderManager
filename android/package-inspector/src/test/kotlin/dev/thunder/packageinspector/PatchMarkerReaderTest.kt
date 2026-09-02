package dev.thunder.packageinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PatchMarkerReaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `current authenticated marker exposes stable runtime version`() {
        val marker = PatchMarkerReader.read(
            apk(markerJson(schema = 3, runtimeVersion = "1.2.3")).path,
            OUTPUT_PACKAGE,
        )

        assertTrue(marker is PatchMarker.Valid)
        marker as PatchMarker.Valid
        assertEquals(3, marker.schemaVersion)
        assertEquals("1.2.3", marker.runtimeVersion)
        assertEquals(SOURCE_PACKAGE, marker.sourcePackageName)
    }

    @Test
    fun `legacy schema without runtime version remains readable`() {
        val json = """
            {
              "schemaVersion": 1,
              "platform": "thunder",
              "outputPackageName": "$OUTPUT_PACKAGE",
              "bootstrapVersion": "0.1.0"
            }
        """.trimIndent()
        val marker = PatchMarkerReader.read(apk(json).path, OUTPUT_PACKAGE)

        assertTrue(marker is PatchMarker.Valid)
        assertNull((marker as PatchMarker.Valid).runtimeVersion)
    }

    @Test
    fun `provenance markers require a stable runtime version`() {
        listOf(
            markerJson(schema = 2, includeRuntimeVersion = false),
            markerJson(schema = 3, runtimeVersion = "1.2.3-rc.1"),
            markerJson(schema = 3, runtimeVersion = "01.2.3"),
        ).forEach { json ->
            assertEquals(
                PatchMarker.Invalid(InvalidPatchMarkerReason.MALFORMED),
                PatchMarkerReader.read(apk(json).path, OUTPUT_PACKAGE),
            )
        }
    }

    private fun markerJson(
        schema: Int,
        runtimeVersion: String = "1.2.3",
        includeRuntimeVersion: Boolean = true,
    ): String {
        val runtime = if (includeRuntimeVersion) ",\n  \"runtimeVersion\": \"$runtimeVersion\"" else ""
        val hostDex = if (schema == 3) ",\n  \"hostDexSha256\": \"${"b".repeat(64)}\"" else ""
        return """
            {
              "schemaVersion": $schema,
              "platform": "thunder",
              "outputPackageName": "$OUTPUT_PACKAGE",
              "bootstrapVersion": "0.1.0",
              "sourcePackageName": "$SOURCE_PACKAGE",
              "sourceVersionCode": 343205,
              "sourceSignerSha256": ["${"a".repeat(64)}"],
              "sourceSetSha256": "${"c".repeat(64)}"$hostDex$runtime
            }
        """.trimIndent()
    }

    private fun apk(marker: String): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("assets/thunder/patch-manifest.json"))
            output.write(marker.toByteArray())
            output.closeEntry()
        }
    }

    private companion object {
        const val OUTPUT_PACKAGE = "dev.thunder.app"
        const val SOURCE_PACKAGE = "com.discord"
    }
}
