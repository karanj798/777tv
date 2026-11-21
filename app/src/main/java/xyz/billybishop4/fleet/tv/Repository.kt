package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-demand requests go to installed addons; Stalker remains an independent Live TV source. */
class Repository(addons: List<Addon>, val http: Http = Http(), private val stalkerCatalogCache: StalkerCatalogFileCache? = null) {
    private val client = AddonClient(addons.toList(), http)
    private val cacheLock = Any()
    private var cacheEpoch = 0
    private val catalogCache = linkedMapOf<Triple<String, String, String?>, List<Title>>()
    private val homeCache = linkedMapOf<CatalogFilter, CatalogPage>()
    private val searchCache = linkedMapOf<String, CatalogPage>()
    private val metaCache = linkedMapOf<String, Title>()
    private val metaTokens = linkedMapOf<String, Long>()
    private var metaSequence = 0L
    private val guideCache = linkedMapOf<String, Pair<Long, List<GuideProgram>>>()
    private val stalker = StalkerClient(http)

    fun refresh() {
        synchronized(cacheLock) { cacheEpoch++; catalogCache.clear(); homeCache.clear(); searchCache.clear(); metaCache.clear(); metaTokens.clear(); guideCache.clear() }
        client.refresh()
    }
    private fun <K, V> remember(cache: MutableMap<K, V>, key: K, value: V, capacity: Int, epoch: Int) = synchronized(cacheLock) {
        if (cacheEpoch == epoch) {
            if (cache.size >= capacity && key !in cache) cache.remove(cache.keys.first())
            cache[key] = value
        }
    }
    suspend fun catalog(type: String, id: String = "top", genre: String? = null): List<Title> {
        val key = Triple(type, id, genre)
        val (cached, epoch) = synchronized(cacheLock) { catalogCache[key] to cacheEpoch }
        if (cached != null) return cached
        val result = client.catalog(type, id, genre)
        remember(catalogCache, key, result, 12, epoch)
        return result
    }
    suspend fun search(q: String): List<Title> = client.search(q)
    suspend fun homeCatalogs(filter: CatalogFilter = CatalogFilter()): CatalogPage {
        val key = filter.copy(extras = filter.extras.toMap())
        val (cached, epoch) = synchronized(cacheLock) { homeCache[key] to cacheEpoch }
        if (cached != null) return cached
        val result = client.homeCatalogs(key)
        remember(homeCache, key, result, 8, epoch)
        return result
    }
    suspend fun searchCatalogs(query: String): CatalogPage {
        val key = query.trim()
        val (cached, epoch) = synchronized(cacheLock) { searchCache[key] to cacheEpoch }
        if (cached != null) return cached
        val result = client.searchCatalogs(key)
        remember(searchCache, key, result, 3, epoch)
        return result
    }
    suspend fun detail(title: Title, refresh: Boolean = false): Title {
        val (cached, epoch, token) = synchronized(cacheLock) {
            val value = if (refresh) null else metaCache[title.key]
            val version = if (value == null) (++metaSequence).also {
                metaTokens[title.key] = it
                if (metaTokens.size > 128) metaTokens.remove(metaTokens.keys.first())
            } else 0L
            Triple(value, cacheEpoch, version)
        }
        if (cached != null) return cached
        val result = client.detail(title)
        synchronized(cacheLock) {
            if (cacheEpoch == epoch && metaTokens[title.key] == token) {
                if (metaCache.size >= 32 && title.key !in metaCache) metaCache.remove(metaCache.keys.first())
                metaCache[title.key] = result
            }
        }
        return result
    }
    suspend fun streams(title: Title, episode: Episode?): List<Stream> = client.streams(title, episode)
    fun artwork(url: String): String = url.takeIf(::isHttp).orEmpty()

    /**
     * Stalker channels, served from the persistent catalog cache while it is
     * fresh (see StalkerCatalogCodec.TTL_MS) unless [forceRefresh] is set.
     * A cache miss or a forced refresh fetches the catalog live and rewrites
     * the cache; cache read/write failures never fail the load itself.
     */
    suspend fun channels(provider: Provider, forceRefresh: Boolean = false): List<Channel> = withContext(Dispatchers.Default) {
        val cache = stalkerCatalogCache
        if (cache != null && !forceRefresh) runCatching { cache.read(provider) }.getOrNull()?.let { return@withContext it.channels }
        val catalog = stalker.catalog(provider)
        if (cache != null) runCatching { cache.write(provider, catalog) }
        catalog.channels
    }
    suspend fun liveWatch(p: Provider, c: Channel, refresh: Boolean = false): Watch = withContext(Dispatchers.Default) { stalker.watch(p, c, refresh) }
    suspend fun guide(p: Provider, c: Channel): List<GuideProgram> {
        val key = "${p.hashCode()}/${c.id}"
        val (cached, epoch) = synchronized(cacheLock) { guideCache[key] to cacheEpoch }
        cached?.takeIf { System.currentTimeMillis() - it.first < 300000 }?.let { return it.second }
        val result = withContext(Dispatchers.Default) { stalker.guide(p, c) }
        remember(guideCache, key, System.currentTimeMillis() to result, 24, epoch)
        return result
    }
}
