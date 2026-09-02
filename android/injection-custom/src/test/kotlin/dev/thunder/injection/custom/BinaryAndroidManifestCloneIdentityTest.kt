package dev.thunder.injection.custom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryAndroidManifestCloneIdentityTest {
    @Test
    fun `base rewrite derives install identities but retains host routing names`() {
        val outputPackage = "dev.thunder.fixture"
        val result = BinaryAndroidManifest.rewriteBaseForClone(
            BinaryManifestFixture.create(),
            BinaryManifestFixture.SOURCE_PACKAGE,
            outputPackage,
            "Thunder",
        )

        assertEquals(outputPackage, BinaryAndroidManifest.readPackageName(result.bytes))
        assertEquals(
            listOf("$outputPackage.share"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "activity", "taskAffinity"),
        )
        assertEquals(
            listOf("$outputPackage.file-provider"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "provider", "authorities"),
        )
        assertEquals(
            listOf("$outputPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "permission", "name"),
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
            listOf(
                "manifest.package",
                "manifest.package-owned-task-affinities",
                "application.provider.authorities",
                "manifest.package-owned-permissions",
                "application.label",
                "application.activity.label",
                "application.activity-alias.label",
            ),
            result.changedManifestFields,
        )
    }

    @Test
    fun `split rewrite applies coexistence identities without changing bytecode-facing names`() {
        val outputPackage = "dev.thunder.fixture"
        val result = BinaryAndroidManifest.rewritePackageAndCoexistenceNames(
            BinaryManifestFixture.createSplit(),
            BinaryManifestFixture.SOURCE_PACKAGE,
            outputPackage,
        )

        assertEquals(outputPackage, BinaryAndroidManifest.readPackageName(result.bytes))
        assertEquals(
            listOf(BinaryManifestFixture.SPLIT_COMPONENT_NAME),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "activity", "name"),
        )
        assertEquals(
            listOf("$outputPackage.custom_tab"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "activity", "taskAffinity"),
        )
        assertEquals(
            listOf("$outputPackage.split-provider;com.example.shared-provider"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "provider", "authorities"),
        )
        assertEquals(
            listOf("$outputPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "uses-permission", "name"),
        )
        assertEquals(
            listOf("$outputPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "provider", "writePermission"),
        )
        assertEquals(
            listOf(BinaryManifestFixture.SPLIT_ACTION_NAME, BinaryManifestFixture.SYSTEM_ACTION_NAME),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "action", "name"),
        )
        assertEquals(
            listOf(BinaryManifestFixture.SPLIT_CATEGORY_NAME, BinaryManifestFixture.SYSTEM_CATEGORY_NAME),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "category", "name"),
        )
        assertEquals(
            listOf(
                "manifest.package",
                "manifest.package-owned-task-affinities",
                "application.provider.authorities",
                "manifest.package-owned-permissions",
            ),
            result.changedManifestFields,
        )
    }

    @Test
    fun `schema 2 migration reverses routing names and normalizes split coexistence identities`() {
        val outputPackage = BinaryManifestFixture.OUTPUT_PACKAGE
        val legacy = BinaryManifestFixture.createSplit(
            packageName = outputPackage,
            taskAffinityPackageName = outputPackage,
            providerPermissionPackageName = BinaryManifestFixture.SOURCE_PACKAGE,
            routingPackageName = outputPackage,
        )
        val result = BinaryAndroidManifest.migrateSchema2CloneIdentities(
            legacy,
            BinaryManifestFixture.SOURCE_PACKAGE,
            outputPackage,
        )

        assertEquals(outputPackage, BinaryAndroidManifest.readPackageName(result.bytes))
        assertEquals(
            listOf("$outputPackage.custom_tab"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "activity", "taskAffinity"),
        )
        assertEquals(
            listOf("$outputPackage.split-provider;com.example.shared-provider"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "provider", "authorities"),
        )
        assertEquals(
            listOf("$outputPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "uses-permission", "name"),
        )
        assertEquals(
            listOf(BinaryManifestFixture.SPLIT_ACTION_NAME, BinaryManifestFixture.SYSTEM_ACTION_NAME),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "action", "name"),
        )
        assertEquals(
            listOf(BinaryManifestFixture.SPLIT_CATEGORY_NAME, BinaryManifestFixture.SYSTEM_CATEGORY_NAME),
            BinaryAndroidManifest.readStringAttributeValues(result.bytes, "category", "name"),
        )
        assertEquals(
            listOf(
                "application.provider.authorities",
                "manifest.package-owned-permissions",
                "manifest.restored-host-intent-actions",
                "manifest.restored-host-intent-categories",
            ),
            result.changedManifestFields,
        )
    }

    @Test
    fun `schema 2 migration preserves an already-correct manifest byte for byte`() {
        val outputPackage = BinaryManifestFixture.OUTPUT_PACKAGE
        val correct = BinaryManifestFixture.createSplit(
            packageName = outputPackage,
            taskAffinityPackageName = outputPackage,
            providerPermissionPackageName = outputPackage,
            routingPackageName = BinaryManifestFixture.SOURCE_PACKAGE,
        )
        val result = BinaryAndroidManifest.migrateSchema2CloneIdentities(
            correct,
            BinaryManifestFixture.SOURCE_PACKAGE,
            outputPackage,
        )

        assertArrayEquals(correct, result.bytes)
        assertEquals(emptyList<String>(), result.changedManifestFields)
    }
}
