package xyz.billybishop4.fleet.tv

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class StalkerCacheTest {
    private class Portal : AutoCloseable {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        var expire = 0
        var rateLimitHandshake = false
        var handshakes = 0
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    requests += r
                    val action = r.requestUrl!!.queryParameter("action")
                    if (action == "handshake") {
                        handshakes++
                        if (rateLimitHandshake) return MockResponse().setResponseCode(429)
                    }
                    if (action != "handshake" && expire > 0) { expire--; return MockResponse().setResponseCode(401) }
                    val body = when (action) {
                        "handshake" -> """{"token":"token-$handshakes"}"""
                        "get_profile" -> """{"id":42,"status":0}"""
                        "get_genres" -> """[{"id":"1","title":"News"}]"""
                        "get_all_channels" -> """{"total_items":1,"max_page_items":1,"data":[{"id":"1","name":"News 1","tv_genre_id":"1","cmd":"ffmpeg http://localhost/ch/1"}]}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody("{\"js\":$body}")
                }
            }
            server.start()
        }
        fun provider() = Provider("p", "Test", server.url("/stalker_portal/c/").toString(), mac = "00:1A:79:00:00:01")
        override fun close() = server.shutdown()
    }

    private fun tempDir(): File = Files.createTempDirectory("stalker-cache-test").toFile()
    private fun sampleCatalog() = StalkerCatalog(mapOf("1" to "News"),
        listOf(Channel("1", "News 1", "", "News", "", command = "ffmpeg http://localhost/ch/1", number = "1")))

    @Test fun `cache key changes with portal URL and with MAC and contains neither`() {
        val base = StalkerCatalogCodec.keyFor("https://portal.example/stalker_portal/c/", "00:1A:79:00:00:01")
        assertEquals(base, StalkerCatalogCodec.keyFor("https://portal.example/stalker_portal/c/", "00-1a-79-00-00-01"))
        assertNotEquals(base, StalkerCatalogCodec.keyFor("https://other.example/stalker_portal/c/", "00:1A:79:00:00:01"))
        assertNotEquals(base, StalkerCatalogCodec.keyFor("https://portal.example/stalker_portal/c/", "00:1A:79:00:00:02"))
        val name = StalkerCatalogCodec.fileNameFor(Provider("p", "Test", "https://portal.example/", mac = "00:1A:79:00:00:01"))
        assertFalse(name.contains("portal.example")); assertFalse(name.contains("00"))
    }

    @Test fun `catalog encode decode round trips genres and channels`() {
        val decoded = StalkerCatalogCodec.decode(StalkerCatalogCodec.encode(sampleCatalog(), 1234L))
        assertEquals(1234L, decoded!!.first)
        assertEquals(mapOf("1" to "News"), decoded.second.genres)
        assertEquals("News 1", decoded.second.channels.single().name)
        assertEquals("News", decoded.second.channels.single().group)
        assertEquals("ffmpeg http://localhost/ch/1", decoded.second.channels.single().command)
        assertNull(StalkerCatalogCodec.decode("not json"))
        assertNull(StalkerCatalogCodec.decode("""{"savedAt":1,"genres":{},"channels":[]}"""))
    }

    @Test fun `freshness is three days from save and rejects future or missing stamps`() {
        val ttl = StalkerCatalogCodec.TTL_MS
        assertEquals(3L * 24 * 60 * 60 * 1000, ttl)
        assertTrue(StalkerCatalogCodec.isFresh(1000, 1000 + ttl - 1))
        assertFalse(StalkerCatalogCodec.isFresh(1000, 1000 + ttl))
        assertFalse(StalkerCatalogCodec.isFresh(1000, 999))
        assertFalse(StalkerCatalogCodec.isFresh(0, 1000))
    }

    @Test fun `file cache reads fresh entries, expires by its clock, and deletes`() {
        val dir = tempDir(); val now = AtomicLong(10_000)
        val cache = StalkerCatalogFileCache(dir) { now.get() }
        val p = Provider("p", "Test", "https://portal.example/", mac = "00:1A:79:00:00:01")
        assertNull(cache.read(p))
        cache.write(p, sampleCatalog())
        assertEquals("News 1", cache.read(p)!!.channels.single().name)
        assertTrue(cache.fileFor(p).isFile)
        now.addAndGet(StalkerCatalogCodec.TTL_MS)
        assertNull(cache.read(p))
        now.set(10_000); cache.delete(p)
        assertNull(cache.read(p)); assertFalse(cache.fileFor(p).exists())
        dir.deleteRecursively()
    }

    @Test fun `fresh cache serves channels with no network, across repository instances`() {
        runBlocking {
            Portal().use { f ->
                val dir = tempDir(); val now = AtomicLong(50_000)
                val cache = StalkerCatalogFileCache(dir) { now.get() }
                val repo = Repository(emptyList(), stalkerCatalogCache = cache)
                assertEquals(1, repo.channels(f.provider()).size)
                val afterFirst = f.requests.size
                assertEquals(1, repo.channels(f.provider()).size)
                // A new Repository (app restart) with the same cache directory still needs no network.
                assertEquals(1, Repository(emptyList(), stalkerCatalogCache = cache).channels(f.provider()).size)
                assertEquals(afterFirst, f.requests.size)
                dir.deleteRecursively()
            }
        }
    }

    @Test fun `stale cache refetches and forced refresh refetches despite a fresh cache`() {
        runBlocking {
            Portal().use { f ->
                val dir = tempDir(); val now = AtomicLong(50_000)
                val cache = StalkerCatalogFileCache(dir) { now.get() }
                val repo = Repository(emptyList(), stalkerCatalogCache = cache)
                repo.channels(f.provider()); val afterFirst = f.requests.size
                repo.channels(f.provider(), forceRefresh = true)
                assertTrue(f.requests.size > afterFirst)
                val afterForced = f.requests.size
                now.addAndGet(StalkerCatalogCodec.TTL_MS + 1)
                repo.channels(f.provider())
                assertTrue(f.requests.size > afterForced)
                dir.deleteRecursively()
            }
        }
    }

    @Test fun `corrupt cache file is treated as a miss and refetched`() {
        runBlocking {
            Portal().use { f ->
                val dir = tempDir(); val now = AtomicLong(50_000)
                val cache = StalkerCatalogFileCache(dir) { now.get() }
                val repo = Repository(emptyList(), stalkerCatalogCache = cache)
                repo.channels(f.provider())
                cache.fileFor(f.provider()).writeText("garbage{{{")
                val before = f.requests.size
                assertEquals(1, repo.channels(f.provider()).size)
                assertTrue(f.requests.size > before)
                // The refetch rewrote a valid cache entry.
                assertEquals("News 1", cache.read(f.provider())!!.channels.single().name)
                dir.deleteRecursively()
            }
        }
    }

    @Test fun `expired token pauses at least two seconds before the single re-authentication`() {
        runBlocking {
            Portal().use { f ->
                f.expire = 1
                val now = AtomicLong(0); val delays = CopyOnWriteArrayList<Long>()
                val client = StalkerClient(Http(), nowMs = { now.get() }, delayMs = { ms -> delays += ms; now.addAndGet(ms) })
                assertEquals(1, client.channels(f.provider()).size)
                assertEquals(2, f.handshakes)
                assertTrue("expected a >=2s pause before re-auth, delays=$delays", delays.any { it >= StalkerClient.REAUTH_DELAY_MS })
            }
        }
    }

    @Test fun `handshakes are spaced by the minimum interval across operations`() {
        runBlocking {
            Portal().use { f ->
                val now = AtomicLong(0); val delays = CopyOnWriteArrayList<Long>()
                val client = StalkerClient(Http(), nowMs = { now.get() }, delayMs = { ms -> delays += ms; now.addAndGet(ms) })
                client.channels(f.provider())
                assertTrue(delays.isEmpty())
                client.channels(f.provider())
                assertEquals(2, f.handshakes)
                assertEquals(listOf(StalkerClient.MIN_HANDSHAKE_INTERVAL_MS), delays.toList())
            }
        }
    }

    @Test fun `persistent rejection stops after two handshakes in total`() {
        runBlocking {
            Portal().use { f ->
                f.expire = 99
                val now = AtomicLong(0); val delays = CopyOnWriteArrayList<Long>()
                val client = StalkerClient(Http(), nowMs = { now.get() }, delayMs = { ms -> delays += ms; now.addAndGet(ms) })
                try { client.channels(f.provider()); fail("Rejected portal accepted") } catch (_: UserMessage) { }
                assertEquals(2, f.handshakes)
                assertEquals(1, delays.size)
            }
        }
    }

    @Test fun `a 429 handshake is never retried`() {
        runBlocking {
            Portal().use { f ->
                f.rateLimitHandshake = true
                val now = AtomicLong(0); val delays = CopyOnWriteArrayList<Long>()
                val client = StalkerClient(Http(), nowMs = { now.get() }, delayMs = { ms -> delays += ms; now.addAndGet(ms) })
                try { client.channels(f.provider()); fail("Rate-limited portal accepted") } catch (e: HttpFailure) { assertEquals(429, e.status) }
                assertEquals(1, f.handshakes)
                assertTrue(delays.isEmpty())
            }
        }
    }
}
