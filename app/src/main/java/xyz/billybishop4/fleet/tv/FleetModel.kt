package xyz.billybishop4.fleet.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Section(val label: String) { LIVE("Live TV"), SEARCH("On-Demand") }
internal fun streamLinksFresh(loadedAtMs:Long?,nowMs:Long,ttlMs:Long=300_000L)=loadedAtMs!=null && loadedAtMs>=0 && nowMs>=loadedAtMs && nowMs-loadedAtMs<=ttlMs
sealed interface Route {
    data class Browse(val section: Section) : Route
    data class Detail(val title: Title, val episode:Episode?=null) : Route
    data class Sources(val title: Title, val episode: Episode? = null) : Route
    data object AddProvider : Route
    data class EditProvider(val provider: Provider) : Route
    data object Settings : Route
    data object Addons : Route
    data class Channels(val provider: Provider) : Route
    data class Player(val watch: Watch) : Route
}

class FleetModel(application: Application) : AndroidViewModel(application) {
    val changes = androidx.lifecycle.MutableLiveData<Unit>()
    private fun <T> state(initial: T) = kotlin.properties.Delegates.observable(initial) { _, old, value ->
        if (if (old is Collection<*> && value is Collection<*>) old !== value else old != value) changes.postValue(Unit)
    }
    val store = Store(application)
    var preferences by state(store.preferences()); private set
    private val addonLoad=store.addons()
    var addons by state(addonLoad.getOrDefault(emptyList())); private set
    var addonStorageError by state(addonLoad.isFailure); private set
    var addonStatus by state<String?>(null); private set
    private var addonDraftValue by state("")
    val addonDraft:String get()=addonDraftValue
    private var fixtureAddons:List<Addon>?=null
    var repository by state(Repository(addons, stalkerCatalogCache = store.stalkerCatalogCache())); private set
    private val initialSection = if (preferences.startSection == "Live TV") Section.LIVE else Section.SEARCH
    var route by state<Route>(Route.Browse(initialSection)); private set
    var section by state(initialSection); private set
    var busy by state(false); private set
    var error by state<String?>(null); private set
    var shelves by state<List<Shelf>>(emptyList()); private set
    var results by state<List<Title>>(emptyList()); private set
    var catalogDefinitions by state<List<CatalogDefinition>>(emptyList()); private set
    var catalogFilter by state(CatalogFilter()); private set
    var searchShelves by state<List<Shelf>>(emptyList()); private set
    var detail by state<Title?>(null); private set
    var selectedEpisode by state<Episode?>(null); private set
    var streams by state<List<Stream>>(emptyList()); private set
    var streamsBusy by state(false); private set
    var streamsError by state<String?>(null); private set
    var channels by state<List<Channel>>(emptyList()); private set
    private val providerLoad = store.providers()
    var providerStorageError by state(providerLoad.isFailure); private set
    var providers by state(providerLoad.getOrDefault(emptyList())); private set
    var connectionStatus by state<String?>(null); private set
    var providerDraft by state<Provider?>(null); private set
    private var providerDraftIsNew = true
    var favorites by state(store.favorites()); private set
    var hiddenCategories by state(store.hiddenCategories().filterKeys { id -> providers.any { it.id == id } }); private set
    var channelGroup by state("All channels")
    var channelQuery by state("")
    var onlyFavoriteChannels by state(false)
    private var browsingProvider = ""
    private var channelProvider: Provider? = null
    private var forceChannelRefresh = false
    var saved by state(store.saved()); private set
    var progress by state(store.progress()); private set
    var searchText by state(""); private set
    private data class Location(val route:Route,val section:Section)
    private val history = mutableListOf<Location>()
    private data class SearchData(val page:CatalogPage,val titles:List<Title>)
    private var lastSearch:Pair<String,SearchData>?=null
    private var homeWarning:String?=null
    private var job: Job? = null
    private var generation = 0
    private var streamJob: Job? = null
    private var streamGeneration = 0
    private var detailStreamKey:String?=null
    private data class SeriesPage(var detail:Title?=null,var episode:Episode?=null,var explicit:Boolean=false,var streams:List<Stream>?=null,var streamsAt:Long?=null,
        var metadataAt:Long?=null,var refreshMetadata:Boolean=false)
    private val seriesPages=linkedMapOf<String,SeriesPage>()

