package xyz.billybishop4.fleet.tv

import java.net.URI
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Optional provider-issued identity values are never invented or substituted. */
fun validatedPortal(p: Provider): Provider {
    if (p.kind != "stalker") throw UserMessage("Edit this provider to configure a Stalker portal.")
    portalAddress(p.url)
    val mac = p.mac.trim().replace('-', ':').uppercase(Locale.ROOT)
    if (!Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}").matches(mac)) throw UserMessage("Enter the portal's registered MAC address, for example 00:1A:79:12:34:56.")
    if (listOf(p.userAgent, p.serialNumber, p.deviceId, p.deviceId2, p.signature, p.stbType, p.timezone, p.hwVersion, p.hwVersion2, p.pin, p.version, p.imageVersion).any { it.any { c -> c == '\r' || c == '\n' } })
        throw UserMessage("Device settings must each be a single line.")
    if (p.username.isBlank() != p.password.isBlank()) throw UserMessage("Enter both username and password, or leave both empty.")
    if (p.timezone.isNotBlank() && p.timezone.trim() !in TimeZone.getAvailableIDs()) throw UserMessage("Enter a valid timezone, such as America/Los_Angeles.")
    if (p.imageVersion.isNotBlank() && !Regex("[0-9]{1,10}").matches(p.imageVersion.trim())) throw UserMessage("Image Version must be a number, or empty for the default.")
    val model = p.stbType.trim().ifBlank { "MAG250" }
    if (!Regex("[A-Za-z0-9_-]{1,32}").matches(model)) throw UserMessage("Enter a valid STB model, such as MAG250.")
    return p.copy(name = p.name.trim().ifBlank { URI(p.url.trim()).host }, url = p.url.trim(), mac = mac,
        username = p.username.trim(), serialNumber = p.serialNumber.trim(), deviceId = p.deviceId.trim(), deviceId2 = p.deviceId2.trim(),
        signature = p.signature.trim(), stbType = model, timezone = p.timezone.trim(), hwVersion = p.hwVersion.trim(),
        hwVersion2 = p.hwVersion2.trim(), pin = p.pin.trim(), version = p.version.trim(), imageVersion = p.imageVersion.trim())
}

internal data class PortalAddress(val endpoints: List<String>, val referer: String, val base: String)
internal fun portalAddress(value: String): PortalAddress {
    if (!isHttp(value.trim())) throw UserMessage("Enter an HTTP or HTTPS portal address.")
    val uri = URI(value.trim()); val path = uri.rawPath.trimEnd('/')
    val root = when {
        path.endsWith("/server/load.php") -> path.removeSuffix("/server/load.php")
        path.endsWith(".php") -> path.substringBeforeLast('/', "")
        path.endsWith("/c") -> path.removeSuffix("/c")
        else -> path
    }
    val origin = "${uri.scheme}://${uri.rawAuthority}"
    val suffix = uri.rawQuery?.let { "?$it" }.orEmpty()
    val endpoints = if (path.endsWith(".php")) listOf(origin + uri.rawPath + suffix)
        else listOf("$origin$root/server/load.php$suffix", "$origin$root/portal.php$suffix")
    return PortalAddress(endpoints, "$origin$root/c/", "$origin$root/")
}

/**
 * Candidate portal addresses for discovery, best first: the entered address,
 * the address its redirects land on, ancestor prefixes of both paths, and the
 * well-known /stalker_portal/ prefix. Endpoints are deduplicated across
 * candidates so no URL is handshaken twice.
 */
internal fun discoveryAddresses(entered: String, resolved: String?): List<PortalAddress> {
    val out = mutableListOf<PortalAddress>(); val seen = mutableSetOf<String>()
    fun add(value: String) {
        val address = runCatching { portalAddress(value) }.getOrNull() ?: return
        val fresh = address.endpoints.filter { seen.add(it) }
        if (fresh.isNotEmpty()) out += address.copy(endpoints = fresh)
    }
    fun ancestors(value: String) {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return
        val origin = "${uri.scheme}://${uri.rawAuthority}"
        var path = uri.rawPath.orEmpty().trimEnd('/')
        while (path.isNotEmpty()) {
            path = path.substringBeforeLast('/', "")
            add(origin + path + "/")
        }
    }
    add(entered)
    // A cross-origin redirect target never receives this portal's MAC cookie,
    // so only same-origin redirect results feed discovery.
    if (!resolved.isNullOrBlank() && resolved != entered && sameOrigin(entered, resolved)) { add(resolved); ancestors(resolved) }
    ancestors(entered)
    runCatching { URI(entered.trim()) }.getOrNull()?.let { add("${it.scheme}://${it.rawAuthority}/stalker_portal/") }
    return out
}

