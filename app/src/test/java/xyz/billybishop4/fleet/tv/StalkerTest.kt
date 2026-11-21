package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class StalkerTest {
    private class Portal : AutoCloseable {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        var fallback = false
        var login = false
        var blocked = false
        var expire = 0
        var malformed = false
        var paged = false
        var unsupportedList = false
        var invalidMedia = false
        var staleTotal = false
        var guideUnsupported = false
        var bareRedirect = false
        var bareProbe = false
        var strictTokens = false
        var tokens = 0
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    requests += r
                    val q = r.requestUrl!!; val action = q.queryParameter("action")
                    if ((bareRedirect || bareProbe) && !q.encodedPath.startsWith("/stalker_portal/")) {
                        if (action == null && q.encodedPath == "/" && bareRedirect)
                            return MockResponse().setResponseCode(302).addHeader("Location", "/stalker_portal/c/")
                        return MockResponse().setResponseCode(404)
                    }
                    if (bareRedirect && action == null && q.encodedPath == "/stalker_portal/c/") return MockResponse().setBody("<html>portal</html>")
                    if (fallback && q.encodedPath.endsWith("/server/load.php")) return MockResponse().setResponseCode(404)
                    if (malformed) return MockResponse().setBody("<html>wrong address</html>")
                    if (action != "handshake" && expire > 0) { expire--; return MockResponse().setResponseCode(401) }
                    if (strictTokens && action != "handshake" && r.getHeader("Authorization") != "Bearer token-$tokens") return MockResponse().setResponseCode(401)
                    val body = when(action) {
                        "handshake" -> """{"token":"token-${++tokens}"}"""
                        "get_profile" -> """{"id":42,"status":${if (blocked) 1 else if (login && q.queryParameter("auth_second_step") != "1") 2 else 0}}"""
                        "do_auth" -> "true"
                        "get_short_epg" -> {
                            if(guideUnsupported)return MockResponse().setResponseCode(404)
                            """[{"name":"News","start_timestamp":1700000000,"stop_timestamp":1700003600}]"""
                        }
                        "get_epg_info" -> """{"data":{"1":[{"name":"Fallback news","start_timestamp":1700000000,"stop_timestamp":1700003600}]}}"""
                        "get_genres" -> """[{"id":"1","title":"News"}]"""
                        "get_all_channels", "get_ordered_list" -> {
                            if (staleTotal && action == "get_all_channels") return MockResponse().setBody("""{"js":{"total_items":3,"max_page_items":1,"data":[{"id":"1","name":"A","cmd":"ffmpeg http://localhost/ch/1"},{"id":"2","name":"B","cmd":"ffmpeg http://localhost/ch/2"}]}}""")
                            if (unsupportedList && action == "get_all_channels") return MockResponse().setResponseCode(404)
                            val page = if (paged && action == "get_ordered_list") q.queryParameter("p")!!.toInt() else 1
                            """{"total_items":${if (paged) 2 else 1},"max_page_items":1,"data":[{"id":"$page","name":"News $page","tv_genre_id":"1","logo":"logos/news.png","cmd":"ffmpeg http://localhost/ch/$page"}]}"""
                        }
                        "create_link" -> JSONObject().put("cmd", if (invalidMedia) "ffmpeg udp://unsafe:1234" else "ffmpeg ${server.url("/media/live.m3u8?token=signed")}").toString()
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody("{\"js\":$body}")
                }
            }
            server.start()
        }
        fun provider() = Provider("p", "Test", server.url("/stalker_portal/c/").toString(), mac = "00:1A:79:00:00:01")
        fun bareProvider() = Provider("p", "Test", server.url("/").toString(), mac = "00:1A:79:00:00:01")
        fun repo() = Repository(emptyList())
        override fun close() = server.shutdown()
    }
    @Test fun `portal addresses preserve prefixes and accept explicit endpoints`() {
        assertEquals(listOf("https://p.example/stalker_portal/server/load.php", "https://p.example/stalker_portal/portal.php"), portalAddress("https://p.example/stalker_portal/c/#/home").endpoints)
        assertEquals("https://p.example/stalker_portal/c/", portalAddress("https://p.example/stalker_portal/server/load.php").referer)
        assertEquals(listOf("https://p.example/custom/portal.php?key=a%26b"), portalAddress("https://p.example/custom/portal.php?key=a%26b").endpoints)
        assertEquals("https://p.example/path%20name/server/load.php", portalAddress("https://p.example/path%20name/").endpoints.first())
    }
    @Test fun `only URL and MAC are required and optional identity remains empty`() {
        val p = validatedPortal(Provider("p", "", "https://portal.example/", mac = "00-1a-79-00-00-01"))
        assertEquals("portal.example", p.name); assertEquals("00:1A:79:00:00:01", p.mac)
        assertEquals("", p.serialNumber); assertEquals("", p.deviceId); assertEquals("", p.signature)
    }
    @Test fun `invalid MAC credentials and header injection are rejected`() {
        val base = Provider("p", "", "https://portal.example/", mac = "00:1A:79:00:00:01")
        for (p in listOf(base.copy(mac = "broken"), base.copy(userAgent = "a\r\nCookie: bad"), base.copy(username = "only"), base.copy(kind = "xtream"), base.copy(timezone = "bad/timezone"))) {
            try { validatedPortal(p); fail("Invalid provider accepted") } catch (_: UserMessage) { }
        }
    }
    @Test fun `handshake profile genres and channel commands use MAC bearer and custom identity`() = runBlocking {
        Portal().use { f ->
            val p = f.provider().copy(serialNumber = "SERIAL", deviceId = "ID&1", deviceId2 = "ID+2", signature = "signature", stbType = "MAG254", hwVersion = "2.20-IB-00", userAgent = "Custom-Agent",
                hwVersion2 = "hardware-two", pin = "1234", version = "Custom firmware", imageVersion = "218")
            val c = f.repo().channels(p).single()
            assertEquals("News", c.group); assertTrue(c.logo.endsWith("/stalker_portal/logos/news.png")); assertTrue(c.url.isEmpty())
            val handshake = f.requests.first(); assertNull(handshake.getHeader("Authorization"))
            val profile = f.requests.first { it.requestUrl!!.queryParameter("action") == "get_profile" }
            assertEquals("Bearer token-1", profile.getHeader("Authorization")); assertEquals("Custom-Agent", profile.getHeader("User-Agent"))
            assertTrue(profile.getHeader("Cookie")!!.contains("mac=00%3A1A%3A79%3A00%3A00%3A01"))
            for ((key, value) in mapOf("sn" to "SERIAL", "device_id" to "ID&1", "device_id2" to "ID+2", "signature" to "signature", "stb_type" to "MAG254", "hw_version" to "2.20-IB-00",
                "hw_version_2" to "hardware-two", "pin" to "1234", "ver" to "Custom firmware", "image_version" to "218"))
                assertEquals(value, profile.requestUrl!!.queryParameter(key))
        }
    }
    @Test fun `blank optional identity is omitted and command becomes a fresh HTTP link`() = runBlocking {
        Portal().use { f ->
            val p = f.provider(); val repo = f.repo(); val c = repo.channels(p).single(); val watch = repo.liveWatch(p, c)
            val profiles = f.requests.filter { it.requestUrl!!.queryParameter("action") == "get_profile" }
            for (r in profiles) for (key in listOf("sn", "device_id", "device_id2", "signature")) assertNull(r.requestUrl!!.queryParameter(key))
            assertTrue(watch.live); assertTrue(watch.stream.url.contains("/media/live.m3u8?token=signed"))
            assertNull(watch.headers["Cookie"]); assertNull(watch.headers["Authorization"])
            assertEquals(c.command, f.requests.last().requestUrl!!.queryParameter("cmd"))
            assertEquals(2, f.tokens)
        }
    }
    @Test fun `discovery addresses follow redirects then ancestors without repeating endpoints`() {
        val list = discoveryAddresses("https://p.example/", "https://p.example/stalker_portal/c/")
        val endpoints = list.flatMap { it.endpoints }
        assertEquals("https://p.example/server/load.php", endpoints.first())
        assertEquals("https://p.example/stalker_portal/server/load.php", endpoints.first { it.contains("stalker_portal") })
        assertEquals(endpoints.size, endpoints.toSet().size)
        assertEquals(listOf("https://p.example/server/load.php", "https://p.example/portal.php"), discoveryAddresses("https://p.example/", null).flatMap { it.endpoints }.take(2))
    }
    @Test fun `bare root URL follows redirect to the real portal`() = runBlocking {
        Portal().use { f -> f.bareRedirect = true; assertEquals(1, f.repo().channels(f.bareProvider()).size)
            assertTrue(f.requests.any { it.requestUrl!!.encodedPath == "/stalker_portal/server/load.php" }) }
    }
    @Test fun `bare root URL without redirect is probed for the standard portal prefix`() = runBlocking {
        Portal().use { f -> f.bareProbe = true; assertEquals(1, f.repo().channels(f.bareProvider()).size)
            assertTrue(f.requests.any { it.requestUrl!!.encodedPath == "/stalker_portal/server/load.php" }) }
    }
    @Test fun `root URL falls back from load endpoint to portal endpoint`() = runBlocking {
        Portal().use { f -> f.fallback = true; assertEquals(1, f.repo().channels(f.provider()).size); assertTrue(f.requests.any { it.requestUrl!!.encodedPath.endsWith("/portal.php") }) }
    }
    @Test fun `username authentication follows portal second step`() = runBlocking {
        Portal().use { f ->
            f.login = true
            assertEquals(1, f.repo().channels(f.provider().copy(username = "a&b", password = "p+q")).size)
            val auth = f.requests.first { it.requestUrl!!.queryParameter("action") == "do_auth" }
            assertEquals("a&b", auth.requestUrl!!.queryParameter("login")); assertEquals("p+q", auth.requestUrl!!.queryParameter("password"))
            assertTrue(f.requests.any { it.requestUrl!!.queryParameter("auth_second_step") == "1" })
        }
    }
    @Test fun `blocked profiles and missing required login fail clearly`() = runBlocking {
        Portal().use { f ->
            f.blocked = true
            try { f.repo().channels(f.provider()); fail("Blocked account accepted") } catch (e: UserMessage) { assertTrue(e.message!!.contains("rejected")) }
            f.blocked = false; f.login = true
            try { f.repo().channels(f.provider()); fail("Missing login accepted") } catch (e: UserMessage) { assertTrue(e.message!!.contains("username")) }
        }
    }
    @Test fun `expired authorization renews once and repeated rejection is bounded`() = runBlocking {
        Portal().use { f ->
            f.expire = 1; assertEquals(1, f.repo().channels(f.provider()).size); assertEquals(2, f.tokens)
            f.expire = 20; val before = f.tokens
            try { f.repo().channels(f.provider()); fail("Rejected portal accepted") } catch (_: UserMessage) { }
            assertEquals(2, f.tokens - before)
        }
    }
    @Test fun `partial channel listing is completed using ordered pages`() = runBlocking {
        Portal().use { f -> f.paged = true; assertEquals(listOf("1", "2"), f.repo().channels(f.provider()).map { it.id }) }
    }
    @Test fun `unsupported get all channels uses ordered list`() = runBlocking {
        Portal().use { f -> f.unsupportedList = true; assertEquals(1, f.repo().channels(f.provider()).size) }
    }
    @Test fun `bulk list with stale total does not trigger hundreds of page requests`() = runBlocking {
        Portal().use { f ->
            f.staleTotal = true
            assertEquals(2, f.repo().channels(f.provider()).size)
            assertFalse(f.requests.any { it.requestUrl!!.queryParameter("action") == "get_ordered_list" })
        }
    }
    @Test fun `invalid endpoint and unsupported playback commands fail without execution`() = runBlocking {
        Portal().use { f ->
            f.malformed = true
            try { f.repo().channels(f.provider()); fail("HTML accepted") } catch (e: UserMessage) { assertTrue(e.message!!.contains("No Stalker API")) }
            f.malformed = false; val c = f.repo().channels(f.provider()).single(); f.invalidMedia = true
            try { f.repo().liveWatch(f.provider(), c); fail("Unsafe command accepted") } catch (e: UserMessage) { assertTrue(e.message!!.contains("playable HTTP")) }
        }
    }
    @Test fun `unsupported short guide uses two hour fallback and caches the chosen channel`() = runBlocking {
        Portal().use { f ->
            f.guideUnsupported=true
            val repo=f.repo();val c=Channel("1","News","",command="ffmpeg http://localhost/ch/1")
            assertEquals("Fallback news",repo.guide(f.provider(),c).single().name)
            assertEquals("Fallback news",repo.guide(f.provider(),c).single().name)
            assertEquals(1,f.tokens)
            assertEquals("2",f.requests.last().requestUrl!!.queryParameter("period"))
        }
    }
    @Test fun `concurrent guide and tuning do not invalidate each others authentication`() = runBlocking {
        Portal().use { f ->
            f.strictTokens=true
            val client=StalkerClient(Http());val p=f.provider();val c=Channel("1","News","",command="ffmpeg http://localhost/ch/1")
            awaitAll(async { client.guide(p,c) },async { client.watch(p,c,false) })
            val actions=f.requests.map { it.requestUrl!!.queryParameter("action") }
            assertEquals(2,f.tokens);assertEquals(listOf(0,3),actions.indices.filter { actions[it]=="handshake" })
            assertTrue("create_link" in actions);assertTrue("get_short_epg" in actions)
        }
    }
}
