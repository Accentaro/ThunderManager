package dev.thunder.injection.custom

import dev.thunder.injection.ApkArtifactInput
import dev.thunder.injection.ApkSetInput
import dev.thunder.injection.ArchiveEntryChange
import dev.thunder.injection.BackendCompatibility
import dev.thunder.injection.InjectionPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

class PrivateHostCompatibilityTest {
    @Test
    fun `privately supplied host base passes non-destructive mutation verification`() = runBlocking {
        val basePath = System.getenv("THUNDER_PRIVATE_HOST_BASE")
        val dexPath = System.getenv("THUNDER_BOOTSTRAP_DEX")
        val runtimePath = System.getenv("THUNDER_RUNTIME_BUNDLE")
        assumeTrue(
            "No private host was supplied",
            !basePath.isNullOrBlank() && !dexPath.isNullOrBlank() && !runtimePath.isNullOrBlank(),
        )
        val base = File(requireNotNull(basePath)).canonicalFile
        val dex = File(requireNotNull(dexPath)).canonicalFile
        val runtime = File(requireNotNull(runtimePath)).canonicalFile
        val splits = System.getenv("THUNDER_PRIVATE_HOST_SPLITS")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { File(it).canonicalFile }
        assertTrue(base.isFile && dex.isFile && runtime.isFile && splits.all(File::isFile))
        val splitArtifacts = splits.map { split ->
            ApkArtifactInput(readSplitName(split), split, sha256(split))
        }
        assertEquals(
            "Private split manifests must declare unique split names",
            splitArtifacts.size,
            splitArtifacts.map(ApkArtifactInput::splitName).toSet().size,
        )
        val workspace = Files.createTempDirectory("thunder-private-host-").toFile()
        try {
            val input = ApkSetInput(
                packageName = "com.discord",
                versionCode = 343205,
                artifacts = listOf(ApkArtifactInput(null, base, sha256(base))) + splitArtifacts,
                sourceSignerSha256 = listOf("3c39d23cf9367849a5c699395647fe0e5bfea5a1f1f40d8c717ddc70f8bfa113"),
            )
            val backend = PurposeBuiltInjectionBackend(
                { dex.readBytes() },
                { RuntimeBundle("0.0.1", runtime.readBytes()) },
            )
            val assessment = backend.analyse(input)
            assertEquals(assessment.evidence.joinToString { "${it.id}=${it.detail}" }, BackendCompatibility.COMPATIBLE, assessment.compatibility)
            val output = File(workspace, "output").apply { check(mkdir()) }
            val plan = InjectionPlan("private-host-probe", input, "dev.thunder.app", output, "0.1.0", 1)
            val mutated = backend.apply(backend.prepare(plan))
            val report = backend.verify(input, mutated)
            assertTrue(report.changedManifestFields.contains("manifest.package"))
            assertTrue(report.changedManifestFields.contains("application.label"))
            assertTrue(report.changedManifestFields.contains("application.appComponentFactory"))
            assertTrue(report.changedManifestFields.contains("application.activity[ThunderRecoveryActivity]"))
            assertTrue(report.changedEntries.any { it.entryName == "classes5.dex" })

            var resourceArtifactCount = 0
            for (artifact in mutated.artifacts) {
                ZipFile(artifact.file).use { archive ->
                    val resources = archive.getEntry(RESOURCES_ENTRY) ?: return@use
                    resourceArtifactCount++
                    val bytes = archive.getInputStream(resources).use { it.readBytes() }
                    assertEquals(
                        "Unexpected resource package in ${artifact.splitName ?: "base"}",
                        "dev.thunder.app",
                        BinaryResourceTable.readPackageName(bytes),
                    )
                    assertTrue(
                        "Resource rewrite was not reported for ${artifact.splitName ?: "base"}",
                        report.changedEntries.any { change ->
                            change.artifactSplitName == artifact.splitName &&
                                change.entryName == RESOURCES_ENTRY &&
                                change.change == ArchiveEntryChange.REPLACED
                        },
                    )
                }
            }
            assertTrue("Private Discord fixture contains no resource-bearing APK", resourceArtifactCount > 0)
        } finally {
            check(workspace.deleteRecursively())
        }
    }

    private fun readSplitName(file: File): String = ZipFile(file).use { archive ->
        val manifest = archive.getEntry(MANIFEST_ENTRY)
            ?: error("Private split ${file.name} has no $MANIFEST_ENTRY")
        val bytes = archive.getInputStream(manifest).use { it.readBytes() }
        val names = BinaryAndroidManifest.readStringAttributeValues(bytes, "manifest", "split")
            .filter(String::isNotBlank)
        check(names.size == 1) {
            "Private split ${file.name} must declare exactly one binary-manifest split name"
        }
        names.single()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MANIFEST_ENTRY = "AndroidManifest.xml"
        const val RESOURCES_ENTRY = "resources.arsc"
    }
}
