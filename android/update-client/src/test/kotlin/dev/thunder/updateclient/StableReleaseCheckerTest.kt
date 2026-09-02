package dev.thunder.updateclient

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.URI

class StableReleaseCheckerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `successful automatic checks are cached for exactly six hours and manual checks bypass cache`() = runBlocking {
        var now = 10_000L
        val fetcher = FakeFetcher(UpdateTestFixtures.thunderManifestBytes())
        val checker = StableReleaseChecker(fetcher, ReleaseCheckCache(temporary.newFolder()), { now })

        assertEquals(ReleaseCheckSource.NETWORK, checker.check(ReleaseProduct.THUNDER).source)
        now += ReleaseCheckCache.CACHE_TTL_MILLIS - 1
        assertEquals(ReleaseCheckSource.CACHE, checker.check(ReleaseProduct.THUNDER).source)
        assertEquals(ReleaseCheckSource.NETWORK, checker.check(ReleaseProduct.THUNDER, forceRefresh = true).source)
        now += ReleaseCheckCache.CACHE_TTL_MILLIS
        assertEquals(ReleaseCheckSource.NETWORK, checker.check(ReleaseProduct.THUNDER).source)
        assertEquals(3, fetcher.calls)
    }

    @Test
    fun `newer equal and older releases compare using SemVer`() = runBlocking {
        val installed = SemanticVersion.parseStableRelease("1.2.3")
        listOf("1.2.4" to true, "1.2.3" to false, "1.2.2" to false).forEach { (version, expected) ->
            val checker = StableReleaseChecker(
                FakeFetcher(UpdateTestFixtures.thunderManifestBytes(version)),
                ReleaseCheckCache(temporary.newFolder()),
            )
            val checked = checker.check(ReleaseProduct.THUNDER)
            assertEquals(expected, checked.isNewerThan(installed))
        }
    }

    @Test
    fun `network failures are not cached and corrupt cached manifests are ignored`() {
        var now = 2_000L
        val fetcher = FakeFetcher(UpdateTestFixtures.thunderManifestBytes()).apply {
            failure = IOException("offline")
        }
        val cache = ReleaseCheckCache(temporary.newFolder())
        val checker = StableReleaseChecker(fetcher, cache, { now })

        assertThrows(IOException::class.java) {
            runBlocking { checker.check(ReleaseProduct.THUNDER) }
        }
        fetcher.failure = null
        assertEquals(ReleaseCheckSource.NETWORK, runBlocking { checker.check(ReleaseProduct.THUNDER) }.source)
        assertEquals(2, fetcher.calls)

        now += ReleaseCheckCache.CACHE_TTL_MILLIS
        cache.writeSuccessful(ReleaseProduct.THUNDER, now, "not-json".toByteArray())
        assertEquals(ReleaseCheckSource.NETWORK, runBlocking { checker.check(ReleaseProduct.THUNDER) }.source)
        assertEquals(3, fetcher.calls)
    }

    private class FakeFetcher(var response: ByteArray) : ReleaseBytesFetcher {
        var calls = 0
        var failure: IOException? = null

        override suspend fun fetch(url: URI, maximumBytes: Int): ByteArray {
            calls++
            failure?.let { throw it }
            assertTrue(url.toASCIIString().startsWith("https://"))
            assertFalse(response.isEmpty())
            return response
        }
    }
}
