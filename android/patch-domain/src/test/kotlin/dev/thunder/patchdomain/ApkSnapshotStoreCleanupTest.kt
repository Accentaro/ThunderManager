package dev.thunder.patchdomain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class ApkSnapshotStoreCleanupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun removesOnlyAbandonedUuidTransactionDirectories() = runBlocking {
        val root = temporaryFolder.newFolder("transactions")
        val stale = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        File(stale, "base.apk").writeBytes(byteArrayOf(1, 2, 3))
        val unrelatedDirectory = File(root, "keep-me").apply { mkdirs() }
        val unrelatedFile = File(root, "keep-me.txt").apply { writeText("safe") }

        val result = ApkSnapshotStore(root, UUID.randomUUID().toString())
            .reapAbandonedTransactions()

        assertEquals(SnapshotCleanupResult(1, 0, 0), result)
        assertFalse(stale.exists())
        assertTrue(unrelatedDirectory.isDirectory)
        assertTrue(unrelatedFile.isFile)
    }

    @Test
    fun retainsTransactionsOwnedByTheCurrentProcess() = runBlocking {
        val root = temporaryFolder.newFolder("transactions")
        val ownerToken = UUID.randomUUID().toString()
        val active = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        File(active, "journal.json").writeText(
            "{\"transactionId\":\"${active.name}\",\"ownerToken\":\"$ownerToken\"}",
        )

        val result = ApkSnapshotStore(root, ownerToken).reapAbandonedTransactions()

        assertEquals(SnapshotCleanupResult(0, 1, 0), result)
        assertTrue(active.isDirectory)
    }
}