    val defaultProfile: Provider? get() = providers.firstOrNull { it.kind == "stalker" && it.id == preferences.defaultProfileId }
        ?: providers.firstOrNull { it.kind == "stalker" }
    init { if (initialSection == Section.LIVE) enterLive(); load() }
    private fun enterLive() {
        val profile = defaultProfile
        if (profile == null) route = Route.Browse(Section.LIVE)
        else route = Route.Channels(profile)
    }
    fun top(section: Section) {
        val root=history.isEmpty() && when(section) {
            Section.SEARCH -> route==Route.Browse(Section.SEARCH)
            Section.LIVE -> route is Route.Channels || (route==Route.Browse(Section.LIVE) && defaultProfile==null)
        }
        if(this.section==section && root)return
        this.section = section; history.clear(); route = Route.Browse(section)
        if (section == Section.LIVE) enterLive()
        load()
    }
    fun showProfiles() { section = Section.LIVE; history.clear(); route = Route.Browse(Section.LIVE); load() }
    fun manageProfiles() { open(Route.Browse(Section.LIVE));section=Section.LIVE }
    fun makeDefaultProfile(id: String) {
        if (providers.none { it.id == id && it.kind == "stalker" }) return
        updatePreferences(preferences.copy(defaultProfileId = id))
    }
    fun open(next: Route) {
        val target=if(next is Route.Sources && next.title.type=="series")Route.Detail(next.title,next.episode) else next
        if(target is Route.Detail && target.title.type=="series" && (route as? Route.Detail)?.title?.key==target.title.key) {
            target.episode?.let(::selectEpisode)
            return
        }
        if (target == route) return
        if(target is Route.Detail && target.title.type=="series")beginSeriesPage(target)
        if(history.size>=32)history.removeAt(0)
        history += Location(route,section)
        route = target
        if(target is Route.Channels)section=Section.LIVE
        load()
    }
    fun back(): Boolean {
        while(history.isNotEmpty()) {
            val previous=history.removeAt(history.lastIndex)
            if(previous.route==route && previous.section==section)continue
            route=previous.route;section=previous.section;load();return true
        }
        if (route == Route.Browse(Section.SEARCH) && searchText.isNotEmpty()) { search(""); return true }
        return false
    }
    fun retry() {
        (route as? Route.Detail)?.takeIf { it.title.type=="series" }?.let { page ->
            seriesPages[page.title.key]?.apply { refreshMetadata=true }
        }
        // On the Channels route, retry is the "Refresh channels" action: it is
        // the cache-reload path and must bypass the persistent catalog cache.
        if (route is Route.Channels) { channelProvider = null; forceChannelRefresh = true }
        if (route == Route.Browse(Section.SEARCH)) {
            lastSearch=null;repository.refresh()
            if(searchText.trim().length < 2) { shelves = emptyList();homeWarning=null }
        }
        load()
    }
    fun failure(message: String?) { error = message }
    private fun loadMessage(e:Exception):String = when(e) {
        is UserMessage -> e.message.orEmpty()
        is SocketTimeoutException -> "The server took too long. Try again."
        is HttpFailure -> if(e.status==401 || e.status==403) "The server refused this request. Check its address and access settings." else "The service is unavailable. Try again."
        else -> "Couldn't load this page. Check your connection and add-on settings."
    }
    private fun request(block: suspend () -> Unit) {
        job?.cancel(); val g = ++generation; busy = true; error = null
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (g == generation) error = loadMessage(e)
            } finally { if (g == generation) busy = false }
        }
    }
    private fun load() {
        cancelStreams()
        if((route as? Route.Detail)?.title?.type!="series")selectedEpisode=null
        progress = store.progress()
        connectionStatus = null
        when (val page = route) {
            is Route.Browse -> when (page.section) {
                Section.SEARCH -> search(searchText)
                Section.LIVE -> cancelLoad()
            }
            is Route.Detail -> {
                if(page.title.type=="series")loadSeriesDetail(page)
                else { detail = page.title; request { detail = repository.detail(page.title) };loadMovieStreams(page.title) }
            }
            is Route.Sources -> loadSourceStreams(page.title,page.episode)
            is Route.Channels -> {
                if (browsingProvider != page.provider.id) { browsingProvider = page.provider.id; channelGroup = "All channels"; channelQuery = ""; onlyFavoriteChannels = false }
                if (channelProvider == page.provider && channels.isNotEmpty()) { forceChannelRefresh = false; cancelLoad() }
                else { val force = forceChannelRefresh; forceChannelRefresh = false
                    channels = emptyList(); request { channels = repository.channels(page.provider, force); channelProvider = page.provider } }
            }
            Route.AddProvider -> {
                cancelLoad()
                if (providerDraft == null || !providerDraftIsNew) { providerDraft = Provider(UUID.randomUUID().toString(), "", ""); providerDraftIsNew = true }
            }
            is Route.EditProvider -> {
                cancelLoad()
                if (providerDraft?.id != page.provider.id || providerDraftIsNew) { providerDraft = page.provider.copy(kind = "stalker"); providerDraftIsNew = false }
            }
            else -> cancelLoad()
        }
    }
    private fun cancelStreams() {
        streamJob?.cancel();streamJob=null;streamGeneration++
        detailStreamKey=null
        streams=emptyList();streamsBusy=false;streamsError=null
    }
    private fun loadMovieStreams(title:Title) {
        loadDetailStreams(title,null,null)
    }
    private fun loadDetailStreams(title:Title,episode:Episode?,page:SeriesPage?) {
        streamJob?.cancel();val g=++streamGeneration
        detailStreamKey=title.key+"/"+episode?.id.orEmpty()
        val client=repository
        page?.apply { streams=null;streamsAt=null }
        streams=emptyList();streamsBusy=true;streamsError=null
        streamJob=viewModelScope.launch {
            try {
                val found=client.streams(title,episode)
                if(g==streamGeneration && (route as? Route.Detail)?.title?.key==title.key && (episode==null || selectedEpisode?.id==episode.id)) {
                    streams=found;page?.apply { streams=found;streamsAt=android.os.SystemClock.elapsedRealtime() }
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(g==streamGeneration)streamsError=loadMessage(e)}
            finally{if(g==streamGeneration)streamsBusy=false}
        }
    }
    private fun loadSourceStreams(title:Title,episode:Episode?) {
        val g=++streamGeneration
        val client=repository
        streams=emptyList();streamsBusy=true;streamsError=null
        request {
            try { val found=client.streams(title,episode);if(g==streamGeneration)streams=found }
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(g==streamGeneration)streamsError=loadMessage(e);throw e}
            finally{if(g==streamGeneration)streamsBusy=false}
        }
    }
    fun retryStreams() {
        when(val page=route) {
            is Route.Detail -> if(page.title.type!="series")loadMovieStreams(page.title)
                else selectedEpisode?.let { loadDetailStreams(detail?:page.title,it,seriesPages[page.title.key]) }
            is Route.Sources -> loadSourceStreams(page.title,page.episode)
            else -> Unit
        }
    }
    private fun initialEpisode(title:Title?):Episode? {
        val episodes=title?.episodes.orEmpty()
        return (if(preferences.resume)store.progress().asSequence().filter { it.title.key==title?.key && it.episode!=null && it.positionMs>0 && !it.finished }
            .sortedByDescending { it.updated }.mapNotNull { progress -> episodes.firstOrNull { it.id==progress.episode?.id } }.firstOrNull() else null)
            ?:episodes.firstOrNull { it.season>0 } ?:episodes.firstOrNull()
    }
    private fun beginSeriesPage(route:Route.Detail) {
        val previous=seriesPages.remove(route.title.key)
        val metadata=previous?.detail?:route.title.takeIf { it.episodes.isNotEmpty() }
        val episode=route.episode?:initialEpisode(metadata)
        val cached=previous?.streams?.takeIf { previous.episode?.id==episode?.id && streamLinksFresh(previous.streamsAt,android.os.SystemClock.elapsedRealtime()) }
        seriesPages[route.title.key]=SeriesPage(metadata,episode,route.episode!=null,
            cached,previous?.streamsAt?.takeIf { cached!=null },previous?.metadataAt?:android.os.SystemClock.elapsedRealtime().takeIf { metadata!=null },previous?.refreshMetadata?:false)
        while(seriesPages.size>8)seriesPages.remove(seriesPages.keys.first())
    }
    private fun loadSeriesDetail(route:Route.Detail) {
        if(route.title.key !in seriesPages)beginSeriesPage(route)
        val page=seriesPages.getValue(route.title.key)
        detail=page.detail?:detail?.takeIf { it.key==route.title.key }?:route.title;selectedEpisode=page.episode
        val refresh=page.refreshMetadata || page.detail!=null && !streamLinksFresh(page.metadataAt,android.os.SystemClock.elapsedRealtime(),900_000L)
        if(page.detail!=null && !refresh) { cancelLoad();applySeriesMetadata(route.title,page,page.detail!!);return }
        page.episode?.let { restoreSeriesStreams(route.title,page) }
        request {
            val loaded=repository.detail(route.title,refresh=refresh)
            if((this@FleetModel.route as? Route.Detail)?.title?.key==route.title.key && seriesPages[route.title.key]===page) {
                page.detail=loaded;page.metadataAt=android.os.SystemClock.elapsedRealtime();page.refreshMetadata=false
                detail=loaded;applySeriesMetadata(route.title,page,loaded)
            }
        }
    }
    private fun applySeriesMetadata(title:Title,page:SeriesPage,metadata:Title) {
        val resolved=metadata.episodes.firstOrNull { it.id==page.episode?.id }
            ?:page.episode?.takeIf { page.explicit && metadata.episodes.isEmpty() }
            ?:initialEpisode(metadata)
        if(resolved?.id!=page.episode?.id) { page.streams=null;page.streamsAt=null }
        page.episode=resolved;selectedEpisode=resolved
        if(resolved==null)cancelStreams() else restoreSeriesStreams(title,page)
    }
    private fun restoreSeriesStreams(title:Title,page:SeriesPage) {
        val episode=page.episode?:return
        val key=title.key+"/"+episode.id
        val cached=page.streams?.takeIf { streamLinksFresh(page.streamsAt,android.os.SystemClock.elapsedRealtime()) }
        if(cached!=null) {
            if(detailStreamKey!=key || streams!==cached) { cancelStreams();detailStreamKey=key;streams=cached }
        } else if(detailStreamKey!=key || streamJob?.isActive!=true)loadDetailStreams(page.detail?:title,episode,page)
    }
    fun selectEpisode(episode:Episode) {
        val route=route as? Route.Detail?:return
        if(route.title.type!="series")return
        val page=seriesPages[route.title.key]?:return
        val valid=(page.detail?:detail?.takeIf { it.key==route.title.key })?.episodes?.firstOrNull { it.id==episode.id }?:return
        if(page.episode?.id==valid.id)return
        page.episode=valid;page.explicit=true;page.streams=null;page.streamsAt=null;selectedEpisode=valid
        loadDetailStreams(page.detail?:route.title,valid,page)
    }
    fun selectSeason(season:Int) {
        val route=route as? Route.Detail?:return
        val first=(seriesPages[route.title.key]?.detail?:detail?.takeIf { it.key==route.title.key })?.episodes?.firstOrNull { it.season==season }?:return
        if(selectedEpisode?.season!=season)selectEpisode(first)
    }
    private fun invalidateSeriesPages() { seriesPages.values.forEach { it.detail=null;it.metadataAt=null;it.refreshMetadata=false;it.streams=null;it.streamsAt=null } }
    private fun cancelLoad() { job?.cancel(); generation++; busy = false; error = null }
    private fun searchHome() {
        if (shelves.isNotEmpty()) { cancelLoad();error=homeWarning;return }
        request {
            val loaded=repository.homeCatalogs(catalogFilter)
            catalogDefinitions=loaded.catalogs;shelves=loaded.shelves
            homeWarning=if(loaded.partial)"Some catalogs couldn't load. Try again to refresh." else null
            error=homeWarning
        }
    }
    fun selectCatalog(filter:CatalogFilter) {
        val next=filter.copy(extras=filter.extras.toMap())
        if(next==catalogFilter)return
        catalogFilter=next;shelves=emptyList();homeWarning=null
        if(route==Route.Browse(Section.SEARCH) && searchText.trim().length<2)searchHome()
    }
    fun search(text: String) {
        searchText = text
        if (text.trim().length < 2) { results = emptyList();searchShelves=emptyList();searchHome(); return }
        lastSearch?.takeIf { it.first==text.trim() }?.let { cached ->
            cancelLoad();catalogDefinitions=cached.second.page.catalogs;searchShelves=cached.second.page.shelves;results=cached.second.titles
            error=if(cached.second.page.partial)"Some search catalogs couldn't load. Try again." else null
            return
        }
        results = emptyList();searchShelves=emptyList()
        request {
            delay(350);val found=repository.searchCatalogs(text.trim())
            val titles=found.shelves.flatMap { it.titles }.distinctBy { it.key }
            lastSearch=text.trim() to SearchData(found,titles);catalogDefinitions=found.catalogs;searchShelves=found.shelves;results=titles
            if(found.partial)error="Some search catalogs couldn't load. Try again."
        }
    }
    fun toggle(title: Title) {
        saved = if (saved.any { it.key == title.key }) saved.filter { it.key != title.key } else (listOf(title.copy(episodes=emptyList())) + saved).take(500)
        store.saveTitles(saved)
    }
    fun resume(title: Title, episode: Episode?): Long = if (!preferences.resume) 0L else store.progress().find { it.title.key == title.key && it.episode?.id == episode?.id && !it.finished }?.positionMs ?: 0L
    fun saveProgress(watch: Watch, positionMs: Long, durationMs: Long) {
        if (!watch.live && positionMs > 0) {
            store.progress(Progress(watch.title, watch.episode, positionMs, durationMs.coerceAtLeast(0), System.currentTimeMillis()))
            progress = store.progress()
        }
    }
    @androidx.annotation.VisibleForTesting
    internal fun server(value:String):Boolean = try {
        val base=baseUrl(value)
        require(java.net.URI(base).host in setOf("10.0.2.2","127.0.0.1","localhost"))
        cancelLoad()
        fixtureAddons=defaultAddons().map { addon ->
            val kind=if(addon.id=="com.linvo.cinemeta")"cinemeta" else "gdrive"
            val document=org.json.JSONObject(addon.manifest).apply { remove("idPrefixes");put("name",if(kind=="cinemeta")"Fixture Cinemeta" else "Fixture GDrive") }
            addon.copy(manifestUrl="$base/addons/$kind/manifest.json",
                manifest=document.toString())
        }
        store.addons(fixtureAddons!!);addons=fixtureAddons!!;addonStorageError=false;reloadAddons();true
    } catch (_:Exception) { error="Enter a local fixture address.";false }
    fun addonEdited() { addonStatus=null;error=null }
    fun setAddonDraft(value:String) { addonDraftValue=value;addonEdited() }
    private fun reloadAddons() {
        invalidateSeriesPages()
        repository=Repository(addons, stalkerCatalogCache = store.stalkerCatalogCache());shelves=emptyList();results=emptyList();searchShelves=emptyList();catalogDefinitions=emptyList();catalogFilter=CatalogFilter();lastSearch=null;homeWarning=null;error=null
        if(route==Route.Browse(Section.SEARCH) || route is Route.Detail || route is Route.Sources)load()
    }
    private fun saveAddons(value:List<Addon>):Boolean = try {
        if(addonStorageError)throw UserMessage("Saved add-ons couldn't be read. Reset add-ons to restore defaults.")
        store.addons(value);addons=value;reloadAddons();true
    } catch(e:Exception) { error=(e as? UserMessage)?.message?:"Couldn't save add-ons. Try again.";false }
    fun installAddon(value:String) {
        if(addonStorageError) { error="Saved add-ons couldn't be read. Reset add-ons to restore defaults.";return }
        val normalized=try { addonManifestUrl(value) } catch(_:Exception) { error="Enter an HTTP, HTTPS or Stremio manifest URL.";return }
        if(addons.size>=32 && addons.none{it.manifestUrl==normalized}) { error="Remove an add-on before installing more.";return }
        val submittedDraft=addonDraftValue
        request {
            val installed=fetchAddon(normalized,repository.http)
            val previous=addons.firstOrNull{it.manifestUrl==installed.manifestUrl}
            val next=if(previous==null)addons+installed else addons.map{if(it.manifestUrl==installed.manifestUrl)installed.copy(enabled=it.enabled) else it}
            if(saveAddons(next)) { if(addonDraftValue==submittedDraft)addonDraftValue="";addonStatus="Add-on installed." }
        }
    }
    fun setAddonEnabled(url:String,enabled:Boolean) {
        if(addons.none{it.manifestUrl==url})return
        if(saveAddons(addons.map{if(it.manifestUrl==url)it.copy(enabled=enabled) else it}))addonStatus=if(enabled)"Add-on enabled." else "Add-on disabled."
    }
    fun removeAddon(url:String) {
        if(addons.none{it.manifestUrl==url})return
        if(saveAddons(addons.filterNot{it.manifestUrl==url}))addonStatus="Add-on removed."
    }
    fun refreshAddon(url:String) {
        val existing=addons.firstOrNull{it.manifestUrl==url}?:return
        request {
            val refreshed=fetchAddon(url,repository.http).copy(enabled=existing.enabled)
            if(saveAddons(addons.map{if(it.manifestUrl==url)refreshed else it}))addonStatus="Add-on refreshed."
        }
    }
    fun resetAddons() {
        cancelLoad()
        try {
            store.resetAddons();addonStorageError=false;addons=fixtureAddons?:defaultAddons();store.addons(addons)
            addonDraftValue="";reloadAddons();addonStatus="Default add-ons restored."
        } catch(_:Exception) { addonStorageError=true;error="Couldn't reset add-ons. Try again." }
    }
    fun validateProvider(value: Provider): Provider = validatedPortal(value)
    fun saveProvider(value: Provider) {
        if (providerStorageError) { error = "Saved providers could not be decrypted. Reset provider storage in Settings before adding a provider."; return }
        try {
            val provider = validateProvider(value)
            val openFirstProfile=route==Route.AddProvider && providers.isEmpty()
            val updated = if (providers.any { it.id == provider.id }) providers.map { if (it.id == provider.id) provider else it } else providers + provider
            store.providers(updated); providers = updated
            for(i in history.indices)history[i]=history[i].copy(route=when(val old=history[i].route) {
                is Route.Channels -> if(old.provider.id==provider.id)Route.Channels(provider) else old
                is Route.EditProvider -> if(old.provider.id==provider.id)Route.EditProvider(provider) else old
                else -> old
            })
            if (preferences.defaultProfileId.isBlank() || providers.none { it.id == preferences.defaultProfileId && it.kind == "stalker" }) makeDefaultProfile(provider.id)
            providerDraft = null; back()
            if(openFirstProfile)open(Route.Channels(provider))
        }
        catch (e: IllegalArgumentException) { error = e.message }
        catch (e: UserMessage) { error = e.message }
        catch (_: Exception) { error = "Couldn't save this provider on this device. Try again." }
    }
    fun testProvider(value: Provider) {
        val valid = try { validateProvider(value) } catch (e: Exception) { error = e.message ?: "Check the provider settings."; return }
        request { val list = repository.channels(valid); connectionStatus = "Connected · ${list.size} channels" }
    }
    fun providerEdited() { if (connectionStatus != null || busy) cancelLoad(); connectionStatus = null;error=null }
    fun editProviderDraft(value: Provider) {
        val previous = providerDraft
        if (value == previous) return
        if (previous?.copy(name = "") != value.copy(name = "")) providerEdited()
        providerDraft = value
    }
    fun discardProviderDraft() { providerDraft = null; back() }
    fun updatePreferences(value: Preferences) { preferences = Preferences.decode(value.json()); store.preferences(preferences) }
    fun toggleFavorite(provider: Provider, channel: Channel) {
        val key = favoriteKey(provider.id, channel)
        favorites = if (key in favorites) favorites - key else favorites + key
        store.favorites(favorites)
    }
    fun setCategoryVisible(providerId: String, category: String, visible: Boolean) {
        if (providers.none { it.id == providerId }) return
        val hidden = hiddenCategories[providerId].orEmpty()
        val updated = if (visible) hidden - category else hidden + category
        if (updated == hidden) return
        hiddenCategories = if (updated.isEmpty()) hiddenCategories - providerId else hiddenCategories + (providerId to updated)
        store.hiddenCategories(hiddenCategories)
        if (!visible && (route as? Route.Channels)?.provider?.id == providerId && channelGroup == category) channelGroup = "All channels"
    }
    fun showAllCategories(providerId: String) {
        if (providerId !in hiddenCategories) return
        hiddenCategories = hiddenCategories - providerId
        store.hiddenCategories(hiddenCategories)
    }
    fun clearHistory() { store.clearHistory(); progress = emptyList() }
    fun resetProviders() {
        try {
            store.resetProviders(); providers = emptyList(); providerStorageError = false; favorites = emptySet(); store.favorites(emptySet()); hiddenCategories = emptyMap(); providerDraft = null
            updatePreferences(preferences.copy(defaultProfileId = "")); channelProvider = null; channels = emptyList()
            for(i in history.indices)if(history[i].route is Route.Channels || history[i].route is Route.EditProvider || (history[i].route as? Route.Player)?.watch?.live==true)
                history[i]=Location(Route.Browse(Section.LIVE),Section.LIVE)
            if(route is Route.Channels || route is Route.EditProvider || (route as? Route.Player)?.watch?.live==true) {
                route=Route.Browse(Section.LIVE);section=Section.LIVE;cancelLoad()
            }
        } catch (_: Exception) { error = "Couldn't reset provider storage. Try again after unlocking the device." }
    }
    fun refreshContent() { repository.refresh();invalidateSeriesPages(); shelves = emptyList(); results = emptyList();searchShelves=emptyList();catalogDefinitions=emptyList();lastSearch=null;homeWarning=null; if (route == Route.Settings) { connectionStatus = "Content cache cleared." } else load() }
    fun trimMemory() {
        repository.refresh()
        val active=(route as? Route.Detail)?.title?.key?:(route as? Route.Player)?.watch?.title?.key
        seriesPages.keys.filter { it!=active }.forEach(seriesPages::remove)
        if(route!=Route.Browse(Section.SEARCH)) { shelves=emptyList();results=emptyList();searchShelves=emptyList();catalogDefinitions=emptyList();lastSearch=null;homeWarning=null }
        if(route !is Route.Channels && (route as? Route.Player)?.watch?.live!=true) { channels=emptyList();channelProvider=null }
    }
    fun removeProvider(id: String) {
        try {
            providers.firstOrNull { it.id == id }?.let { store.deleteStalkerCatalog(it) }
            store.providers(providers.filter { it.id != id }); providers = providers.filter { it.id != id }
            if (preferences.defaultProfileId == id) updatePreferences(preferences.copy(defaultProfileId = providers.firstOrNull { it.kind == "stalker" }?.id.orEmpty()))
            favorites = favorites.filterNot { it.startsWith("$id/") }.toSet(); store.favorites(favorites)
            hiddenCategories = hiddenCategories - id; store.hiddenCategories(hiddenCategories)
            if(providerDraft?.id==id)providerDraft=null
            if (channelProvider?.id == id) { channelProvider = null; channels = emptyList(); browsingProvider = "" }
            for(i in history.indices) {
                val old=history[i].route
                if((old is Route.Channels && old.provider.id==id) || (old is Route.EditProvider && old.provider.id==id) || (old is Route.Player && old.watch.provider?.id==id))
                    history[i]=Location(Route.Browse(Section.LIVE),Section.LIVE)
            }
            if ((route as? Route.Channels)?.provider?.id == id || (route as? Route.EditProvider)?.provider?.id==id || (route as? Route.Player)?.watch?.provider?.id == id) {
                route=Route.Browse(Section.LIVE);section=Section.LIVE;load()
            }
        }
        catch (_: Exception) { error = "Couldn't remove the provider. Try again." }
    }
}