private class ExpiredPortal : java.io.IOException()
private class WrongEndpoint : java.io.IOException()

/** Each operation authenticates afresh; short-lived tokens never reach disk or a CDN. */
class StalkerClient(
    private val http: Http,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    companion object {
        /** Pause before the single allowed re-authentication after a token is rejected. */
        const val REAUTH_DELAY_MS: Long = 2000
        /** Minimum spacing between handshakes across operations, so portals are not bombarded with token requests. */
        const val MIN_HANDSHAKE_INTERVAL_MS: Long = 2000
    }
    private val operations = Mutex()
    // Only touched while holding the operations mutex, which every session does.
    private var lastHandshakeAtMs: Long? = null
    private suspend fun <T> session(provider: Provider, block: suspend Session.() -> T): T = operations.withLock {
        val p = validatedPortal(provider)
        repeat(2) { attempt ->
            try { return Session(p).let { it.authenticate(); it.block() } }
            catch (e: ExpiredPortal) {
                if (attempt == 1) throw UserMessage("Portal authorization failed. Check the registered MAC and optional device credentials.")
                // Exactly one re-authentication per operation, and only after a
                // pause. The mutex is held throughout, so no other operation can
                // handshake during the wait. An HttpFailure (including 429) is
                // not caught here and never triggers a re-authentication.
                delayMs(REAUTH_DELAY_MS)
            }
        }
        error("Unreachable")
    }
    suspend fun channels(provider: Provider): List<Channel> = session(provider) { channels() }
    suspend fun catalog(provider: Provider): StalkerCatalog = session(provider) { catalog() }
    suspend fun guide(provider: Provider, channel: Channel): List<GuideProgram> = session(provider) {
        val result = try { call("itv", "get_short_epg", mapOf("ch_id" to channel.id, "size" to "8")) }
        catch (e: HttpFailure) {
            if (e.status !in listOf(400, 404, 405, 501)) throw e
            call("itv", "get_epg_info", mapOf("period" to "2"))
        }
        parseGuide(result, channel.id, p.timezone)
    }
    suspend fun watch(provider: Provider, channel: Channel, refresh: Boolean): Watch = session(provider) {
        val current = if (refresh) channels().find { it.id == channel.id }
            ?: throw UserMessage("This channel is no longer in the portal. Refresh the channels.") else channel
        val response = call("itv", "create_link", mapOf("cmd" to current.command, "series" to "0", "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0") +
            provider.pin.takeIf { it.isNotBlank() }?.let { mapOf("pin" to it) }.orEmpty())
        val command = (response as? JSONObject)?.optString("cmd").orEmpty()
        val media = Regex("https?://[^\\s\"']+", RegexOption.IGNORE_CASE).find(command)?.value
            ?: command.trim().takeIf { it.startsWith('/') }?.let { URI(address.base).resolve(it).toString() }
        if (media == null || !isHttp(media) || (URI(media).host.lowercase(Locale.ROOT) in setOf("localhost", "127.0.0.1", "::1", "[::1]") && !sameOrigin(media, provider.url)))
            throw UserMessage("The portal did not return a playable HTTP channel link.")
        Watch(Title(current.id, current.name, "channel"), Stream(current.name, media),
            headers = mapOf("User-Agent" to agent, "Referer" to address.referer), live = true, provider = provider, channel = current)
    }

    private inner class Session(val p: Provider) {
        var address = portalAddress(p.url)
        val agent = p.userAgent.ifBlank { "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3" }
        private var endpoint = address.endpoints.first()
        private var token = ""
        private var notValid = "0"
        private val cookie = "mac=${enc(p.mac)}; stb_lang=en; timezone=${enc(p.timezone.ifBlank { TimeZone.getDefault().id })}"
        suspend fun call(type: String, action: String, args: Map<String, String> = emptyMap()): Any {
            val headers = mutableMapOf("User-Agent" to agent, "X-User-Agent" to "Model: ${p.stbType}; Link: WiFi", "Referer" to address.referer, "Cookie" to cookie)
            if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"
            val raw = try { http.text(query(endpoint, mapOf("type" to type, "action" to action, "JsHttpRequest" to "1-xml") + args), timeout = 15000, headers = headers) }
                catch (e: HttpFailure) { if (e.status in listOf(401, 403)) throw ExpiredPortal(); throw e }
            val json = try { JSONObject(raw.removePrefix("\uFEFF")) } catch (_: JSONException) {
                if (action == "handshake") throw WrongEndpoint()
                throw UserMessage("The portal returned an invalid response. Check the portal address.")
            }
            if (!json.has("js")) {
                if (action == "handshake") throw WrongEndpoint()
                throw UserMessage("The portal returned an invalid response.")
            }
            val result = json.opt("js")
            if (result == null || result == JSONObject.NULL || result == false || result == "Authorization failed.") throw ExpiredPortal()
            return result
        }
        suspend fun authenticate() {
            // Space handshakes out across operations: if the previous handshake
            // was less than the minimum interval ago, wait out the remainder.
            val last = lastHandshakeAtMs
            if (last != null) {
                val wait = MIN_HANDSHAKE_INTERVAL_MS - (nowMs() - last)
                if (wait > 0) delayMs(wait)
            }
            val handshake = discoverHandshake()
            token = handshake?.optString("token").orEmpty()
            if (token.isBlank() || token.any { it == '\r' || it == '\n' }) throw ExpiredPortal()
            notValid = handshake?.optString("not_valid", "0") ?: "0"
            var profile = profile(false)
            if (profile.optInt("status", 0) == 2) {
                if (p.username.isBlank()) throw UserMessage("This portal also requires a username and password. Add them in Advanced device settings.")
                val auth = call("stb", "do_auth", mapOf("login" to p.username, "password" to p.password) + identity().filterKeys { it in listOf("device_id", "device_id2") })
                if (auth == false || (auth is JSONObject && auth.optInt("auth", 1) == 0)) throw ExpiredPortal()
                profile = profile(true)
            }
            if (profile.optInt("status", 0) != 0 || profile.optInt("blocked", 0) != 0)
                throw UserMessage("The portal rejected this account or device. Check your provider's registered MAC and device settings.")
        }
        /**
         * Handshakes the entered address first. Only when it proves not to be a
         * Stalker API (wrong content, 404/405) are discovered addresses tried:
         * providers often hand out a bare domain that redirects to the real
         * portal root. The winning candidate becomes this session's address.
         */
        private suspend fun discoverHandshake(): JSONObject? {
            val candidates = mutableListOf(address)
            var index = 0
            while (index < candidates.size) {
                val candidate = candidates[index]
                val (answered, handshake) = tryHandshake(candidate)
                if (answered) { address = candidate; return handshake }
                index++
                if (index == candidates.size && candidates.size == 1) candidates += discoveryCandidates()
            }
            throw UserMessage("No Stalker API was found at this portal address.")
        }
        /** @return answered=true when an endpoint responded as a Stalker API, even if it rejected the account. */
        private suspend fun tryHandshake(candidate: PortalAddress): Pair<Boolean, JSONObject?> {
            for ((index, candidateEndpoint) in candidate.endpoints.withIndex()) {
                endpoint = candidateEndpoint
                lastHandshakeAtMs = nowMs()
                try { return true to (call("stb", "handshake", mapOf("token" to "", "prehash" to "false")) as? JSONObject) }
                catch (e: WrongEndpoint) { if (index == candidate.endpoints.lastIndex) return false to null }
                catch (e: HttpFailure) { if (e.status !in listOf(404, 405)) throw e; if (index == candidate.endpoints.lastIndex) return false to null }
            }
            return false to null
        }
        private suspend fun discoveryCandidates(): List<PortalAddress> {
            val resolved = try { http.finalUrl(p.url, headers = mapOf("User-Agent" to agent, "Cookie" to cookie, "Referer" to address.referer)) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { null }
            return discoveryAddresses(p.url, resolved).drop(1)
        }
        private fun identity() = mapOf("sn" to p.serialNumber, "device_id" to p.deviceId, "device_id2" to p.deviceId2, "signature" to p.signature,
            "hw_version_2" to p.hwVersion2, "pin" to p.pin).filterValues { it.isNotBlank() }
        private suspend fun profile(second: Boolean): JSONObject {
            val args = mapOf("stb_type" to p.stbType, "hd" to "1", "num_banks" to "1", "image_version" to p.imageVersion.ifBlank { "216" }, "hw_version" to p.hwVersion.ifBlank { "1.7-BD-00" },
                "ver" to p.version.ifBlank { "ImageDescription: 0.2.16-250; PORTAL version: 4.9.9; API Version: JS API version: 328; STB API version: 134; Player Engine version: 0x566" },
                "auth_second_step" to if (second) "1" else "0", "not_valid_token" to notValid) + identity()
            return call("stb", "get_profile", args) as? JSONObject ?: throw ExpiredPortal()
        }
        suspend fun channels(): List<Channel> = catalog().channels
        suspend fun catalog(): StalkerCatalog {
            val genres = try { rows(call("itv", "get_genres")).associate { it.optString("id") to it.optString("title", "Channels") } }
                catch (e: HttpFailure) { if (e.status !in listOf(404, 405, 501)) throw e; emptyMap() }
            var response: Any? = try { call("itv", "get_all_channels") }
                catch (e: HttpFailure) { if (e.status !in listOf(400, 404, 405, 501)) throw e; null }
            val entries = linkedMapOf<String, Channel>()
            fun append(data: Any): Int {
                val before = entries.size
                for (j in rows(data)) {
                    val id = j.optString("id"); val cmd = j.optString("cmd")
                    if (id.isBlank() || cmd.isBlank()) continue
                    val logo = j.optString("logo").takeIf { it.isNotBlank() }?.let { runCatching { URI(address.base).resolve(it).toString() }.getOrNull() }.orEmpty()
                    entries[id] = Channel(id, j.optString("name").ifBlank { "Channel $id" }, "", genres[j.optString("tv_genre_id")] ?: "Channels", logo.takeIf(::isHttp).orEmpty(), command = cmd, number = j.optString("number").take(12))
                    if (entries.size > 30000) throw UserMessage("This portal's channel list is too large. Ask your provider for a smaller channel package.")
                }
                return entries.size - before
            }
            if (response != null) append(response)
            val total = (response as? JSONObject)?.optInt("total_items", entries.size) ?: entries.size
            val pageSize = (response as? JSONObject)?.optInt("max_page_items", 0) ?: 0
            // Some portals count hidden/deleted channels in total_items even in
            // their complete get_all_channels response. A bulk response larger
            // than a normal page is already complete; do not fetch it again.
            if (response != null && (total <= rows(response).size || (pageSize > 0 && rows(response).size > pageSize))) return StalkerCatalog(genres, entries.values.toList())
            entries.clear()
            for (page in 1..3000) {
                response = call("itv", "get_ordered_list", mapOf("genre" to "*", "fav" to "0", "sortby" to "number", "p" to page.toString()))
                val added = append(response)
                val target = (response as? JSONObject)?.optInt("total_items", entries.size) ?: entries.size
                val count = rows(response).size
                val capacity = (response as? JSONObject)?.optInt("max_page_items", count) ?: count
                if (entries.size >= target || count == 0 || (capacity > 0 && count < capacity)) return StalkerCatalog(genres, entries.values.toList())
                if (added == 0) throw UserMessage("The portal stopped returning channel pages. Try refreshing the channels.")
            }
            throw UserMessage("The portal returned too many channel pages.")
        }
        private fun rows(value: Any): List<JSONObject> = when (value) {
            is JSONArray -> value.objects()
            is JSONObject -> if (value.has("data")) rows(value.opt("data") ?: JSONArray()) else value.keys().asSequence().mapNotNull { value.optJSONObject(it) }.toList()
            else -> emptyList()
        }
    }
}
