package xyz.billybishop4.fleet.tv

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

internal class SettingsScreen(private val a: MainActivity) : NativeScreen {
    private val m = a.model
    override val view = a.column()
    private val page = a.column().apply { pad(3) }
    private val scroll = ScrollView(a).apply { tag = "settings-list"; isFillViewport = true; addView(page) }
    private val status = a.text("", 13f, MUTED).apply {
        tag = "settings-status"; visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val bindings = ArrayList<() -> Unit>()
    private val categories = linkedMapOf<String, Button>()
    private val positions = HashMap<String, Int>()
    private var localStatus = ""
    private var selected = "Playback"
    private var closed = false
    private var licenseDialog: android.app.AlertDialog? = null
    private var licenseJob: Job? = null
    init {
        view.heading("Settings")
        val rail = a.column()
        for ((name, label) in listOf("Playback" to "Playback", "On demand" to "On demand", "Live TV settings" to "Live TV", "Appearance" to "Appearance", "Data & storage" to "Storage")) {
            val button = a.action(label, name) { selectPage(name) }.apply {
                id = View.generateViewId(); gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), a.round(RAISED, WHITE))
                    addState(intArrayOf(android.R.attr.state_selected), a.round(RAISED))
                    addState(intArrayOf(), a.round(android.graphics.Color.TRANSPARENT))
                }
            }
            categories[name] = button
            rail.add(button, height = a.dp(48)); rail.gap(5)
        }
        view.addView(a.row().apply {
            gravity = Gravity.TOP
            addView(rail, LinearLayout.LayoutParams(a.dp(178), -1).apply { marginEnd = a.dp(24) })
            addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        view.add(status); showPage()
    }
    private fun selectPage(name: String) {
        if (selected == name) return
        positions[selected] = scroll.scrollY; selected = name; localStatus = ""
        showPage()
    }
    private fun heading(text: String) {
        page.add(a.text(text, 13f, MUTED, true)); page.gap(8)
    }
    private fun setting(name: String, value: () -> String, checked: (() -> Boolean)? = null, action: () -> Unit) {
        val row = SettingRow(a, name, checked != null).apply {
            id = View.generateViewId(); tag = "setting:$name"; contentDescription = name
            nextFocusLeftId = categories.getValue(selected).id
            setOnClickListener { action(); update() }
        }
        bindings += { row.bind(value(), checked?.invoke()) }
        page.add(row); page.gap(6)
    }
    private fun toggle(name: String, value: () -> Boolean, change: () -> Unit) =
        setting(name, { if (value()) "On" else "Off" }, value, change)
    private fun <T> choice(name: String, values: List<T>, current: () -> T, label: (T) -> String = { it.toString() }, choose: (T) -> Unit) {
        setting(name, { label(current()) }) {
            val available = if (current() in values) values else values + current()
            a.chooseOption(name, available.map(label), available.indexOf(current())) { index -> choose(available[index]); update() }
        }
    }
    private fun showPage() {
        page.removeAllViews(); bindings.clear()
        categories.forEach { (name, button) -> button.isSelected = name == selected }
        when (selected) {
            "Playback" -> {
                heading("Player")
                choice("Video fit", listOf("Fit", "Fill", "Zoom"), { m.preferences.videoFit }) { m.updatePreferences(m.preferences.copy(videoFit = it)) }
                page.gap(12); heading("Audio & subtitles")
                val language: (String) -> String = { code -> when (code) { "" -> "Provider default"; "en" -> "English"; "es" -> "Spanish"; "hi" -> "Hindi"; "fr" -> "French"; "ja" -> "Japanese"; else -> code } }
                val audioLanguageLabel: (String) -> String = { code -> if (code.isEmpty()) "English (default)" else language(code) }
                choice("Preferred audio language", listOf("", "en", "es", "hi", "fr", "ja"), { m.preferences.audioLanguage }, audioLanguageLabel) { m.updatePreferences(m.preferences.copy(audioLanguage = it)) }
                toggle("Subtitles by default", { m.preferences.subtitles }) { m.updatePreferences(m.preferences.copy(subtitles = !m.preferences.subtitles)) }
                choice("Subtitle language", listOf("", "en", "es", "hi", "fr", "ja"), { m.preferences.subtitleLanguage }, language) { m.updatePreferences(m.preferences.copy(subtitleLanguage = it)) }
            }
            "On demand" -> {
                heading("Add-ons")
                setting("Manage add-ons", { "${m.addons.count { it.enabled }} enabled" }) { m.open(Route.Addons) }
                page.gap(12); heading("Movies & shows")
                toggle("Resume playback", { m.preferences.resume }) { m.updatePreferences(m.preferences.copy(resume = !m.preferences.resume)) }
                choice("Stream size order", listOf(true, false), { m.preferences.largestFirst }, { if (it) "Largest first" else "Smallest first" }) { m.updatePreferences(m.preferences.copy(largestFirst = it)) }
                choice("Playback speed", listOf(.75f, 1f, 1.25f, 1.5f, 2f), { m.preferences.speed }, { "${it}×" }) { m.updatePreferences(m.preferences.copy(speed = it)) }
                choice("Seek interval", listOf(5, 10, 15, 30), { m.preferences.seekSeconds }, { "$it seconds" }) { m.updatePreferences(m.preferences.copy(seekSeconds = it)) }
            }
            "Live TV settings" -> {
                heading("IPTV")
                page.add(a.action("Manage IPTV profiles") { m.manageProfiles() }); page.gap(16)
                heading("Channel playback")
                choice("Live buffering", listOf(3, 6, 10), { m.preferences.liveBufferSeconds }, { "$it seconds" }) { m.updatePreferences(m.preferences.copy(liveBufferSeconds = it)) }
                choice("Automatic live reconnects", listOf(0, 1, 2, 3), { m.preferences.liveRetries }, { if (it == 0) "Off" else it.toString() }) { m.updatePreferences(m.preferences.copy(liveRetries = it)) }
                choice("Channel order", listOf("Provider", "A–Z"), { m.preferences.liveSort }) { m.updatePreferences(m.preferences.copy(liveSort = it)) }
                page.gap(8); page.add(a.text("Hold OK on a channel to add or remove a favorite.", 13f, MUTED))
            }
            "Appearance" -> {
                heading("Navigation")
                choice("Startup screen", listOf("On-Demand", "Live TV"), { m.preferences.startSection }) { m.updatePreferences(m.preferences.copy(startSection = it)) }
                choice("Poster size", listOf(false, true), { m.preferences.largePosters }, { if (it) "Large" else "Standard" }) { m.updatePreferences(m.preferences.copy(largePosters = it)) }
                page.gap(12); heading("Search home")
                toggle("Show continue watching", { m.preferences.showContinue }) { m.updatePreferences(m.preferences.copy(showContinue = !m.preferences.showContinue)) }
                toggle("Show recently watched", { m.preferences.showRecent }) { m.updatePreferences(m.preferences.copy(showRecent = !m.preferences.showRecent)) }
                toggle("Show My list", { m.preferences.showMyList }) { m.updatePreferences(m.preferences.copy(showMyList = !m.preferences.showMyList)) }
            }
            else -> {
                m.store.readWarning?.let { page.add(a.text(it, 13f, MUTED)); page.gap(12) }
                heading("Data on this TV")
                page.add(a.action("Refresh content") { m.refreshContent(); localStatus = "Movies and shows will reload when you return to Search."; update() }); page.gap(8)
                page.add(a.action("Clear watch history") { a.confirm("Clear watch history? Saved titles and profiles will remain.", "Confirm reset") { m.clearHistory(); localStatus = "Watch history cleared."; update() } }); page.gap(8)
                page.add(a.action("Reset playback and appearance") { a.confirm("Reset playback and appearance? Profiles and history will remain.", "Confirm reset") { m.updatePreferences(Preferences(defaultProfileId = m.preferences.defaultProfileId)); localStatus = "Playback and appearance reset."; update() } }); page.gap(8)
                page.add(a.action("Reset provider storage") { a.confirm("Delete all profiles, credentials and channel favorites?", "Confirm reset") { m.resetProviders(); localStatus = if (m.error == null) "IPTV profiles removed." else ""; update() } }); page.gap(18)
                heading("About")
                val version = runCatching { @Suppress("DEPRECATION") a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull().orEmpty()
                page.add(a.text("777tv $version", 13f, MUTED)); page.gap(8)
                page.add(a.action("Open source licenses") { licenses() }.quiet(), width = -2)
            }
        }
        val first = firstControl(page)
        if (first != null) {
            if (first.id == View.NO_ID) first.id = View.generateViewId()
            categories.values.forEach { it.nextFocusRightId = first.id }
        }
        val position = positions[selected] ?: 0
        scroll.post { if (!closed) scroll.scrollTo(0, position) }
        update()
    }
    private fun firstControl(group: View): View? {
        if (group.isFocusable && group.isEnabled && group.visibility == View.VISIBLE) return group
        if (group is android.view.ViewGroup) for (i in 0 until group.childCount) firstControl(group.getChildAt(i))?.let { return it }
        return null
    }
    override fun update() {
        bindings.forEach { it() }
        val text = m.error ?: localStatus
        status.text = text; status.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        status.setTextColor(if (m.error != null) 0xFFFFC4C4.toInt() else MUTED)
    }
    override fun focusTarget(): View? = categories[selected]
    override fun saveState(): Parcelable = Bundle().apply {
        putString("category", selected)
        positions[selected] = scroll.scrollY; positions.forEach { (name, y) -> putInt("scroll:$name", y) }
    }
    override fun restoreState(state: Parcelable?) {
        val saved = state as? Bundle ?: return
        selected = saved.getString("category")?.takeIf { it in categories } ?: selected
        categories.keys.forEach { positions[it] = saved.getInt("scroll:$it") }; showPage()
    }
    override fun close() { closed = true; licenseJob?.cancel(); licenseDialog?.dismiss(); licenseDialog = null }
    private fun licenses() {
        val names = listOf("Overview") + a.assets.list("licenses").orEmpty().sorted()
        licenseDialog = android.app.AlertDialog.Builder(a).setTitle("Open source licenses").setItems(names.toTypedArray()) { _, index ->
            licenseJob?.cancel()
            licenseJob = a.lifecycleScope.launch {
                val content = withContext(Dispatchers.IO) { a.assets.open(if (index == 0) "THIRD_PARTY_NOTICES.md" else "licenses/${names[index]}").bufferedReader().use { it.readText() } }
                if (closed) return@launch
                val text = a.text(content, 13f).apply { pad(16); setTextIsSelectable(true) }
                licenseDialog = android.app.AlertDialog.Builder(a).setTitle(names[index]).setView(ScrollView(a).apply { addView(text) }).setPositiveButton("Done", null).show().apply {
                    window?.setBackgroundDrawable(a.round(PANEL))
                }
            }
        }.setNegativeButton("Done", null).show().apply { window?.setBackgroundDrawable(a.round(PANEL)) }
    }
}

private class SettingRow(context: Context, name: String, private val checkable: Boolean) : LinearLayout(context) {
    private val value = context.text("", 14f, MUTED)
    private var checked = false
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        minimumHeight = context.dp(54); setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
        background = context.focusBackground()
        addView(context.text(name, 14f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LayoutParams(0, -2, 1f))
        addView(value, LayoutParams(-2, -2).apply { marginStart = context.dp(16) })
        if (!checkable) addView(context.text("›", 19f, MUTED), LayoutParams(-2, -2).apply { marginStart = context.dp(12) })
    }
    fun bind(text: String, selected: Boolean?) {
        if (value.text.toString() != text) value.text = text
        checked = selected == true
        value.setTextColor(if (checked) WHITE else MUTED)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = if (checkable) "android.widget.Switch" else "android.widget.Button"
        info.isCheckable = checkable; info.isChecked = checked
        info.text = "$contentDescription, ${value.text}"
        if (android.os.Build.VERSION.SDK_INT >= 30) info.stateDescription = value.text
    }
}
