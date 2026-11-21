package xyz.billybishop4.fleet.tv

import android.graphics.Color
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import kotlinx.coroutines.*
import java.util.Date

private data class LiveGroup(val key: String, val name: String, val group: String? = null, val favorite: Boolean = false)

/** Independent recycled panes keep browsing and the current player in place. */
internal class ChannelsScreen(private val a: MainActivity, private val provider: Provider) : NativeScreen {
    private val m = a.model
    override val view = a.column()
    private val count = a.text("", 11f, MUTED)
    private val grid = wrapAwareList("channels", cachedViews = 6)
    private val groups = wrapAwareList("groups")
    private val options = a.action("Options", "Live TV options") { showOptions() }.quiet()
    private val preview = FrameLayout(a).apply {
        tag = "channel-preview"; background = a.round(PANEL); clipToOutline = true
        isFocusable = false; isFocusableInTouchMode = true
    }
    private val previewHost = FrameLayout(a).apply { tag = "preview-video" }
    private val nowTitle = a.text("", 18f, bold = true).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE
    }
    private val guideHeading = a.text("Schedule", 11f, MUTED).apply { visibility = View.GONE }
    private val guide = a.column().apply { tag = "channel-guide"; visibility = View.GONE }
    private val empty = a.column().apply { gravity = Gravity.CENTER; pad(12); visibility = View.GONE }
    private val emptyProgress = ProgressBar(a).apply { isIndeterminate = true; visibility = View.GONE }
    private val emptyTitle = a.text("", 15f, bold = true).apply { gravity = Gravity.CENTER }
    private val emptyMessage = a.text("", 12f, MUTED).apply { gravity = Gravity.CENTER }
    private val emptyAction = a.action("", "Channel recovery") {}.quiet().apply { visibility = View.GONE }
    private lateinit var searchInput: EditText
    private val clearSearch = a.action("Clear", "Clear channel search") { clearQuery() }.quiet().apply { visibility = View.GONE }
    private var filterJob: Job? = null
    private var guideJob: Job? = null
    private var filterKey: List<Any?>? = null
    private var groupedList: List<Channel>? = null
    private var index: ChannelIndex? = null
    private var categoryNames: List<String> = emptyList()
    private var categoryKey: List<Any?>? = null
    private var listVersion = 0
    private var restored: Parcelable? = null
    private var selected: Channel? = null
    private var selectedId = ""
    private var closed = false
    private var requestedCategoryFocus: View? = null
    private var rowFocusListener: ViewTreeObserver.OnPreDrawListener? = null
    private var categoryFocusListener: ViewTreeObserver.OnPreDrawListener? = null
    private var shownPrograms: List<GuideProgram>? = null
    private var shownGuideMessage = ""
    private var shownCurrentStart: Long? = null
    private val adapter = NativeAdapter<Channel>({ it.identity }, { _, _ ->
        LiveChannelRow(a, ::openChannel, { m.toggleFavorite(provider, it) })
    }, { v, c, _ ->
        (v as LiveChannelRow).bind(c, hasPreview(c), favoriteKey(provider.id, c) in m.favorites, m.channelGroup == "All channels")
    }, recycle = { (it as LiveChannelRow).release() })
    private val groupAdapter = NativeAdapter<LiveGroup>({ it.key }, { _, _ ->
        a.action("") {}.apply {
            layoutParams = RecyclerView.LayoutParams(-1, a.dp(43)).apply { bottomMargin = a.dp(3) }
            gravity = Gravity.START or Gravity.CENTER_VERTICAL; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 13f
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), a.round(RAISED, WHITE))
                addState(intArrayOf(android.R.attr.state_selected), a.round(PANEL))
                addState(intArrayOf(), a.round(Color.TRANSPARENT))
            }
        }
    }, { v, item, _ ->
        (v as Button).apply {
            text = item.name
            tag = if (item.favorite) "category:favorites" else "button:${item.group}"
            contentDescription = if (item.favorite) favoriteDescription() else item.name
            isSelected = groupSelected(item)
            setOnClickListener { activateGroup(item, this) }
        }
    })

    init {
        view.add(a.row().apply {
            addView(a.text(provider.name, 22f, bold = true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            add(options, width = -2)
        }); view.gap(14)
        val body = a.row().apply { gravity = Gravity.TOP }
        val categories = a.column().apply {
            add(a.text("Categories", 11f, MUTED)); gap(12)
            groups.adapter = groupAdapter; addView(groups, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val channels = a.column().apply {
            val field = a.field("Search channels", m.channelQuery) { m.channelQuery = it }
            field.getChildAt(0).visibility = View.GONE; field.getChildAt(1).visibility = View.GONE
            searchInput = (field.findTag("field:Search channels") as EditText).apply {
                id = View.generateViewId(); hint = "Search channels"; textSize = 13f; layoutParams.height = a.dp(38)
            }
            add(a.row().apply {
                addView(field, LinearLayout.LayoutParams(0, -2, 1f)); add(clearSearch, width = -2)
            }); add(count); gap(6)
            empty.add(emptyProgress, width = a.dp(28), height = a.dp(28)); empty.gap(10)
            empty.add(emptyTitle); empty.gap(8); empty.add(emptyMessage); empty.gap(10); empty.add(emptyAction, width = -2)
            val lists = FrameLayout(a).apply {
                grid.adapter = adapter; addView(grid, FrameLayout.LayoutParams(-1, -1))
                addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            }
            addView(lists, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val watching = a.column()
        previewHost.addView(a.column().apply {
            gravity = Gravity.CENTER
            add(a.text("Channel preview", 16f, MUTED).apply { gravity = Gravity.CENTER })
            gap(7); add(a.text("Press OK on a channel to preview", 12f, MUTED).apply { gravity = Gravity.CENTER })
        }, FrameLayout.LayoutParams(-1, -1))
        preview.addView(previewHost, FrameLayout.LayoutParams(-1, -1))
        preview.foreground = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), a.round(Color.TRANSPARENT, WHITE))
            addState(intArrayOf(), a.round(Color.TRANSPARENT))
        }
        preview.setOnFocusChangeListener { _, focus -> a.previewFocused(focus) }
        preview.setOnClickListener { if (selected != null) a.expandPreview() }
        watching.add(preview, height = a.dp(180)); watching.gap(12); watching.add(nowTitle); watching.gap(12)
        watching.add(guideHeading); watching.gap(3)
        watching.addView(ScrollView(a).apply { isFocusable = false; addView(guide) }, LinearLayout.LayoutParams(-1, 0, 1f))
        fun pane(v: View, weight: Float, gap: Int) {
            body.addView(v, LinearLayout.LayoutParams(0, -1, weight).apply { marginEnd = a.dp(gap) })
        }
        pane(categories, .20f, 14); pane(channels, .35f, 18); pane(watching, .45f, 0)
        view.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        preview.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val height = ((r - l) * 9 / 16).coerceAtLeast(a.dp(100))
            if (preview.layoutParams.height != height) preview.layoutParams = preview.layoutParams.apply { this.height = height }
        }
    }

    /**
     * A Live TV list whose DPAD wraps at the ends: Down on the last item
     * focuses the first, Up on the first focuses the last. Intercepted in
     * dispatchKeyEvent, which every ancestor sees on the way down to the
     * focused row — an OnKeyListener on the list itself never fires,
     * because the row, not the list, holds focus.
     */
    private fun wrapAwareList(tag: String, cachedViews: Int = 2): RecyclerView {
        val list = object : RecyclerView(a) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.action == KeyEvent.ACTION_DOWN) {
                    val total = adapter?.itemCount ?: 0
                    val child = focusedChild
                    if (total > 1 && child != null) {
                        val position = getChildAdapterPosition(child)
                        val target = when {
                            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == total - 1 -> 0
                            event.keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0 -> total - 1
                            else -> null
                        }
                        if (target != null) { wrapFocus(this, target, total); return true }
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }
        list.tag = tag; list.layoutManager = LinearLayoutManager(a); list.tune(cachedViews)
        return list
    }

    private fun wrapFocus(list: RecyclerView, target: Int, total: Int) {
        list.scrollToPosition(target)
        val inputGeneration = a.focusInputGeneration
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (closed || !list.isShown || a.focusInputGeneration != inputGeneration || list.adapter?.itemCount != total) {
                    if (list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
                val row = list.findViewHolderForAdapterPosition(target)?.itemView
                if (row != null) { list.viewTreeObserver.removeOnPreDrawListener(this); row.requestFocus() }
                return true
            }
        }
        list.viewTreeObserver.addOnPreDrawListener(listener)
        list.invalidate()
    }

    private fun favoriteDescription() = if (m.onlyFavoriteChannels) "★ Favorites only" else "★ Favorites"
    private fun groupSelected(item: LiveGroup) = if (item.favorite) m.onlyFavoriteChannels else !m.onlyFavoriteChannels && item.group == m.channelGroup
    private fun showOptions() {
        cancelRowFocus()
        val actions=arrayListOf<Pair<String,()->Unit>>()
        if(selected!=null && a.activePlayer!=null)actions+="Picture controls" to { a.playback?.showPictureControls() }
        actions.addAll(listOf(
            "Refresh channels" to { m.retry() },
            "Edit categories" to { editCategories() },
            "Manage IPTV profiles" to { m.manageProfiles() },
        ))
        a.showActions("Live TV",actions)
    }
    private fun clearQuery() { searchInput.setText(""); searchInput.requestFocus() }
    private fun activateGroup(item: LiveGroup, source: View) {
        cancelRowFocus()
        val only = item.favorite
        val group = item.group ?: "All channels"
        val changed = m.channelGroup != group || m.onlyFavoriteChannels != only
        requestedCategoryFocus = source
        m.channelGroup = group; m.onlyFavoriteChannels = only
        if (!changed) focusRow(0, source)
    }
    private fun cancelRowFocus() {
        requestedCategoryFocus = null
        rowFocusListener?.let { if (grid.viewTreeObserver.isAlive) grid.viewTreeObserver.removeOnPreDrawListener(it) }
        rowFocusListener = null
        categoryFocusListener?.let { if (groups.viewTreeObserver.isAlive) groups.viewTreeObserver.removeOnPreDrawListener(it) }
        categoryFocusListener = null
    }
    private fun focusRow(position: Int, source: View? = null): Boolean {
        val channel = adapter.currentList.getOrNull(position) ?: return false
        cancelRowFocus()
        if (source != null && !source.hasFocus()) return false
        grid.scrollToPosition(position)
        val expectedKey = filterKey
        val inputGeneration = a.focusInputGeneration
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (closed || a.focusInputGeneration != inputGeneration || filterKey != expectedKey || !grid.isShown || !a.hasWindowFocus() || (source != null && !source.hasFocus())) { cancelRowFocus(); return true }
                if (adapter.currentList.getOrNull(position)?.identity != channel.identity) { cancelRowFocus(); return true }
                val row = grid.findViewHolderForAdapterPosition(position)?.itemView
                if (row?.tag == "channel:${channel.id}" && adapter.currentList.getOrNull(position)?.identity == channel.identity) {
                    cancelRowFocus(); row.requestFocus()
                }
                return true
            }
        }
        rowFocusListener = listener
        grid.viewTreeObserver.addOnPreDrawListener(listener)
        grid.invalidate()
        return true
    }
    internal fun focusSelectedChannel(): Boolean {
        val position = adapter.currentList.indexOfFirst { it.identity == selectedId }
        return if (position >= 0) focusRow(position) else false
    }
    override fun focusTarget(): View? {
        if (m.busy && m.channels.isEmpty()) return null
        if (emptyProgress.isShown) return null
        val position = adapter.currentList.indexOfFirst { it.identity == selectedId }.takeIf { it >= 0 } ?: 0
        if (grid.isShown && adapter.itemCount > 0) {
            return grid.findViewHolderForAdapterPosition(position)?.itemView?.takeIf { it.isShown }
                ?: (0 until grid.childCount).map { grid.getChildAt(it) }.firstOrNull { it.isShown && it.isFocusable }
        }
        emptyAction.takeIf { it.isShown }?.let { return it }
        if (empty.isShown && m.channelQuery.isNotBlank()) return searchInput
        if (empty.isShown) {
            val source = groupAdapter.currentList.indexOfFirst(::groupSelected)
            return groups.findViewHolderForAdapterPosition(source)?.itemView?.takeIf { it.isShown }
        }
        return null
    }
    override fun restoreFocus(tag: String, parentTag: String?): Boolean {
        if (tag.startsWith("channel:")) {
            val position = adapter.currentList.indexOfFirst { "channel:${it.id}" == tag }
            return position >= 0 && focusRow(position)
        }
        val position = groupAdapter.currentList.indexOfFirst {
            tag == if (it.favorite) "category:favorites" else "button:${it.group}"
        }
        if (position < 0) return false
        val key = groupAdapter.currentList[position].key
        cancelRowFocus(); groups.scrollToPosition(position)
        val inputGeneration = a.focusInputGeneration
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (closed || a.focusInputGeneration != inputGeneration || !groups.isShown || !a.hasWindowFocus() || groupAdapter.currentList.getOrNull(position)?.key != key) { cancelRowFocus(); return true }
                val row = groups.findViewHolderForAdapterPosition(position)?.itemView
                if (row?.tag == tag) { cancelRowFocus(); row.requestFocus() }
                return true
            }
        }
        categoryFocusListener = listener
        groups.viewTreeObserver.addOnPreDrawListener(listener); groups.invalidate()
        return true
    }

    private fun editCategories() {
        cancelRowFocus()
        val names = (categoryNames + m.hiddenCategories[provider.id].orEmpty()).distinct()
        if (names.isEmpty()) {
            a.showActions("No categories available", listOf("Refresh channels" to { m.retry() }))
            return
        }
        val checked = BooleanArray(names.size) { names[it] !in m.hiddenCategories[provider.id].orEmpty() }
        val dialog = android.app.AlertDialog.Builder(a).setTitle("Visible categories")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, index, visible -> m.setCategoryVisible(provider.id, names[index], visible) }
            .setNeutralButton("Show all", null).setPositiveButton("Done", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(a.round(PANEL)); dialog.window?.decorView?.clipToOutline = true
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).quiet().isAllCaps = false
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).quiet().isAllCaps = false
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                m.showAllCategories(provider.id)
                names.indices.forEach { checked[it] = true; dialog.listView.setItemChecked(it, true) }
            }
            // Options may have been chosen with a touch/mouse click. Enter
            // keyboard focus explicitly so the next OK toggles the first row.
            dialog.listView.requestFocusFromTouch(); dialog.listView.setSelection(0)
        }
        dialog.show()
    }
    private fun hasPreview(c: Channel) = selectedId == c.identity && a.playback != null
    /** Focus only browses. The first OK previews; another OK on that channel expands it. */
    private fun openChannel(c: Channel, focus: View) {
        if (hasPreview(c)) { a.expandPreview(); return }
        selected = c; selectedId = c.identity
        nowTitle.text = c.name; nowTitle.visibility = View.VISIBLE
        preview.isFocusable = true; preview.contentDescription = "Full screen ${c.name}"; previewHost.removeAllViews()
        a.preview(provider, c, previewHost, focus)
        updateSelectedRows(); loadGuide(c)
    }
    private fun loadGuide(c: Channel) {
        guideJob?.cancel(); showGuide(emptyList(), "Loading schedule…")
        guideJob = a.lifecycleScope.launch {
            delay(500)
            while (isActive && !closed && selectedId == c.identity) {
                try {
                    val programs = m.repository.guide(provider, c)
                    ensureActive(); if (!closed && selectedId == c.identity) showGuide(programs, "No schedule provided for this channel.")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (!closed && selectedId == c.identity) showGuide(emptyList(), "Schedule unavailable. Playback can continue.") }
                delay(60000)
            }
        }
    }
    private fun showGuide(programs: List<GuideProgram>, emptyText: String) {
        val now = System.currentTimeMillis()
        val upcoming = programs.filter { it.endMs > now }.take(8)
        val currentStart = upcoming.firstOrNull { now in it.startMs until it.endMs }?.startMs
        val message = if (upcoming.isEmpty()) emptyText else ""
        if (shownPrograms == upcoming && shownGuideMessage == message && shownCurrentStart == currentStart) return
        shownPrograms = upcoming; shownGuideMessage = message; shownCurrentStart = currentStart
        val focus = guide.findFocus()?.tag; val hadFocus = guide.hasFocus()
        guide.removeAllViews(); guide.visibility = View.VISIBLE
        guideHeading.visibility = if (upcoming.isEmpty()) View.GONE else View.VISIBLE
        if (upcoming.isEmpty()) { guide.add(a.text(emptyText, 12f, MUTED)); if(hadFocus)preview.requestFocus(); return }
        val format = android.text.format.DateFormat.getTimeFormat(a)
        val focusedProgramPresent=upcoming.any { "guide:${it.startMs}" == focus }
        upcoming.forEachIndexed { index, p ->
            val current = p.startMs == currentStart
            val item = a.column().apply {
                tag = "guide:${p.startMs}"; isFocusable = true; isFocusableInTouchMode = true
                setPadding(a.dp(6), a.dp(8), a.dp(6), a.dp(8))
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), a.round(RAISED, WHITE))
                    addState(intArrayOf(), a.round(Color.TRANSPARENT))
                }
            }
            item.add(a.text((if (current) "Now · " else "") + format.format(Date(p.startMs)) + " – " + format.format(Date(p.endMs)), 10f, MUTED))
            item.gap(5); item.add(a.text(p.name, 13f, bold = current).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            guide.add(item)
            if (index < upcoming.lastIndex) guide.add(View(a).apply { setBackgroundColor(PANEL) }, height = a.dp(1))
            if (focus == item.tag || (hadFocus && !focusedProgramPresent && index == 0)) item.requestFocus()
        }
    }
    private fun updateSelectedRows() {
        for (i in 0 until grid.childCount) (grid.getChildAt(i) as? LiveChannelRow)?.let { row ->
            val c = adapter.currentList.getOrNull(grid.getChildAdapterPosition(row))
            if (c != null) row.mark(hasPreview(c), favoriteKey(provider.id, c) in m.favorites, m.channelGroup == "All channels")
        }
    }
    private fun showEmpty(title: String, message: String = "", action: String? = null, busy: Boolean = false, run: () -> Unit = {}) {
        empty.visibility = View.VISIBLE; grid.visibility = View.INVISIBLE; count.visibility = View.GONE
        emptyTitle.text = title; emptyMessage.text = message; emptyMessage.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        emptyProgress.visibility = if (busy) View.VISIBLE else View.GONE
        emptyAction.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action != null) { emptyAction.text = action; emptyAction.contentDescription = action; emptyAction.setOnClickListener { run() } }
    }
    override fun update() {
        clearSearch.visibility = if (m.channelQuery.isBlank()) View.GONE else View.VISIBLE
        if (groupedList !== m.channels) { groupedList = m.channels; listVersion++; index = null }
        val hidden = m.hiddenCategories[provider.id].orEmpty()
        if (m.channelGroup != "All channels" && m.channelGroup in hidden) m.channelGroup = "All channels"
        updateSelectedRows()
        for (i in 0 until groups.childCount) (groups.getChildAt(i) as? Button)?.let { row ->
            if (row.tag == "category:favorites") { row.isSelected = m.onlyFavoriteChannels; row.contentDescription = favoriteDescription() }
            else row.isSelected = !m.onlyFavoriteChannels && row.tag == "button:${m.channelGroup}"
        }
        if (m.busy && m.channels.isEmpty()) showEmpty("Loading channels…", busy = true)
        else if (m.error != null && m.channels.isEmpty()) showEmpty("Couldn't load channels", m.error.orEmpty(), "Try again") { m.retry() }
        val key = listOf(listVersion, m.channelGroup, m.channelQuery, m.onlyFavoriteChannels, if (m.onlyFavoriteChannels) m.favorites else null, m.preferences.liveSort, hidden, m.busy, m.error)
        if (key == filterKey) return
        filterKey = key
        val channels = m.channels; val group = m.channelGroup; val query = m.channelQuery
        val only = m.onlyFavoriteChannels; val saved = m.favorites; val sort = m.preferences.liveSort == "A–Z"
        filterJob?.cancel(); filterJob = a.lifecycleScope.launch {
            if (query.isNotEmpty()) delay(150)
            val cached = index
            val (prepared, filtered, favoritesHidden) = withContext(Dispatchers.Default) {
                val prepared = cached ?: ChannelIndex(channels)
                val filtered=prepared.select(provider.id, group, query, only, saved, sort, hidden)
                val favoritesHidden=only && filtered.isEmpty() && hidden.isNotEmpty() &&
                    channels.any { it.group in hidden && favoriteKey(provider.id,it) in saved }
                Triple(prepared,filtered,favoritesHidden)
            }
            ensureActive(); index = prepared; categoryNames = prepared.categories
            val nextCategories = listOf(listVersion, hidden)
            if (nextCategories != categoryKey) {
                categoryKey = nextCategories
                val categories = listOf(LiveGroup("all", "All channels", "All channels"), LiveGroup("favorites", "★ Favorites", favorite = true)) +
                    categoryNames.filterNot { it in hidden || it == "All channels" }.map { LiveGroup("category:$it", it, it) }
                groupAdapter.submitList(categories)
            }
            if (channels.isNotEmpty() && group != "All channels" && group !in categoryNames) { m.channelGroup = "All channels"; return@launch }
            adapter.submitList(filtered) {
                if (closed || filterKey != key) return@submitList
                restored?.let { grid.layoutManager?.onRestoreInstanceState(it); restored = null }
                when {
                    m.busy && channels.isEmpty() -> showEmpty("Loading channels…", busy = true)
                    m.error != null && channels.isEmpty() -> showEmpty("Couldn't load channels", m.error.orEmpty(), "Try again") { m.retry() }
                    channels.isEmpty() -> showEmpty("No channels available", "Refresh or check your IPTV profile.", "Refresh channels") { m.retry() }
                    categoryNames.all { it in hidden } -> showEmpty("All categories hidden", "Choose which categories to show.", "Edit categories") { editCategories() }
                    filtered.isEmpty() && query.isNotBlank() -> showEmpty("No channels found", "Try another search or clear the filter.")
                    filtered.isEmpty() && favoritesHidden -> showEmpty("Favorites are hidden", "Your favorites are saved. Show their categories to see them here.", "Edit categories") { editCategories() }
                    filtered.isEmpty() && only -> showEmpty("No favorites yet", "Press and hold OK on a channel to save it.")
                    filtered.isEmpty() -> showEmpty("No channels in this category", "Choose another category.")
                    else -> {
                        empty.visibility = View.GONE; grid.visibility = View.VISIBLE; count.visibility = View.VISIBLE
                        count.text = "${filtered.size} ${if (filtered.size == 1) "channel" else "channels"}"
                    }
                }
                requestedCategoryFocus?.let { source ->
                    if (filtered.isNotEmpty()) focusRow(0, source) else cancelRowFocus()
                }
            }
        }
    }
    override fun saveState(): Parcelable = Bundle().apply { putParcelable("list", grid.layoutManager?.onSaveInstanceState()); putString("selected", selectedId) }
    @Suppress("DEPRECATION") override fun restoreState(state: Parcelable?) {
        if (state is Bundle) { restored = state.getParcelable("list"); selectedId = state.getString("selected").orEmpty() } else restored = state
    }
    override fun background() { cancelRowFocus(); guideJob?.cancel() }
    override fun foreground() { selected?.takeIf { guideJob?.isActive != true }?.let(::loadGuide) }
    override fun close() { closed = true; filterJob?.cancel(); cancelRowFocus(); guideJob?.cancel(); grid.adapter = null; groups.adapter = null }
}
