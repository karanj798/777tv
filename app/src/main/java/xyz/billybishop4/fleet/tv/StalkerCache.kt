package xyz.billybishop4.fleet.tv

import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** A fetched Stalker catalog: the genre id-to-title map plus the channels built from it. */
data class StalkerCatalog(val genres: Map<String, String>, val channels: List<Channel>)

/**
 * Pure encode/decode, keying and freshness logic for the on-disk Stalker
 * catalog cache. No Android types and no I/O, so it is unit-testable on the JVM.
 *
 * The cache key is a one-way SHA-256 hash of the portal URL and MAC: a profile
 * edited to a different portal or MAC can never be served the old catalog, and
 * neither value appears in the file name or the file contents. Bearer tokens
 * are never part of a catalog and are never written here.
 */
object StalkerCatalogCodec {
    const val TTL_MS: Long = 3L * 24 * 60 * 60 * 1000

    fun keyFor(portalUrl: String, mac: String): String {
        val normalizedMac = mac.trim().replace('-', ':').uppercase(Locale.ROOT)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((portalUrl.trim() + "\n" + normalizedMac).toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val chars = CharArray(digest.size * 2)
        digest.forEachIndexed { i, b -> val v = b.toInt() and 255; chars[i * 2] = hex[v ushr 4]; chars[i * 2 + 1] = hex[v and 15] }
        return String(chars)
    }

    fun fileNameFor(provider: Provider): String {
        val safeId = provider.id.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
        return "catalog-$safeId-${keyFor(provider.url, provider.mac)}.json"
    }

    fun isFresh(savedAtMs: Long, nowMs: Long): Boolean =
        savedAtMs > 0 && nowMs >= savedAtMs && nowMs - savedAtMs < TTL_MS

    fun encode(catalog: StalkerCatalog, savedAtMs: Long): String {
        val genres = JSONObject()
        catalog.genres.forEach { (id, title) -> genres.put(id, title) }
        val channels = JSONArray(catalog.channels.map { c ->
            JSONObject().put("id", c.id).put("name", c.name).put("group", c.group)
                .put("logo", c.logo).put("command", c.command).put("number", c.number)
        })
        return JSONObject().put("savedAt", savedAtMs).put("genres", genres).put("channels", channels).toString()
    }

    /** @return the saved-at time paired with the catalog, or null when the text is not a usable cache entry. */
    fun decode(text: String): Pair<Long, StalkerCatalog>? = runCatching {
        val root = JSONObject(text)
        val savedAt = root.getLong("savedAt")
        val genreObject = root.optJSONObject("genres") ?: JSONObject()
        val genres = genreObject.keys().asSequence().associate { it to genreObject.optString(it) }
        val channels = root.getJSONArray("channels").objects().map { j ->
            Channel(j.getString("id"), j.optString("name"), "", j.optString("group", "Channels"),
                j.optString("logo"), command = j.optString("command"), number = j.optString("number"))
        }
        if (channels.isEmpty()) null else savedAt to StalkerCatalog(genres, channels)
    }.getOrNull()
}

/**
 * One cache file per provider in app-private internal storage (a directory
 * under filesDir supplied by Store). Writes are atomic: a temp file in the
 * same directory is renamed over the target. A missing, stale or corrupt
 * entry reads as null, so callers simply refetch.
 */
class StalkerCatalogFileCache(private val dir: File, private val nowMs: () -> Long = System::currentTimeMillis) {
    fun fileFor(provider: Provider): File = File(dir, StalkerCatalogCodec.fileNameFor(provider))

    fun read(provider: Provider): StalkerCatalog? {
        val file = fileFor(provider)
        val text = runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull() ?: return null
        val (savedAt, catalog) = StalkerCatalogCodec.decode(text) ?: return null
        return catalog.takeIf { StalkerCatalogCodec.isFresh(savedAt, nowMs()) }
    }

    fun write(provider: Provider, catalog: StalkerCatalog) {
        if (catalog.channels.isEmpty()) return
        dir.mkdirs()
        val target = fileFor(provider)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(StalkerCatalogCodec.encode(catalog, nowMs()), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) { temp.delete(); throw java.io.IOException("Could not store the channel cache.") }
        }
    }

    fun delete(provider: Provider) {
        runCatching { fileFor(provider).delete() }
        runCatching { File(dir, fileFor(provider).name + ".tmp").delete() }
    }

    fun clearAll() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }
}
