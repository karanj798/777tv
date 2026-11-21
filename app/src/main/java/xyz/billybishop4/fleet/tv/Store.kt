package xyz.billybishop4.fleet.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("fleet-tv", Context.MODE_PRIVATE)
    private val filesDir = context.filesDir
    private var progressSource:String?=null
    private var progressEntries:List<Progress> = emptyList()
    var readWarning: String? = null; private set
    private fun array(key: String): JSONArray = try { JSONArray(prefs.getString(key, "[]")) }
        catch (_: Exception) { readWarning = "Some saved titles or history could not be read. You can clear them in Settings."; JSONArray() }
    fun preferences(): Preferences = runCatching { Preferences.decode(JSONObject(prefs.getString("preferences", "{}")!!)) }.getOrElse { Preferences() }
    fun preferences(value: Preferences) { prefs.edit().putString("preferences", value.json().toString()).apply() }
    fun favorites(): Set<String> = prefs.getStringSet("channel-favorites", emptySet())!!.toSet()
    fun favorites(value: Set<String>) { prefs.edit().putStringSet("channel-favorites", value.toSet()).apply() }
    fun hiddenCategories(): Map<String, Set<String>> = runCatching {
        val data = JSONObject(prefs.getString("hidden-channel-categories", "{}")!!)
        data.keys().asSequence().mapNotNull { id -> data.optJSONArray(id)?.let { groups ->
            id to (0 until groups.length()).mapNotNull { groups.opt(it) as? String }.toSet()
        } }.toMap()
    }.getOrDefault(emptyMap())
    fun hiddenCategories(value: Map<String, Set<String>>) {
        val data = JSONObject()
        value.forEach { (id, groups) -> if (groups.isNotEmpty()) data.put(id, JSONArray(groups.sorted())) }
        prefs.edit().putString("hidden-channel-categories", data.toString()).apply()
    }
    fun clearHistory() { prefs.edit().remove("progress").apply();progressSource=null;progressEntries=emptyList(); readWarning = null }
    fun saved(): List<Title> = Decode.titles(array("saved"))
    fun saveTitles(titles: List<Title>) { prefs.edit().putString("saved", JSONArray(titles.take(500).map { it.json() }).toString()).apply() }
    fun progress(): List<Progress> {
        val raw=prefs.getString("progress","[]")!!
        if(raw==progressSource)return progressEntries
        val data=try { JSONArray(raw) } catch(_:Exception) { readWarning="Some saved titles or history could not be read. You can clear them in Settings.";JSONArray() }
        val entries=data.objects().mapNotNull { j ->
        runCatching {
            val ep = j.optJSONObject("episode")?.let { Episode(it.getString("id"), it.getInt("season"), it.getInt("number"), it.getString("name"),(it.opt("description") as? String).orEmpty()) }
            Progress(Decode.title(j.getJSONObject("title")), ep, j.getLong("position"), j.getLong("duration"), j.getLong("updated"))
        }.getOrNull()
        }
        progressSource=raw;progressEntries=entries
        return entries
    }
    fun progress(p: Progress) {
        val entries = (listOf(p) + progress().filter { it.key != p.key }).take(100).map {
            // History never retains a series' full episode catalog; its disk
            // format also omits episodes. Cache exactly that lightweight form.
            if(it.positionMs<0 || it.durationMs<0 || it.title.episodes.isNotEmpty())
                it.copy(title=it.title.copy(episodes=emptyList()),positionMs=it.positionMs.coerceAtLeast(0),durationMs=it.durationMs.coerceAtLeast(0)) else it
        }
        val raw=JSONArray(entries.map {
            JSONObject().put("title", it.title.json()).put("position", it.positionMs.coerceAtLeast(0)).put("duration", it.durationMs.coerceAtLeast(0)).put("updated", it.updated)
                .put("episode", it.episode?.let { e -> JSONObject().put("id", e.id).put("season", e.season).put("number", e.number).put("name", e.name).put("description",e.description) })
        }).toString()
        prefs.edit().putString("progress",raw).apply();progressSource=raw;progressEntries=entries
    }
    private fun key(alias:String="fleet-tv-providers"): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun providers(): Result<List<Provider>> = runCatching {
        val blob = prefs.getString("providers-encrypted", null) ?: return@runCatching emptyList()
        val parts = blob.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        JSONArray(String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)).objects().map {
            Provider(it.getString("id"), it.getString("name"), it.getString("url"), it.getString("kind"), it.optString("username"), it.optString("password"),
                it.optString("userAgent"), it.optString("mac"), it.optString("serialNumber"), it.optString("deviceId"), it.optString("deviceId2"), it.optString("signature"),
                it.optString("stbType", "MAG250"), it.optString("timezone"), it.optString("hwVersion"), it.optString("hwVersion2"), it.optString("pin"), it.optString("version"), it.optString("imageVersion"))
        }
    }
    fun providers(providers: List<Provider>) {
        val raw = JSONArray(providers.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("kind", it.kind).put("username", it.username).put("password", it.password)
            .put("userAgent", it.userAgent).put("mac", it.mac).put("serialNumber", it.serialNumber).put("deviceId", it.deviceId).put("deviceId2", it.deviceId2)
            .put("signature", it.signature).put("stbType", it.stbType).put("timezone", it.timezone).put("hwVersion", it.hwVersion)
            .put("hwVersion2", it.hwVersion2).put("pin", it.pin).put("version", it.version).put("imageVersion", it.imageVersion) }).toString()
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val blob = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(raw.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString("providers-encrypted", blob).apply()
    }
    fun resetProviders() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("fleet-tv-providers") }
        prefs.edit().remove("providers-encrypted").remove("hidden-channel-categories").apply()
        stalkerCatalogCache().clearAll()
    }
    /** Catalog cache rooted in app-private internal storage, never in SharedPreferences. */
    fun stalkerCatalogCache(): StalkerCatalogFileCache = StalkerCatalogFileCache(java.io.File(filesDir, "stalker-catalog"))
    fun deleteStalkerCatalog(provider: Provider) { stalkerCatalogCache().delete(provider) }
    fun addons():Result<List<Addon>> = runCatching {
        val blob=prefs.getString("addons-encrypted",null)?:return@runCatching defaultAddons()
        val parts=blob.split(':',limit=2);require(parts.size==2)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key("fleet-tv-addons"),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        cipher.updateAAD("fleet-tv-addons:v1".toByteArray(Charsets.UTF_8))
        val data=JSONArray(String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8))
        (0 until data.length()).map { Addon.fromJson(data.getJSONObject(it)) }
    }
    fun addons(value:List<Addon>) {
        val raw=JSONArray(value.map{it.json()}).toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key("fleet-tv-addons"))
        cipher.updateAAD("fleet-tv-addons:v1".toByteArray(Charsets.UTF_8))
        val blob=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(raw),Base64.NO_WRAP)
        prefs.edit().putString("addons-encrypted",blob).apply()
    }
    fun resetAddons() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("fleet-tv-addons") }
        prefs.edit().remove("addons-encrypted").apply()
    }
}
