package dev.thunder.injection.custom

import dev.thunder.injection.ApkArtifactInput
import dev.thunder.injection.ApkSetInput
import dev.thunder.injection.ArchiveEntryChange
import dev.thunder.injection.BackendCompatibility
import dev.thunder.injection.InjectionPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PurposeBuiltInjectionBackendTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `binary manifest replacement preserves original factory`() {
        val source = BinaryManifestFixture.create()
        val result = BinaryAndroidManifest.replaceFactoryAndDeclareRecovery(
            source,
            "dev.thunder.bootstrap.ThunderAppComponentFactory",
            "dev.thunder.bootstrap.ThunderRecoveryActivity",
        )
        assertEquals(BinaryManifestFixture.ORIGINAL_FACTORY, result.originalFactory)
        assertEquals("dev.thunder.bootstrap.ThunderAppComponentFactory", BinaryAndroidManifest.readFactory(result.bytes))
        assertTrue(BinaryAndroidManifest.hasExportedActivity(result.bytes, "dev.thunder.bootstrap.ThunderRecoveryActivity"))
    }

    @Test
    fun `clone identity rewrite is targeted and accepts a supplied Thunder mapping`() {
        val outputPackage = "dev.thunder.fixture"
        val result = BinaryAndroidManifest.rewriteBaseForClone(
            BinaryManifestFixture.create(),
            BinaryManifestFixture.SOURCE_PACKAGE,
            outputPackage,
            "Thunder",
        )

        assertEquals(outputPackage, BinaryAndroidManifest.readPackageName(result.bytes))
        assertEquals(
            listOf("$outputPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "permission", "name"),
        )
        assertEquals(
            listOf("$outputPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "uses-permission", "name"),
        )
        assertEquals(
            listOf("$outputPackage.file-provider"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "provider", "authorities"),
        )
        assertEquals(
            listOf("$outputPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "application", "permission"),
        )
        assertEquals(
            listOf("$outputPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "provider", "readPermission"),
        )
        assertEquals(listOf("Thunder"), BinaryAndroidManifest.readStringAttributeValues(result.bytes, "application", "label"))
        assertEquals(listOf("Thunder"), BinaryAndroidManifest.readStringAttributeValues(result.bytes, "activity", "label"))
        assertEquals(listOf("Thunder"), BinaryAndroidManifest.readStringAttributeValues(result.bytes, "activity-alias", "label"))

        // Java component and intent routing names remain tied to the host DEX.
        assertEquals(
            listOf("${BinaryManifestFixture.SOURCE_PACKAGE}.MainApplication"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "application", "name"),
        )
        assertEquals(
            listOf(BinaryManifestFixture.COMPONENT_NAME),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "activity", "name"),
        )
        assertEquals(
            listOf("$outputPackage.share"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "activity", "taskAffinity"),
        )
        assertEquals(
            listOf(BinaryManifestFixture.COMPONENT_NAME),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "activity-alias", "targetActivity"),
        )
        assertEquals(
            listOf(BinaryManifestFixture.ACTION_NAME, BinaryManifestFixture.SYSTEM_ACTION_NAME),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "action", "name"),
        )
        assertEquals(
            listOf(BinaryManifestFixture.CATEGORY_NAME, BinaryManifestFixture.SYSTEM_CATEGORY_NAME),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "category", "name"),
        )
        assertEquals(
            listOf("${BinaryManifestFixture.SOURCE_PACKAGE}.features.FLAG"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "meta-data", "name"),
        )
        assertEquals(
            listOf(BinaryManifestFixture.SOURCE_PACKAGE),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "meta-data", "value"),
        )
    }

    @Test
    fun `backend appends minimal surface and preserves all other entry content`() = runBlocking {
        val base = temporary.newFile("base-input.apk")
        val resourceSplit = temporary.newFile("resource-split-input.apk")
        val abiSplit = temporary.newFile("abi-split-input.apk")
        val outputPackage = "dev.thunder.fixture"
        val nativeBytes = ByteArray(1024) { (it % 251).toByte() }
        val baseResources = BinaryResourceTableFixture.create()
        val splitResources = BinaryResourceTableFixture.create()
        val splitPayload = "split-host".toByteArray()
        val brandIcon = ByteArray(64).also { bytes ->
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(bytes)
            "thunder-brand-fixture".toByteArray().copyInto(bytes, 8)
        }
        createApk(base, mapOf(
            "AndroidManifest.xml" to BinaryManifestFixture.create(),
            "resources.arsc" to baseResources,
            "classes.dex" to fakeDex(1),
            "assets/host.txt" to "host".toByteArray(),
            "res/mipmap-xxhdpi-v4/ic_logo_square_main.png" to "discord-icon".toByteArray(),
            "META-INF/HOST.SF" to "obsolete".toByteArray(),
        ))
        createApk(resourceSplit, mapOf(
            "AndroidManifest.xml" to BinaryManifestFixture.createSplit(),
            "resources.arsc" to splitResources,
            "assets/split.txt" to splitPayload,
            "META-INF/SPLIT.RSA" to "obsolete-split".toByteArray(),
        ))
        createApk(abiSplit, mapOf(
            "AndroidManifest.xml" to BinaryManifestFixture.createSplit(),
            "lib/arm64-v8a/libfixture.so" to nativeBytes,
            "assets/abi.txt" to "abi-host".toByteArray(),
        ))
        val input = ApkSetInput(
            packageName = "com.discord",
            versionCode = 343205,
            artifacts = listOf(
                ApkArtifactInput(null, base, sha256(base)),
                ApkArtifactInput("config.en", resourceSplit, sha256(resourceSplit)),
                ApkArtifactInput("config.arm64_v8a", abiSplit, sha256(abiSplit)),
            ),
            sourceSignerSha256 = listOf("a".repeat(64)),
            versionName = "343.6 - Alpha",
        )
        val runtime = "(()=>{globalThis.__THUNDER_TEST__='ready';})();".repeat(4).toByteArray()
        val backend = PurposeBuiltInjectionBackend(
            { fakeDex(7) },
            { runtimeBundle(runtime, "1.2.3") },
            { brandIcon },
            FakeReactNativeDexWeaver,
        )
        assertEquals(BackendCompatibility.COMPATIBLE, backend.analyse(input).compatibility)
        val outputDirectory = temporary.newFolder("output")
        val plan = InjectionPlan("transaction-1", input, outputPackage, outputDirectory, "0.1.0", 1)
        val mutated = backend.apply(backend.prepare(plan))
        val report = backend.verify(input, mutated)

        assertEquals(outputPackage, mutated.packageName)
        assertTrue(report.changedManifestFields.contains("manifest.package-owned-task-affinities"))
        assertTrue(report.changedManifestFields.contains("application.provider.authorities"))
        assertTrue(report.changedManifestFields.contains("manifest.package-owned-permissions"))
        assertTrue(report.changedManifestFields.none { "intent-action" in it || "intent-categor" in it })
        for (splitName in listOf("config.en", "config.arm64_v8a")) {
            assertTrue(report.changedManifestFields.contains("split[$splitName].manifest.package-owned-task-affinities"))
            assertTrue(report.changedManifestFields.contains("split[$splitName].application.provider.authorities"))
            assertTrue(report.changedManifestFields.contains("split[$splitName].manifest.package-owned-permissions"))
        }
        val changedEntries = report.changedEntries.map {
            Triple(it.artifactSplitName, it.entryName, it.change)
        }
        listOf(
            Triple(null, "AndroidManifest.xml", ArchiveEntryChange.REPLACED),
            Triple(null, "resources.arsc", ArchiveEntryChange.REPLACED),
            Triple(null, "classes.dex", ArchiveEntryChange.REPLACED),
            Triple(null, "classes2.dex", ArchiveEntryChange.ADDED),
            Triple(null, "assets/thunder/bootstrap.properties", ArchiveEntryChange.ADDED),
            Triple(null, "assets/thunder/patch-manifest.json", ArchiveEntryChange.ADDED),
            Triple(null, "assets/thunder/runtime.js", ArchiveEntryChange.ADDED),
            Triple(null, "res/mipmap-xxhdpi-v4/ic_logo_square_main.png", ArchiveEntryChange.REPLACED),
            Triple("config.en", "AndroidManifest.xml", ArchiveEntryChange.REPLACED),
            Triple("config.en", "resources.arsc", ArchiveEntryChange.REPLACED),
            Triple("config.arm64_v8a", "AndroidManifest.xml", ArchiveEntryChange.REPLACED),
            Triple(null, "META-INF/HOST.SF", ArchiveEntryChange.REMOVED),
            Triple("config.en", "META-INF/SPLIT.RSA", ArchiveEntryChange.REMOVED),
        ).forEach { expected -> assertTrue("Missing changed entry $expected", expected in changedEntries) }
        val outputBase = mutated.artifacts.single { it.splitName == null }.file
        ZipFile(outputBase).use { archive ->
            assertTrue(archive.getEntry("META-INF/HOST.SF") == null)
            val manifest = archive.getInputStream(archive.getEntry("AndroidManifest.xml")).readBytes()
            assertArrayEquals(
                brandIcon,
                archive.getInputStream(archive.getEntry("res/mipmap-xxhdpi-v4/ic_logo_square_main.png")).readBytes(),
            )
            assertEquals(outputPackage, BinaryAndroidManifest.readPackageName(manifest))
            assertEquals(
                listOf("$outputPackage.file-provider"),
                BinaryAndroidManifest.readStringAttributeValues(manifest, "provider", "authorities"),
            )
            assertEquals(listOf("Thunder"), BinaryAndroidManifest.readStringAttributeValues(manifest, "application", "label"))
            assertEquals(listOf("Thunder"), BinaryAndroidManifest.readStringAttributeValues(manifest, "activity", "label"))
            assertEquals(listOf("Thunder"), BinaryAndroidManifest.readStringAttributeValues(manifest, "activity-alias", "label"))
            assertEquals(
                listOf("$outputPackage.share"),
                BinaryAndroidManifest.readStringAttributeValues(manifest, "activity", "taskAffinity"),
            )
            assertEquals(
                listOf(BinaryManifestFixture.ACTION_NAME, BinaryManifestFixture.SYSTEM_ACTION_NAME),
                BinaryAndroidManifest.readStringAttributeValues(manifest, "action", "name"),
            )
            assertEquals(
                listOf(BinaryManifestFixture.CATEGORY_NAME, BinaryManifestFixture.SYSTEM_CATEGORY_NAME),
                BinaryAndroidManifest.readStringAttributeValues(manifest, "category", "name"),
            )
            val resources = archive.getInputStream(archive.getEntry("resources.arsc")).readBytes()
            assertEquals(outputPackage, BinaryResourceTable.readPackageName(resources))
            assertArrayEquals(
                BinaryResourceTableFixture.create(outputPackage),
                resources,
            )
            assertArrayEquals(
                embedded("343.6 - Alpha", 343205, runtime),
                archive.getInputStream(archive.getEntry("assets/thunder/runtime.js")).readBytes(),
            )
            val marker = String(
                archive.getInputStream(archive.getEntry("assets/thunder/patch-manifest.json")).readBytes(),
                Charsets.UTF_8,
            )
            val hostDexSha256 = sha256(archive.getInputStream(archive.getEntry("classes.dex")).readBytes())
            assertTrue(marker.contains("\"backendId\":\"thunder.purpose-built\""))
            assertTrue(marker.contains("\"runtimeVersion\":\"1.2.3\""))
            val configuration = String(
                archive.getInputStream(archive.getEntry("assets/thunder/bootstrap.properties")).readBytes(),
                Charsets.ISO_8859_1,
            )
            assertTrue(configuration.lineSequence().any { it == "runtimeVersion=1.2.3" })
            assertTrue(marker.contains("\"backendVersion\":\"0.6.0\""))
            assertTrue(marker.contains("\"schemaVersion\":3"))
            assertTrue(marker.contains("\"hostDexSha256\":\"$hostDexSha256\""))
            assertTrue(marker.contains("\"sourcePackageName\":\"com.discord\""))
            assertTrue(marker.contains("\"outputPackageName\":\"$outputPackage\""))
            assertTrue(marker.contains("\"sourceVersionCode\":343205"))
            assertTrue(marker.contains("\"sourceSignerSha256\":[\"${"a".repeat(64)}\"]"))
            assertTrue(Regex("\\\"sourceSetSha256\\\":\\\"[0-9a-f]{64}\\\"").containsMatchIn(marker))
        }
        val outputSplit = mutated.artifacts.single { it.splitName == "config.en" }.file
        ZipFile(outputSplit).use { archive ->
            val manifest = archive.getInputStream(archive.getEntry("AndroidManifest.xml")).readBytes()
            assertEquals(outputPackage, BinaryAndroidManifest.readPackageName(manifest))
            assertEquals(
                listOf(BinaryManifestFixture.SPLIT_COMPONENT_NAME),
                BinaryAndroidManifest.readStringAttributeValues(manifest, "activity", "name"),
            )
            assertEquals(
                listOf("$outputPackage.custom_tab"),
                BinaryAndroidManifest.readStringAttributeValues(manifest, "activity", "taskAffinity"),
            )
            assertEquals(
                listOf("$outputPackage.split-provider;com.example.shared-provider"),
                BinaryAndroidManifest.readStringAttributeValues(manifest, "provider", "authorities"),
            )
            assertEquals(
                listOf("$outputPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
                BinaryAndroidManifest.readStringAttributeValues(manifest, "uses-permission", "name"),
            )
            assertEquals(
                listOf("$outputPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
                BinaryAndroidManifest.readStringAttributeValues(manifest, "provider", "writePermission"),
            )
            assertEquals(
                listOf(BinaryManifestFixture.SPLIT_ACTION_NAME, BinaryManifestFixture.SYSTEM_ACTION_NAME),
                BinaryAndroidManifest.readStringAttributeValues(manifest, "action", "name"),
            )
            assertEquals(
                listOf(BinaryManifestFixture.SPLIT_CATEGORY_NAME, BinaryManifestFixture.SYSTEM_CATEGORY_NAME),
                BinaryAndroidManifest.readStringAttributeValues(manifest, "category", "name"),
            )
            val resources = archive.getInputStream(archive.getEntry("resources.arsc")).readBytes()
            assertEquals(outputPackage, BinaryResourceTable.readPackageName(resources))
            assertArrayEquals(BinaryResourceTableFixture.create(outputPackage), resources)
            assertArrayEquals(splitPayload, archive.getInputStream(archive.getEntry("assets/split.txt")).readBytes())
            assertTrue(archive.getEntry("META-INF/SPLIT.RSA") == null)
        }
        ZipFile(mutated.artifacts.single { it.splitName == "config.arm64_v8a" }.file).use { archive ->
            assertTrue(archive.getEntry("resources.arsc") == null)
            assertArrayEquals(nativeBytes, archive.getInputStream(archive.getEntry("lib/arm64-v8a/libfixture.so")).readBytes())
            assertArrayEquals("abi-host".toByteArray(), archive.getInputStream(archive.getEntry("assets/abi.txt")).readBytes())
        }
    }

    @Test
    fun `backend migrates schema 2 identities once while refreshing Thunder runtime`() = runBlocking {
        val stock = temporary.newFile("refresh-stock.apk")
        val stockSplit = temporary.newFile("refresh-stock-split.apk")
        val stockBaseResources = BinaryResourceTableFixture.create()
        val stockSplitResources = BinaryResourceTableFixture.create()
        createApk(stock, mapOf(
            "AndroidManifest.xml" to BinaryManifestFixture.create(),
            "resources.arsc" to stockBaseResources,
            "classes.dex" to fakeDex(1),
            "assets/host.txt" to "host".toByteArray(),
        ))
        createApk(stockSplit, mapOf(
            "AndroidManifest.xml" to BinaryManifestFixture.createSplit(),
            "resources.arsc" to stockSplitResources,
        ))
        val stockInput = ApkSetInput(
            packageName = "com.discord",
            versionCode = 343205,
            artifacts = listOf(
                ApkArtifactInput(null, stock, sha256(stock)),
                ApkArtifactInput("config.en", stockSplit, sha256(stockSplit)),
            ),
            sourceSignerSha256 = listOf("a".repeat(64)),
            versionName = "343.6 - Alpha",
        )
        val firstRuntime = "(()=>{globalThis.__THUNDER_TEST__='first';})();".repeat(4).toByteArray()
        val firstBackend = PurposeBuiltInjectionBackend({ fakeDex(7) }, { runtimeBundle(firstRuntime) }, FakeReactNativeDexWeaver)
        val firstPlan = InjectionPlan(
            "transaction-first",
            stockInput,
            BinaryManifestFixture.OUTPUT_PACKAGE,
            temporary.newFolder("refresh-first"),
            "0.1.0",
            1,
        )
        val firstOutput = firstBackend.apply(firstBackend.prepare(firstPlan))
        firstBackend.verify(stockInput, firstOutput)
        val firstBase = firstOutput.artifacts.single { it.splitName == null }.file
        val firstSplit = firstOutput.artifacts.single { it.splitName == "config.en" }.file
        val schema2Base = temporary.newFile("refresh-schema-2-marker.apk")
        rewriteApkEntry(firstBase, schema2Base, "assets/thunder/patch-manifest.json") { bytes ->
            String(bytes, Charsets.UTF_8)
                .replace(Regex(",\"hostDexSha256\":\"[0-9a-f]{64}\""), "")
                .replace("\"schemaVersion\":3", "\"schemaVersion\":2")
                .toByteArray(Charsets.UTF_8)
        }
        val legacyBaseManifest = BinaryAndroidManifest.replaceFactoryAndDeclareRecovery(
            BinaryManifestFixture.create(
                packageName = BinaryManifestFixture.OUTPUT_PACKAGE,
                coexistencePackageName = BinaryManifestFixture.OUTPUT_PACKAGE,
                routingPackageName = BinaryManifestFixture.OUTPUT_PACKAGE,
            ),
            "dev.thunder.bootstrap.ThunderAppComponentFactory",
            "dev.thunder.bootstrap.ThunderRecoveryActivity",
        ).bytes
        val legacyBase = temporary.newFile("refresh-schema-2-base.apk")
        rewriteApkEntry(schema2Base, legacyBase, "AndroidManifest.xml") { legacyBaseManifest }
        val legacySplitManifest = BinaryManifestFixture.createSplit(
            packageName = BinaryManifestFixture.OUTPUT_PACKAGE,
            taskAffinityPackageName = BinaryManifestFixture.OUTPUT_PACKAGE,
            providerPermissionPackageName = BinaryManifestFixture.SOURCE_PACKAGE,
            routingPackageName = BinaryManifestFixture.OUTPUT_PACKAGE,
        )
        val legacySplit = temporary.newFile("refresh-schema-2-split.apk")
        rewriteApkEntry(firstSplit, legacySplit, "AndroidManifest.xml") { legacySplitManifest }
        val expectedBaseManifest = BinaryAndroidManifest.migrateSchema2CloneIdentities(
            legacyBaseManifest,
            BinaryManifestFixture.SOURCE_PACKAGE,
            BinaryManifestFixture.OUTPUT_PACKAGE,
        ).bytes
        val expectedSplitManifest = BinaryAndroidManifest.migrateSchema2CloneIdentities(
            legacySplitManifest,
            BinaryManifestFixture.SOURCE_PACKAGE,
            BinaryManifestFixture.OUTPUT_PACKAGE,
        ).bytes
        val preserved = ZipFile(firstBase).use { archive ->
            listOf("resources.arsc", "classes.dex", "assets/host.txt").associateWith { name ->
                archive.getInputStream(archive.getEntry(name)).readBytes()
            }
        }
        val preservedSplit = ZipFile(firstSplit).use { archive ->
            listOf("resources.arsc").associateWith { name ->
                archive.getInputStream(archive.getEntry(name)).readBytes()
            }
        }
        assertEquals(
            BinaryManifestFixture.OUTPUT_PACKAGE,
            BinaryResourceTable.readPackageName(preserved.getValue("resources.arsc")),
        )
        assertArrayEquals(
            BinaryResourceTableFixture.create(BinaryManifestFixture.OUTPUT_PACKAGE),
            preserved.getValue("resources.arsc"),
        )
        assertEquals(
            BinaryManifestFixture.OUTPUT_PACKAGE,
            BinaryResourceTable.readPackageName(preservedSplit.getValue("resources.arsc")),
        )
        assertArrayEquals(
            BinaryResourceTableFixture.create(BinaryManifestFixture.OUTPUT_PACKAGE),
            preservedSplit.getValue("resources.arsc"),
        )

        val refreshInput = ApkSetInput(
            packageName = BinaryManifestFixture.OUTPUT_PACKAGE,
            versionCode = 343205,
            artifacts = listOf(
                ApkArtifactInput(null, legacyBase, sha256(legacyBase)),
                ApkArtifactInput("config.en", legacySplit, sha256(legacySplit)),
            ),
            sourceSignerSha256 = listOf("b".repeat(64)),
            versionName = "343.6 - Alpha",
        )
        val secondRuntime = "(()=>{globalThis.__THUNDER_TEST__='second';})();".repeat(4).toByteArray()
        val refreshWeaver = CountingReactNativeDexWeaver()
        val refreshBackend = PurposeBuiltInjectionBackend({ fakeDex(9) }, { runtimeBundle(secondRuntime) }, refreshWeaver)
        assertEquals(BackendCompatibility.COMPATIBLE, refreshBackend.analyse(refreshInput).compatibility)
        val refreshPlan = InjectionPlan(
            "transaction-refresh",
            refreshInput,
            BinaryManifestFixture.OUTPUT_PACKAGE,
            temporary.newFolder("refresh-second"),
            "0.2.0",
            1,
        )
        val refreshOutput = refreshBackend.apply(refreshBackend.prepare(refreshPlan))
        val report = refreshBackend.verify(refreshInput, refreshOutput)

        assertEquals("The schema-2 host DEX should be parsed only once per immutable snapshot", 1, refreshWeaver.inspections)
        assertEquals(listOf(
            Triple(null, "classes2.dex", ArchiveEntryChange.REPLACED),
            Triple(null, "assets/thunder/bootstrap.properties", ArchiveEntryChange.REPLACED),
            Triple(null, "assets/thunder/patch-manifest.json", ArchiveEntryChange.REPLACED),
            Triple(null, "assets/thunder/runtime.js", ArchiveEntryChange.REPLACED),
            Triple(null, "AndroidManifest.xml", ArchiveEntryChange.REPLACED),
            Triple("config.en", "AndroidManifest.xml", ArchiveEntryChange.REPLACED),
        ), report.changedEntries.map { Triple(it.artifactSplitName, it.entryName, it.change) })
        assertEquals(
            listOf(
                "manifest.restored-host-intent-actions",
                "manifest.restored-host-intent-categories",
                "split[config.en].application.provider.authorities",
                "split[config.en].manifest.package-owned-permissions",
                "split[config.en].manifest.restored-host-intent-actions",
                "split[config.en].manifest.restored-host-intent-categories",
            ),
            report.changedManifestFields,
        )
        ZipFile(refreshOutput.artifacts.single { it.splitName == null }.file).use { archive ->
            for ((name, bytes) in preserved) {
                assertArrayEquals(bytes, archive.getInputStream(archive.getEntry(name)).readBytes())
            }
            assertArrayEquals(
                expectedBaseManifest,
                archive.getInputStream(archive.getEntry("AndroidManifest.xml")).readBytes(),
            )
            assertArrayEquals(fakeDex(9), archive.getInputStream(archive.getEntry("classes2.dex")).readBytes())
            assertArrayEquals(
                embedded("343.6 - Alpha", 343205, secondRuntime),
                archive.getInputStream(archive.getEntry("assets/thunder/runtime.js")).readBytes(),
            )
            val hostDex = archive.getInputStream(archive.getEntry("classes.dex")).readBytes()
            val marker = String(
                archive.getInputStream(archive.getEntry("assets/thunder/patch-manifest.json")).readBytes(),
                Charsets.UTF_8,
            )
            assertTrue(marker.contains("\"schemaVersion\":3"))
            assertTrue(marker.contains("\"hostDexSha256\":\"${sha256(hostDex)}\""))
        }
        ZipFile(refreshOutput.artifacts.single { it.splitName == "config.en" }.file).use { archive ->
            for ((name, bytes) in preservedSplit) {
                assertArrayEquals(bytes, archive.getInputStream(archive.getEntry(name)).readBytes())
            }
            assertArrayEquals(
                expectedSplitManifest,
                archive.getInputStream(archive.getEntry("AndroidManifest.xml")).readBytes(),
            )
        }

        val refreshedBase = refreshOutput.artifacts.single { it.splitName == null }.file
        val tamperedBase = temporary.newFile("refresh-tampered-host.apk")
        rewriteApkEntry(refreshedBase, tamperedBase, "classes.dex") { bytes ->
            bytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        }
        val tamperedInput = refreshInput.copy(
            artifacts = listOf(
                ApkArtifactInput(null, tamperedBase, sha256(tamperedBase)),
                ApkArtifactInput("config.en", firstSplit, sha256(firstSplit)),
            ),
        )
        val tamperedWeaver = CountingReactNativeDexWeaver()
        val tamperedBackend = PurposeBuiltInjectionBackend({ fakeDex(9) }, { runtimeBundle(secondRuntime) }, tamperedWeaver)
        assertEquals(BackendCompatibility.INCOMPATIBLE, tamperedBackend.analyse(tamperedInput).compatibility)
        assertEquals("Schema 3 must reject by digest without parsing the host DEX", 0, tamperedWeaver.inspections)
    }

    @Test
    fun `embedded host preamble stays ASCII and escaped`() = runBlocking {
        val base = temporary.newFile("escape-base.apk")
        createApk(base, mapOf(
            "AndroidManifest.xml" to BinaryManifestFixture.create(),
            "classes.dex" to fakeDex(1),
        ))
        val input = ApkSetInput(
            packageName = "com.discord",
            versionCode = 343205,
            artifacts = listOf(ApkArtifactInput(null, base, sha256(base))),
            sourceSignerSha256 = listOf("a".repeat(64)),
            versionName = "343.6 \"Alpha\"\\ €",
        )
        val runtime = "(()=>{globalThis.__THUNDER_TEST__='ready';})();".repeat(4).toByteArray()
        val backend = PurposeBuiltInjectionBackend({ fakeDex(7) }, { runtimeBundle(runtime) }, FakeReactNativeDexWeaver)
        val plan = InjectionPlan(
            "transaction-escape",
            input,
            BinaryManifestFixture.OUTPUT_PACKAGE,
            temporary.newFolder("escape-output"),
            "0.1.0",
            1,
        )
        val mutated = backend.apply(backend.prepare(plan))
        backend.verify(input, mutated)

        val embedded = ZipFile(mutated.artifacts.single().file).use { archive ->
            archive.getInputStream(archive.getEntry("assets/thunder/runtime.js")).readBytes()
        }
        val preamble = String(embedded.copyOf(embedded.size - runtime.size), Charsets.US_ASCII)
        assertEquals(
            "globalThis.__THUNDER_HOST__={\"packageName\":\"com.discord\"," +
                "\"nativeCapabilities\":{\"chatBubbles\":false},\"versionCode\":\"343205\"," +
                "\"versionName\":\"343.6 \\\"Alpha\\\"\\\\ \\u20ac\"};\n",
            preamble,
        )
        assertTrue(embedded.none { it == 0.toByte() || it.toInt() and 0x80 != 0 })
    }

    @Test
    fun `schema 3 refresh authenticates host dex without parsing it`() = runBlocking {
        val stock = temporary.newFile("schema-3-stock.apk")
        createApk(stock, mapOf(
            "AndroidManifest.xml" to BinaryManifestFixture.create(),
            "classes.dex" to fakeDex(1),
            "assets/host.txt" to "preserved".toByteArray(),
        ))
        val stockInput = ApkSetInput(
            packageName = "com.discord",
            versionCode = 343205,
            artifacts = listOf(ApkArtifactInput(null, stock, sha256(stock))),
            sourceSignerSha256 = listOf("a".repeat(64)),
            versionName = "343.6 - Alpha",
        )
        val firstRuntime = "(()=>{globalThis.__THUNDER_TEST__='schema3-first';})();".repeat(4).toByteArray()
        val firstBackend = PurposeBuiltInjectionBackend({ fakeDex(7) }, { runtimeBundle(firstRuntime) }, FakeReactNativeDexWeaver)
        val firstOutput = firstBackend.apply(firstBackend.prepare(InjectionPlan(
            "schema-3-first",
            stockInput,
            BinaryManifestFixture.OUTPUT_PACKAGE,
            temporary.newFolder("schema-3-first-output"),
            "0.6.0",
            1,
        )))
        firstBackend.verify(stockInput, firstOutput)
        val cloneBase = firstOutput.artifacts.single().file
        val (originalHostDex, originalManifest) = ZipFile(cloneBase).use { archive ->
            archive.getInputStream(archive.getEntry("classes.dex")).readBytes() to
                archive.getInputStream(archive.getEntry("AndroidManifest.xml")).readBytes()
        }
        val cloneInput = ApkSetInput(
            packageName = BinaryManifestFixture.OUTPUT_PACKAGE,
            versionCode = 343205,
            artifacts = listOf(ApkArtifactInput(null, cloneBase, sha256(cloneBase))),
            sourceSignerSha256 = listOf("b".repeat(64)),
            versionName = "343.6 - Alpha",
        )

        val steadyWeaver = CountingReactNativeDexWeaver()
        val secondRuntime = "(()=>{globalThis.__THUNDER_TEST__='schema3-second';})();".repeat(4).toByteArray()
        val steadyBackend = PurposeBuiltInjectionBackend({ fakeDex(9) }, { runtimeBundle(secondRuntime) }, steadyWeaver)
        assertEquals(BackendCompatibility.COMPATIBLE, steadyBackend.analyse(cloneInput).compatibility)
        val steadyOutput = steadyBackend.apply(steadyBackend.prepare(InjectionPlan(
            "schema-3-second",
            cloneInput,
            BinaryManifestFixture.OUTPUT_PACKAGE,
            temporary.newFolder("schema-3-second-output"),
            "0.6.0",
            1,
        )))
        steadyBackend.verify(cloneInput, steadyOutput)

        assertEquals("Schema-3 Refresh must not parse the host DEX", 0, steadyWeaver.inspections)
        ZipFile(steadyOutput.artifacts.single().file).use { archive ->
            val outputHostDex = archive.getInputStream(archive.getEntry("classes.dex")).readBytes()
            assertArrayEquals(originalHostDex, outputHostDex)
            assertArrayEquals(
                "Schema-3 Refresh must preserve an already-correct manifest byte for byte",
                originalManifest,
                archive.getInputStream(archive.getEntry("AndroidManifest.xml")).readBytes(),
            )
            val marker = String(
                archive.getInputStream(archive.getEntry("assets/thunder/patch-manifest.json")).readBytes(),
                Charsets.UTF_8,
            )
            assertTrue(marker.contains("\"schemaVersion\":3"))
            assertTrue(marker.contains("\"hostDexSha256\":\"${sha256(outputHostDex)}\""))
        }

        val mismatchedVersionInput = cloneInput.copy(versionCode = cloneInput.versionCode + 1)
        assertEquals(
            BackendCompatibility.INCOMPATIBLE,
            steadyBackend.analyse(mismatchedVersionInput).compatibility,
        )
        val mismatchedPlan = InjectionPlan(
            "schema-3-version-mismatch",
            mismatchedVersionInput,
            BinaryManifestFixture.OUTPUT_PACKAGE,
            temporary.newFolder("schema-3-version-mismatch-output"),
            "0.6.0",
            1,
        )
        assertTrue(
            "Prepare must reject a marker whose source version differs from ApkSetInput",
            runCatching { steadyBackend.prepare(mismatchedPlan) }.exceptionOrNull() is IOException,
        )
        assertTrue(
            "Verify must reject a marker whose source version differs from ApkSetInput",
            runCatching { steadyBackend.verify(mismatchedVersionInput, steadyOutput) }
                .exceptionOrNull() is IOException,
        )
    }

    @Test
    fun `optional ChatBubbles seam composes in the host dex and refresh preserves it by hash`() = runBlocking {
        val stock = temporary.newFile("chat-bubbles-stock.apk")
        val hostDex = fakeDex(1).also { it[it.lastIndex - 1] = 1 }
        createApk(stock, mapOf(
            "AndroidManifest.xml" to BinaryManifestFixture.create(),
            "classes.dex" to hostDex,
        ))
        val stockInput = ApkSetInput(
            packageName = "com.discord",
            versionCode = 343205,
            artifacts = listOf(ApkArtifactInput(null, stock, sha256(stock))),
            sourceSignerSha256 = listOf("a".repeat(64)),
            versionName = "343.6 - Alpha",
        )
        val firstRuntime = "(()=>{globalThis.__THUNDER_TEST__='chat-first';})();".repeat(4).toByteArray()
        val firstBackend = PurposeBuiltInjectionBackend(
            { fakeDex(7) },
            { runtimeBundle(firstRuntime) },
            FakeReactNativeDexWeaver,
            FakeChatBubblesDexWeaver,
        )
        val assessment = firstBackend.analyse(stockInput)
        assertEquals(BackendCompatibility.COMPATIBLE, assessment.compatibility)
        assertTrue(assessment.evidence.single { it.id == "chat-bubbles-native-seam" }.passed)
        val firstOutput = firstBackend.apply(firstBackend.prepare(InjectionPlan(
            "chat-bubbles-first",
            stockInput,
            BinaryManifestFixture.OUTPUT_PACKAGE,
            temporary.newFolder("chat-bubbles-first-output"),
            "0.6.0",
            1,
        )))
        val firstReport = firstBackend.verify(stockInput, firstOutput)
        assertEquals(
            "A DEX shared by both seams must be reported once",
            1,
            firstReport.changedEntries.count { it.artifactSplitName == null && it.entryName == "classes.dex" },
        )
        val cloneBase = firstOutput.artifacts.single().file
        val patchedHostDex = ZipFile(cloneBase).use { archive ->
            val rewritten = archive.getInputStream(archive.getEntry("classes.dex")).readBytes()
            assertEquals(2, rewritten.last().toInt())
            assertEquals(2, rewritten[rewritten.lastIndex - 1].toInt())
            val marker = String(
                archive.getInputStream(archive.getEntry("assets/thunder/patch-manifest.json")).readBytes(),
                Charsets.UTF_8,
            )
            assertTrue(marker.contains("\"chatBubblesDexEntry\":\"classes.dex\""))
            assertTrue(marker.contains("\"chatBubblesDexSha256\":\"${sha256(rewritten)}\""))
            val embedded = String(
                archive.getInputStream(archive.getEntry("assets/thunder/runtime.js")).readBytes(),
                Charsets.UTF_8,
            )
            assertTrue(embedded.startsWith(
                "globalThis.__THUNDER_HOST__={\"packageName\":\"com.discord\"," +
                    "\"nativeCapabilities\":{\"chatBubbles\":true},",
            ))
            rewritten
        }

        val cloneInput = ApkSetInput(
            packageName = BinaryManifestFixture.OUTPUT_PACKAGE,
            versionCode = 343205,
            artifacts = listOf(ApkArtifactInput(null, cloneBase, sha256(cloneBase))),
            sourceSignerSha256 = listOf("b".repeat(64)),
            versionName = "343.6 - Alpha",
        )
        val countingChatWeaver = CountingChatBubblesDexWeaver()
        val secondRuntime = "(()=>{globalThis.__THUNDER_TEST__='chat-second';})();".repeat(4).toByteArray()
        val secondBootstrapDex = fakeDex(9)
        val steadyBackend = PurposeBuiltInjectionBackend(
            { secondBootstrapDex },
            { runtimeBundle(secondRuntime) },
            FakeReactNativeDexWeaver,
            countingChatWeaver,
        )
        assertEquals(BackendCompatibility.COMPATIBLE, steadyBackend.analyse(cloneInput).compatibility)
        val steadyOutput = steadyBackend.apply(steadyBackend.prepare(InjectionPlan(
            "chat-bubbles-second",
            cloneInput,
            BinaryManifestFixture.OUTPUT_PACKAGE,
            temporary.newFolder("chat-bubbles-second-output"),
            "0.6.0",
            1,
        )))
        steadyBackend.verify(cloneInput, steadyOutput)
        assertEquals("Authenticated ChatBubbles refresh must not parse the host DEX", 0, countingChatWeaver.inspections)
        ZipFile(steadyOutput.artifacts.single().file).use { archive ->
            assertArrayEquals(patchedHostDex, archive.getInputStream(archive.getEntry("classes.dex")).readBytes())
            assertArrayEquals(secondBootstrapDex, archive.getInputStream(archive.getEntry("classes2.dex")).readBytes())
        }
    }

    private fun embedded(versionName: String, versionCode: Long, runtime: ByteArray): ByteArray =
        ("globalThis.__THUNDER_HOST__={\"packageName\":\"com.discord\"," +
            "\"nativeCapabilities\":{\"chatBubbles\":false}," +
            "\"versionCode\":\"$versionCode\",\"versionName\":\"$versionName\"};\n").toByteArray() + runtime

    private fun runtimeBundle(bytes: ByteArray, version: String = "0.0.1"): RuntimeBundle =
        RuntimeBundle(version, bytes)

    private fun createApk(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { output ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (name.endsWith(".so")) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun rewriteApkEntry(
        input: File,
        output: File,
        targetName: String,
        transform: (ByteArray) -> ByteArray,
    ) {
        var replaced = false
        ZipFile(input).use { archive ->
            ZipOutputStream(output.outputStream()).use { sink ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val sourceEntry = entries.nextElement()
                    val bytes = archive.getInputStream(sourceEntry).use { it.readBytes() }
                    sink.putNextEntry(ZipEntry(sourceEntry.name))
                    sink.write(if (sourceEntry.name == targetName) transform(bytes).also { replaced = true } else bytes)
                    sink.closeEntry()
                }
            }
        }
        check(replaced) { "Missing test APK entry: $targetName" }
    }

    private fun fakeDex(seed: Int): ByteArray = ByteArray(112).also { bytes ->
        "dex\n035\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(32, bytes.size)
        bytes[111] = seed.toByte()
    }

    private object FakeReactNativeDexWeaver : ReactNativeDexWeaver {
        override fun originalCallCount(dex: ByteArray): Int = if (dex.last() == 1.toByte()) 2 else 0

        override fun patchedCallCount(dex: ByteArray): Int = if (dex.last() == 2.toByte()) 2 else 0

        override fun rewrite(dex: ByteArray): ByteArray = dex.copyOf().also { it[it.lastIndex] = 2 }

        override fun verify(original: ByteArray, rewritten: ByteArray) {
            assertEquals(1, original.last().toInt())
            assertEquals(2, rewritten.last().toInt())
        }
    }

    private class CountingReactNativeDexWeaver : ReactNativeDexWeaver by FakeReactNativeDexWeaver {
        var inspections: Int = 0
            private set

        override fun callCounts(dex: ByteArray): ReactNativeCallCounts {
            inspections++
            return FakeReactNativeDexWeaver.callCounts(dex)
        }
    }

    private object FakeChatBubblesDexWeaver : ChatBubblesDexWeaver {
        override fun inspect(dex: ByteArray): ChatBubblesDexStatus = when (dex[dex.lastIndex - 1].toInt()) {
            1 -> ChatBubblesDexStatus.UNPATCHED
            2 -> ChatBubblesDexStatus.PATCHED
            else -> ChatBubblesDexStatus.UNSUPPORTED
        }

        override fun rewrite(dex: ByteArray): ByteArray {
            check(inspect(dex) == ChatBubblesDexStatus.UNPATCHED)
            return dex.copyOf().also { it[it.lastIndex - 1] = 2 }
        }

        override fun verify(original: ByteArray, rewritten: ByteArray) {
            assertEquals(ChatBubblesDexStatus.UNPATCHED, inspect(original))
            assertEquals(ChatBubblesDexStatus.PATCHED, inspect(rewritten))
            assertArrayEquals(
                original.copyOf().also { it[it.lastIndex - 1] = 2 },
                rewritten,
            )
        }
    }

    private class CountingChatBubblesDexWeaver : ChatBubblesDexWeaver by FakeChatBubblesDexWeaver {
        var inspections: Int = 0
            private set

        override fun inspect(dex: ByteArray): ChatBubblesDexStatus {
            inspections++
            return FakeChatBubblesDexWeaver.inspect(dex)
        }
    }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
