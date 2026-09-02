package dev.thunder.packageinspector

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipFile

internal object PatchMarkerReader {
    private const val ENTRY_NAME = "assets/thunder/patch-manifest.json"
    private const val MAX_BYTES = 16 * 1024
    private const val LEGACY_SCHEMA = 1
    private const val PROVENANCE_SCHEMA = 2
    private const val CURRENT_SCHEMA = 3

    fun read(
        baseApkPath: String,
        expectedOutputPackageName: String,
        expectedSourcePackageName: String? = null,
    ): PatchMarker {
        val payload = try {
            ZipFile(baseApkPath).use { archive ->
                val entry = archive.getEntry(ENTRY_NAME) ?: return PatchMarker.Absent
                if (entry.size > MAX_BYTES) return PatchMarker.Invalid(InvalidPatchMarkerReason.TOO_LARGE)

                archive.getInputStream(entry).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (output.size() + count > MAX_BYTES) {
                            return PatchMarker.Invalid(InvalidPatchMarkerReason.TOO_LARGE)
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
            }
        } catch (_: IOException) {
            return PatchMarker.Invalid(InvalidPatchMarkerReason.UNREADABLE)
        } catch (_: SecurityException) {
            return PatchMarker.Invalid(InvalidPatchMarkerReason.UNREADABLE)
        }

        return try {
            val json = JSONObject(payload)
            val schemaVersion = json.getInt("schemaVersion")
            val platform = json.getString("platform")
            val outputPackageName = json.getString("outputPackageName")
            val bootstrapVersion = json.getString("bootstrapVersion")
            val hasProvenance = schemaVersion == PROVENANCE_SCHEMA || schemaVersion == CURRENT_SCHEMA
            val sourcePackageName = if (hasProvenance) {
                json.getString("sourcePackageName")
            } else {
                null
            }
            val sourceVersionCode = if (hasProvenance) {
                json.getLong("sourceVersionCode")
            } else {
                null
            }
            val sourceSignerSha256 = if (hasProvenance) {
                json.getJSONArray("sourceSignerSha256").let { signers ->
                    List(signers.length()) { index -> signers.getString(index) }
                }
            } else {
                null
            }
            val sourceSetSha256 = if (hasProvenance) {
                json.getString("sourceSetSha256")
            } else {
                null
            }
            val hostDexSha256 = if (schemaVersion == CURRENT_SCHEMA) json.getString("hostDexSha256") else null
            val runtimeVersion = when {
                hasProvenance -> json.getString("runtimeVersion")
                json.has("runtimeVersion") -> json.getString("runtimeVersion")
                else -> null
            }

            when {
                schemaVersion !in setOf(LEGACY_SCHEMA, PROVENANCE_SCHEMA, CURRENT_SCHEMA) -> {
                    PatchMarker.Invalid(InvalidPatchMarkerReason.UNSUPPORTED_SCHEMA)
                }
                platform != "thunder" -> PatchMarker.Invalid(InvalidPatchMarkerReason.WRONG_PLATFORM)
                outputPackageName != expectedOutputPackageName -> {
                    PatchMarker.Invalid(InvalidPatchMarkerReason.PACKAGE_MISMATCH)
                }
                sourcePackageName != null && sourcePackageName.isBlank() -> {
                    PatchMarker.Invalid(InvalidPatchMarkerReason.MALFORMED)
                }
                sourceVersionCode != null && sourceVersionCode <= 0L -> {
                    PatchMarker.Invalid(InvalidPatchMarkerReason.MALFORMED)
                }
                sourceSignerSha256 != null
                    && (sourceSignerSha256.isEmpty()
                        || sourceSignerSha256 != sourceSignerSha256.distinct().sorted()
                        || sourceSignerSha256.any { !SHA_256.matches(it) }) -> {
                    PatchMarker.Invalid(InvalidPatchMarkerReason.MALFORMED)
                }
                sourceSetSha256 != null && !SHA_256.matches(sourceSetSha256) -> {
                    PatchMarker.Invalid(InvalidPatchMarkerReason.MALFORMED)
                }
                schemaVersion == CURRENT_SCHEMA && hostDexSha256?.let(SHA_256::matches) != true -> {
                    PatchMarker.Invalid(InvalidPatchMarkerReason.MALFORMED)
                }
                runtimeVersion != null && !STABLE_VERSION.matches(runtimeVersion) -> {
                    PatchMarker.Invalid(InvalidPatchMarkerReason.MALFORMED)
                }
                expectedSourcePackageName != null
                    && sourcePackageName != null
                    && sourcePackageName != expectedSourcePackageName -> {
                    PatchMarker.Invalid(InvalidPatchMarkerReason.SOURCE_PACKAGE_MISMATCH)
                }
                bootstrapVersion.isBlank() -> PatchMarker.Invalid(InvalidPatchMarkerReason.MALFORMED)
                else -> PatchMarker.Valid(
                    schemaVersion = schemaVersion,
                    bootstrapVersion = bootstrapVersion,
                    sourcePackageName = sourcePackageName,
                    sourceVersionCode = sourceVersionCode,
                    sourceSignerSha256 = sourceSignerSha256,
                    sourceSetSha256 = sourceSetSha256,
                    outputPackageName = outputPackageName,
                    hostDexSha256 = hostDexSha256,
                    runtimeVersion = runtimeVersion,
                )
            }
        } catch (_: JSONException) {
            PatchMarker.Invalid(InvalidPatchMarkerReason.MALFORMED)
        }
    }

    private val SHA_256 = Regex("^[0-9a-f]{64}$")
    private val STABLE_VERSION = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
}
