// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

private const val MISSION_EXTRA = "mission"
private const val SEARCH_LANGUAGE = "mission_search_language"

private fun Context.openMission(mission: GtaMission, language: GtaMissionSearchLanguage) {
    startActivity(Intent(this, GtaMissionDetailsActivity::class.java)
        .putExtra(MISSION_EXTRA, mission.id)
        .putExtra(SEARCH_LANGUAGE, language.name))
}

private fun Context.missionAvatar(description: String, portrait: GtaMissionImage?, size: Int) = FrameLayout(this).apply {
    background = filled(mix(Miami.surface, Miami.aqua, .16f), size / 2, mix(Miami.edge, Miami.aqua, .4f))
    clipToOutline = true
    val placeholder = glyph(R.drawable.ic_mission_character, Miami.aqua)
    addView(placeholder, FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER))
    if (portrait != null) {
        addView(context.missionImageView(portrait, 240) { placeholder.visibility = View.INVISIBLE }.apply {
            contentDescription = description
        }, FrameLayout.LayoutParams(-1, -1))
    }
}

class GtaMissionSearchActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var search: EditText
    private lateinit var feedback: TextView
    private lateinit var results: LinearLayout
    private lateinit var install: Button
    private lateinit var turkish: Button
    private lateinit var english: Button
    private lateinit var typeChoices: LinearLayout
    private lateinit var chapterChoices: LinearLayout
    private lateinit var pageTitle: TextView
    private lateinit var hero: FrameLayout
    private var language = GtaMissionSearchLanguage.TURKISH
    private var typeFilter: GtaMissionType? = null
    private var chapterFilter: Int? = null
    private var catalog: GtaMissionCatalog? = null
    private var catalogLoadFailed: Boolean? = null
    private var visibleCount = PAGE_SIZE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        typeFilter = GtaMissionType.entries.firstOrNull { it.key == savedInstanceState?.getString("missionType") }
        chapterFilter = savedInstanceState?.getInt("missionChapter", -1)?.takeIf { it in GTA_PROLOGUE..GTA_NO_CHAPTER }
        val content = miamiPage("Görev Ara", Miami.pink) { pageTitle = it }
        hero = FrameLayout(this)
        content.addBelow(hero)
        val searchPanel = miamiPanel(Miami.pink)
        turkish = press("Türkçe") { selectLanguage(GtaMissionSearchLanguage.TURKISH) }
        english = press("İngilizce") { selectLanguage(GtaMissionSearchLanguage.ENGLISH) }
        searchPanel.addBelow(actionRow(turkish, english))
        search = EditText(this).apply {
            setHintTextColor(Palette.quiet)
            setTextColor(Palette.white)
            textSize = 16f
            background = filled(Miami.night, 15, Miami.edge)
            setPadding(dp(13), dp(7), dp(13), dp(7))
            minimumHeight = dp(50)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            filters = arrayOf(InputFilter.LengthFilter(120))
            setText(savedInstanceState?.getString("query").orEmpty())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    visibleCount = PAGE_SIZE
                    renderResults()
                }
            })
            setOnEditorActionListener { view, action, _ ->
                if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
                true
            }
        }
        searchPanel.addBelow(search, 10)
        typeChoices = row()
        chapterChoices = row()
        searchPanel.addBelow(filterStrip(typeChoices), 8)
        searchPanel.addBelow(filterStrip(chapterChoices), 6)
        content.addBelow(searchPanel, 13)
        feedback = copy("Görevler yükleniyor…", 14, Palette.quiet).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addBelow(feedback, 12)
        install = press("Oyun paketlerini aç", Miami.coral) {
            startActivity(Intent(this, GamePacksActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }.apply { visibility = View.GONE }
        content.addBelow(install, 8)
        results = stack()
        content.addBelow(results)
        selectLanguage(GtaMissionSearchLanguage.fromName(getPreferences(MODE_PRIVATE).getString(SEARCH_LANGUAGE, null)))
        content.isFocusableInTouchMode = true
        content.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext
        worker.execute {
            val loaded = runCatching { GtaMissionStore.catalog(app) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                catalog = loaded.getOrNull()
                catalogLoadFailed = loaded.isFailure
                renderFilters()
                install.visibility = if (catalog == null) View.VISIBLE else View.GONE
                renderResults()
            }
        }
    }

    private fun selectLanguage(selected: GtaMissionSearchLanguage) {
        language = selected
        getPreferences(MODE_PRIVATE).edit().putString(SEARCH_LANGUAGE, selected.name).apply()
        visibleCount = PAGE_SIZE
        pageTitle.text = language.local("Görev Ara", "Mission Search")
        hero.removeAllViews()
        hero.addView(miamiHero(language.local("GTA 6 görevleri", "GTA 6 missions"),
            language.local("Oyunda gördüğün görev adıyla ara.", "Search by the mission name shown in-game."), Miami.pink))
        install.text = language.local("Oyun paketlerini aç", "Open game packs")
        search.hint = language.searchHint
        turkish.miamiChoice(language == GtaMissionSearchLanguage.TURKISH, Miami.pink)
        english.miamiChoice(language == GtaMissionSearchLanguage.ENGLISH, Miami.aqua)
        renderFilters()
        renderResults()
    }

    private fun filterStrip(options: LinearLayout) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(options)
    }

    private fun renderFilters() {
        typeChoices.removeAllViews()
        chapterChoices.removeAllViews()
        val types = listOf(null) + GtaMissionType.entries
        types.forEach { type ->
            val label = type?.searchLabel(language) ?: language.local("Tümü", "All missions")
            addFilter(typeChoices, label, type == typeFilter, Miami.pink) {
                typeFilter = type
                applyFilters()
            }
        }
        val available = catalog?.missions.orEmpty().filter { it.searchTarget == null }
        val chapters = available.map(GtaMission::chapterGroup).distinct().sorted()
        (listOf(null) + chapters).forEach { chapter ->
            val label = chapter?.let(language::chapterLabel) ?: language.local("Tüm bölümler", "All chapters")
            addFilter(chapterChoices, label, chapter == chapterFilter, Miami.aqua) {
                chapterFilter = chapter
                applyFilters()
            }
        }
    }

    private fun addFilter(group: LinearLayout, label: String, selected: Boolean, accent: Int, action: () -> Unit) {
        val button = press(label, accent, action).apply {
            miamiChoice(selected, accent)
            minHeight = dp(44)
            textSize = 13f
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        group.addView(button, LinearLayout.LayoutParams(-2, -2).apply {
            if (group.childCount > 0) marginStart = dp(6)
        })
        if (selected) button.post {
            val scroll = group.parent as? HorizontalScrollView ?: return@post
            if (button.isAttachedToWindow && (button.left < scroll.scrollX || button.right > scroll.scrollX + scroll.width)) {
                scroll.smoothScrollTo((button.right - scroll.width).coerceAtLeast(0), 0)
            }
        }
    }

    private fun applyFilters() {
        visibleCount = PAGE_SIZE
        renderFilters()
        renderResults()
    }

    private fun renderResults() {
        results.removeAllViews()
        val available = catalog ?: run {
            feedback.text = when (catalogLoadFailed) {
                null -> language.local("Görevler yükleniyor…", "Loading missions…")
                true -> language.local("Görev paketi açılamadı. Oyun Paketleri’nden yeniden indir.",
                    "The mission pack could not be opened. Download it again in Game Packs.")
                false -> language.local("Görev rehberini Oyun Paketleri’nden indir.",
                    "Download the mission guide from Game Packs.")
            }
            return
        }
        val query = search.text.toString()
        val matches = available.search(query, language, typeFilter, chapterFilter)
        feedback.text = when (matches.size) {
            0 -> language.local("Bu seçimle eşleşen görev bulunamadı.", "No missions match these filters.")
            1 -> language.local("1 görev", "1 mission")
            else -> language.local("${matches.size} görev", "${matches.size} missions")
        }
        val chapters = query.isBlank() && chapterFilter == null
        matches.take(visibleCount).forEachIndexed { index, mission ->
            if (chapters && (index == 0 || matches[index - 1].chapterGroup != mission.chapterGroup)) {
                results.addBelow(copy(language.chapterLabel(mission.chapterGroup), 16, Miami.aqua, true).apply {
                    isAccessibilityHeading = true
                }, if (index == 0) 8 else 14)
            }
            results.addBelow(missionTile(mission, query), 8)
        }
        if (matches.size > visibleCount) results.addBelow(press(language.local("Daha fazla görev göster", "Show more missions")) {
            visibleCount += PAGE_SIZE
            renderResults()
        }, 10)
    }

    private fun missionTile(mission: GtaMission, query: String) = row().apply {
        val title = mission.nameIn(language)
        val alias = mission.mapLabels.firstOrNull { missionNameKey(it) == missionNameKey(query) }
            ?: mission.mapLabels.firstOrNull()
        val secondary = mission.alternateNameIn(language)
        val mapLabel = alias?.takeUnless { it == title || it == secondary }
        val alternate = secondary ?: mission.code.takeIf { it != title && mission.englishTitle == null && mapLabel == null }
        val portrait = catalog?.missionPortrait(mission)
        val thumbnail = portrait?.second ?: mission.previews.firstOrNull()
        val imageLabel = portrait?.let { language.local("${it.first} profil resmi", "${it.first} portrait") }
            ?: language.local("$title görev görüntüsü", "$title mission image")
        background = RippleDrawable(ColorStateList.valueOf(0x28FFFFFF),
            filled(Miami.surface, 20, Miami.edge), null)
        setPadding(dp(12), dp(11), dp(10), dp(11))
        minimumHeight = dp(78)
        isFocusable = true
        contentDescription = title
        setOnClickListener { openMission(mission, language) }
        addView(FrameLayout(context).apply {
            addView(context.missionAvatar(imageLabel, thumbnail, 51), FrameLayout.LayoutParams(dp(51), dp(51)))
            mission.mapIcon?.let { icon ->
                val badge = FrameLayout(context).apply {
                    val image = context.missionImageView(icon, 64) {
                        background = filled(Miami.night, 7)
                    }.apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = language.local("Oyun içi harita simgesi", "In-game map icon")
                    }
                    addView(image, FrameLayout.LayoutParams(-1, -1))
                }
                addView(badge, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END))
            }
        }, LinearLayout.LayoutParams(dp(60), dp(59)))
        addView(stack().apply {
            addView(copy(title, 18, Palette.white, true))
            if (mapLabel != null) addBelow(copy(mapLabel, 13, Miami.lavender, true), 2)
            if (alternate != null) addBelow(copy(alternate, 13), 2)
            val labels = listOfNotNull(mission.giver, mission.categoryIn(language))
            addBelow(copy(labels.joinToString(" · "), 12), 3)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(11) })
        addView(glyph(R.drawable.ic_disclosure, Miami.pink).apply { rotation = -90f },
            LinearLayout.LayoutParams(dp(18), dp(18)))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", search.text.toString())
        outState.putString("missionType", typeFilter?.key)
        chapterFilter?.let { outState.putInt("missionChapter", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private companion object { const val PAGE_SIZE = 24 }
}

class GtaMissionDetailsActivity : Activity() {
    private enum class Pane { INFO, CONVERSATIONS, TARGETS }
    private enum class ConversationView { SCENES, ALL }
    private enum class DialogueSync { IDLE, CHECKING, READY, FAILED }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var infoButton: Button
    private lateinit var textButton: Button
    private lateinit var catalog: GtaMissionCatalog
    private lateinit var mission: GtaMission
    private val language by lazy { GtaMissionSearchLanguage.fromName(intent.getStringExtra(SEARCH_LANGUAGE)) }
    private var pane = Pane.INFO
    private var conversationView = ConversationView.SCENES
    private var visibleRows = PAGE_SIZE
    private var rows: List<GtaMissionText>? = null
    private var dialogue: GtaMissionDialogue? = null
    private var dialogueSync = DialogueSync.IDLE
    private var packError: String? = null
    private var translation: GameTranslatorEngine? = null
    private val translatedLines = mutableMapOf<GtaMissionText, String>()
    private val attemptedTranslations = mutableSetOf<GtaMissionText>()
    private var renderVersion = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pane = Pane.entries.firstOrNull { it.name == savedInstanceState?.getString("missionPane") } ?: Pane.INFO
        conversationView = ConversationView.entries.firstOrNull {
            it.name == savedInstanceState?.getString("missionConversationView")
        } ?: ConversationView.SCENES
        visibleRows = savedInstanceState?.getInt("missionRows", PAGE_SIZE) ?: PAGE_SIZE
        val content = miamiPage("Görev Detayı", Miami.pink)
        val loading = copy("Görev yükleniyor…")
        content.addBelow(loading)
        val app = applicationContext
        val id = intent.getStringExtra(MISSION_EXTRA)
        worker.execute {
            val selected = runCatching {
                val pack = GtaMissionStore.catalog(app) ?: return@runCatching null
                val item = pack.mission(id) ?: return@runCatching null
                pack to item
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                content.removeView(loading)
                if (selected == null) {
                    content.addBelow(miamiSection("Görev açılamadı", "Görev aramasından tekrar seç."))
                    return@runOnUiThread
                }
                catalog = selected.first
                mission = selected.second
                content.addBelow(missionHero())
                infoButton = press("Görev bilgileri") { select(Pane.INFO) }
                textButton = press("Görev metinleri") { if (pane == Pane.INFO) select(Pane.CONVERSATIONS) }
                content.addBelow(actionRow(infoButton, textButton), 12)
                body = stack()
                content.addBelow(body, 12)
                render()
                loadTexts()
                if (pane == Pane.CONVERSATIONS) loadConversations()
            }
        }
    }

    private fun select(target: Pane) {
        if (pane == target) return
        pane = target
        visibleRows = PAGE_SIZE
        if (pane == Pane.CONVERSATIONS) loadConversations()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::mission.isInitialized && pane == Pane.CONVERSATIONS) loadConversations()
    }

    private fun missionHero() = FrameLayout(this).apply {
        val height = 208
        background = filled(Miami.night, 27, mix(Miami.edge, Miami.pink, 0.34f))
        clipToOutline = true
        val base = ImageView(context).apply {
            setImageResource(R.drawable.miami_waterfront)
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(base, FrameLayout.LayoutParams(-1, dp(height), Gravity.TOP))
        mission.previews.firstOrNull()?.let { photo ->
            addView(context.missionImageView(photo, 800).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, dp(height), Gravity.TOP))
        }
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Miami.night, 0x9511182D.toInt(), 0x2011182D))
        }, FrameLayout.LayoutParams(-1, dp(height), Gravity.BOTTOM))
        addView(stack().apply {
            gravity = Gravity.BOTTOM
            minimumHeight = dp(height)
            setPadding(dp(17), dp(14), dp(16), dp(15))
            val category = copy(mission.categoryIn(language), 12, Miami.pink, true).apply {
                background = filled(0xE8141930.toInt(), 12, mix(Miami.edge, Miami.pink, 0.4f))
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }
            addView(category, LinearLayout.LayoutParams(-2, -2))
            addBelow(copy(mission.nameIn(language), 32, Palette.white, true).apply {
                typeface = Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 800, false)
                isAccessibilityHeading = true
                setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Miami.night)
            }, 7)
            mission.alternateNameIn(language)?.let { addBelow(copy(it, 15, Miami.aqua), 2) }
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun render() {
        val version = ++renderVersion
        body.removeAllViews()
        infoButton.missionTab(pane == Pane.INFO, Miami.coral, Miami.pink, R.drawable.ic_portal_steps)
        textButton.missionTab(pane != Pane.INFO, Miami.aqua, 0xFFB9D2FF.toInt(), R.drawable.ic_mission_text)
        if (pane == Pane.INFO) {
            showInfo()
            return
        }
        val options = actionRow(
            press("Konuşmalar") { select(Pane.CONVERSATIONS) }.apply {
                missionTab(pane == Pane.CONVERSATIONS, Miami.aqua, 0xFF9AE1FA.toInt())
            },
            press("Hedefler/Kaynaklar") { select(Pane.TARGETS) }.apply {
                missionTab(pane == Pane.TARGETS, Miami.lavender, Miami.pink)
            },
        ).apply {
            background = detailSurface(Miami.lavender)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        body.addBelow(options)
        if (pane == Pane.CONVERSATIONS) {
            body.addBelow(actionRow(
                press("Sahnelere göre") { selectConversationView(ConversationView.SCENES) }.apply {
                    missionTab(conversationView == ConversationView.SCENES, Miami.aqua, 0xFF9AE1FA.toInt())
                },
                press("Bütün konuşmayı oku") { selectConversationView(ConversationView.ALL) }.apply {
                    missionTab(conversationView == ConversationView.ALL, Miami.pink, Miami.lavender)
                },
            ), 8)
        }
        showPackText(version, conversations = pane == Pane.CONVERSATIONS)
        if (pane == Pane.TARGETS) showSources()
    }

    private fun selectConversationView(target: ConversationView) {
        if (conversationView == target) return
        conversationView = target
        visibleRows = PAGE_SIZE
        render()
    }

    private fun Button.missionTab(selected: Boolean, accent: Int, end: Int, icon: Int? = null) {
        isSelected = selected
        val foreground = if (selected) Miami.night else accent
        val stops = if (selected) intArrayOf(accent, end)
            else intArrayOf(mix(Miami.surface, accent, 0.19f), mix(Miami.surface, end, 0.06f))
        val gradient = GradientDrawable(GradientDrawable.Orientation.TL_BR, stops).apply {
            cornerRadius = dp(if (icon == null) 15 else 20).toFloat()
            setStroke(dp(if (selected) 2 else 1), if (selected) mix(end, Palette.white, 0.5f) else mix(Miami.edge, accent, 0.5f))
        }
        background = RippleDrawable(ColorStateList.valueOf(if (selected) 0x24090C19 else 0x28FFFFFF), gradient, null)
        setTextColor(foreground)
        gravity = Gravity.CENTER
        minHeight = dp(if (icon == null) 48 else 66)
        setPadding(dp(6), dp(8), dp(6), dp(8))
        icon?.let { res ->
            val drawable = context.getDrawable(res)?.mutate()
            drawable?.setTint(foreground)
            drawable?.setBounds(0, 0, dp(19), dp(19))
            compoundDrawablePadding = dp(5)
            setCompoundDrawables(null, drawable, null, null)
        }
    }

    private fun showInfo() {
        val summaryTexts = mission.conversationSummaries.mapTo(mutableSetOf()) { it.text }
        val overview = mission.summary.takeUnless { it in summaryTexts }
        val location = mission.location?.takeUnless { place ->
            overview == null && mission.conversationSummaries.any { it.location == place }
        }
        if (overview != null || location != null) {
            body.addBelow(miamiSection("Genel bilgiler", overview.orEmpty(), Miami.pink).apply {
                background = detailSurface(Miami.pink)
                location?.let { addBelow(copy(it, 14, Miami.aqua, true), 10) }
            })
        }
        body.addBelow(press("Bu görevde canlı altyazı", Miami.aqua) { chooseRecordingConnection() }, 8)
        if (mission.mapLabels.isNotEmpty()) {
            body.addBelow(miamiSection("Oyunda görünen adlar", mission.mapLabels.joinToString("\n"), Miami.coral), 8)
        }
        mission.giver?.let { body.addBelow(giverPanel(it), 10) }
        if (mission.previews.isNotEmpty()) {
            body.addBelow(missionStills(mission.previews.drop(1).ifEmpty { mission.previews }, mission.nameIn(language)), 10)
        }
        if (mission.characters.isNotEmpty()) {
            body.addBelow(miamiSection("Görevde görülenler", mission.characters.joinToString(", "), Miami.lavender), 10)
        }
        val notes = rows.orEmpty().filter { it.type == GtaTextType.GUIDE && it.sceneId != null }.groupBy { it.sceneId }
        if (mission.scenes.isNotEmpty()) body.addBelow(copy("Görev aşamaları", 18, Palette.white, true).apply {
            isAccessibilityHeading = true
        }, 15)
        mission.scenes.forEachIndexed { index, scene ->
            val accent = if (index % 2 == 0) Miami.coral else Miami.aqua
            val description = (scene.context ?: notes[scene.id]?.firstNotNullOfOrNull { it.turkish })
                ?.takeUnless { it == mission.summary || it in summaryTexts }
            body.addBelow(textCard(accent).apply {
                addView(sceneHeading(scene.title, scene.environment, accent))
                description?.let { addBelow(copy(it, 15, Palette.white), 6) }
                addSceneMedia(scene)
            }, 8)
        }
        showSceneSummaries()
        mission.prerequisites.mapNotNull(catalog::mission).distinctBy { it.id }.forEach { previous ->
            body.addBelow(prerequisite(previous), 10)
        }
    }

    private fun chooseRecordingConnection() {
        if (SessionState.current.active || SessionState.current.portalPanel?.testing == true) {
            AlertDialog.Builder(this).setTitle("Açık bağlantı")
                .setMessage("Bu görevde canlı altyazıyı açmak için önce mevcut bağlantıyı durdur.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        val options = stack().apply { setPadding(dp(22), dp(4), dp(22), dp(5)) }
        val title = mission.nameIn(language)
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setView(options).setNegativeButton("Vazgeç", null).create()
        options.addView(copy("Oyunda İngilizce altyazıyı ve konuşmacı adlarını aç. Bu görev boyunca okunan altyazılar telefonda saklanır. Görev bitince canlı çeviriyi durdur.", 14))
        listOf("PlayStation Portal" to Source.PORTAL, "PS Remote Play" to Source.MOBILE, "PS5 ve TV" to Source.PS5)
            .forEach { (label, source) ->
                options.addBelow(press(label, Miami.aqua) {
                    dialog.dismiss()
                    startActivity(connectionIntent(this, source)
                        .putExtra(GtaMissionRecording.EXTRA_MISSION_ID, mission.id)
                        .putExtra(GtaMissionRecording.EXTRA_MISSION_TITLE, title))
                }, 7)
            }
        dialog.show()
    }

    private fun detailSurface(accent: Int): GradientDrawable =
        blend(mix(Miami.surface, accent, 0.12f), Miami.surface, 22).apply {
            setStroke(dp(1), mix(Miami.edge, accent, 0.32f))
        }

    private fun giverPanel(name: String) = row().apply {
        background = detailSurface(Miami.aqua)
        setPadding(dp(13), dp(12), dp(13), dp(12))
        addView(missionAvatar("$name profil resmi", catalog.portrait(name), 62), LinearLayout.LayoutParams(dp(62), dp(62)))
        addView(stack().apply {
            addView(copy("Görevi veren", 12, Miami.aqua, true))
            addBelow(copy(name, 19, Palette.white, true), 2)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
    }

    private fun LinearLayout.addSceneMedia(scene: GtaMissionScene) {
        scene.images.chunked(2).forEachIndexed { row, photos -> addBelow(missionStills(photos, scene.title, row * 2), 8) }
        scene.source?.let { url ->
            addBelow(press(language.local("Sahne kaynağını aç", "Open scene source")) { openBrowserLink(url) }, 6)
        }
    }

    private fun missionStills(photos: List<GtaMissionImage>, title: String, offset: Int = 0): LinearLayout {
        return row().apply {
            gravity = Gravity.TOP
            photos.forEachIndexed { index, photo ->
                val caption = photo.captionIn(language)
                val number = offset + index + 1
                val label = caption ?: language.local("$title · Görüntü $number", "$title · Image $number")
                val frame = stack().apply {
                    val picture = still(photo, if (photos.size == 1) 150 else 108)
                    picture.contentDescription = label
                    picture.isFocusable = true
                    picture.setOnClickListener { showStill(photo, label) }
                    addView(picture)
                    caption?.let { addBelow(copy(it, 12, Palette.quiet), 5) }
                }
                addView(frame, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
        }
    }

    private fun still(photo: GtaMissionImage, height: Int, large: Boolean = false) = FrameLayout(this).apply {
        minimumHeight = dp(height)
        background = filled(Miami.surface, 16, Miami.edge)
        clipToOutline = true
        val placeholder = glyph(R.drawable.ic_capture_album, Miami.lavender)
        addView(placeholder, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        addView(context.missionImageView(photo, if (large) 1280 else 640) {
            placeholder.visibility = View.INVISIBLE
        }.apply {
            if (large) scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, dp(height)))
    }

    private fun showStill(photo: GtaMissionImage, label: String) {
        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(still(photo, 260, large = true))
            .setPositiveButton("Kapat", null)
            .show()
    }

    private fun prerequisite(previous: GtaMission) = row().apply {
        val title = previous.nameIn(language)
        val gradient = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFFFBC94.toInt(), 0xFFFFABD2.toInt(), 0xFF9FDCF5.toInt())).apply {
            cornerRadius = dp(22).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(0x368E286B), gradient, null)
        setPadding(dp(14), dp(13), dp(12), dp(13))
        isFocusable = true
        minimumHeight = dp(72)
        contentDescription = title
        setOnClickListener { openMission(previous, language) }
        addView(glyph(R.drawable.ic_arrow_back, Miami.night).apply {
            background = filled(0x30FFFFFF, 14)
            rotation = 180f
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(stack().apply {
            addView(copy("Önce tamamlanacak görev", 12, 0xFF4E294E.toInt(), true))
            addBelow(copy(title, 19, Miami.night, true), 1)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
    }

    private fun loadTexts() {
        val app = applicationContext
        worker.execute {
            val result = runCatching { GtaMissionStore.texts(app, catalog, mission) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                rows = result.getOrNull()
                packError = result.exceptionOrNull()?.message
                if (pane != Pane.CONVERSATIONS) render()
            }
        }
    }

    private fun loadConversations() {
        if (dialogueSync == DialogueSync.CHECKING) return
        dialogueSync = DialogueSync.CHECKING
        val app = applicationContext
        val missionId = mission.id
        worker.execute {
            fun resolve(transcript: GtaMissionDialogue?) = transcript?.resolveSubtitles { keys ->
                ImportedGamePack.gtaTranslations(app, keys)
            }
            val cached = runCatching { GtaMissionStore.dialogue(app, missionId) }
            val preview = runCatching { resolve(cached.getOrNull()) }.getOrElse { cached.getOrNull() }
            val local = runCatching { GtaMissionRecording.dialogue(app, missionId) }.getOrNull()
            val visible = preview?.takeIf { it.readableLines.isNotEmpty() } ?: local
            if (visible != null) {
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    dialogue = visible
                    if (pane == Pane.CONVERSATIONS) render()
                }
            }

            val refresh = runCatching { GtaMissionStore.refreshPublished(app) }
            val result = runCatching {
                val stored = GtaMissionStore.dialogue(app, missionId)
                val reload = stored != cached.getOrNull()
                val selected = if (reload) stored else preview
                val needsSubtitles = !ImportedGamePack.hasGta(app) &&
                    selected?.lines?.any { it.subtitleKey != null && it.turkish == null } == true
                if (needsSubtitles) ImportedGamePack.ensureGta(app)
                if (reload || needsSubtitles) resolve(selected) else selected
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val updated = result.getOrElse { preview }?.takeIf { it.readableLines.isNotEmpty() } ?: local
                val redraw = dialogue != updated || updated == null
                dialogue = updated
                dialogueSync = if (refresh.isSuccess && result.isSuccess) DialogueSync.READY else DialogueSync.FAILED
                if (pane == Pane.CONVERSATIONS && redraw) render()
            }
        }
    }

    private fun showPackText(version: Int, conversations: Boolean) {
        if (rows == null && !conversations) {
            val issue = packError
            body.addBelow(copy(issue ?: "Görev metinleri yükleniyor…"), 12)
            if (issue != null) body.addBelow(press("Yeniden dene") { loadTexts() }, 8)
            return
        }
        val available = rows.orEmpty()
        val transcript = dialogue.takeIf { conversations }
        val fullConversation = conversations && conversationView == ConversationView.ALL
        val entries = when {
            conversations -> transcript?.inReadingOrder().orEmpty()
            else -> available.filter { it.type != GtaTextType.DIALOGUE && it.type != GtaTextType.GUIDE }
        }
        if (entries.isEmpty()) {
            if (conversations) {
                val status = when (dialogueSync) {
                    DialogueSync.IDLE, DialogueSync.CHECKING -> "Görev konuşmaları yükleniyor…"
                    DialogueSync.FAILED -> "Görev konuşmaları şu anda yüklenemedi."
                    DialogueSync.READY -> "Bu görev için konuşma kaydı bulunmuyor."
                }
                body.addBelow(copy(status).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }, 12)
                if (dialogueSync == DialogueSync.READY || dialogueSync == DialogueSync.FAILED) {
                    body.addBelow(press("Bu görevde canlı altyazı", Miami.aqua) { chooseRecordingConnection() }, 8)
                }
            } else body.addBelow(miamiSection("Hedefler", "Bu görev için kayıtlı hedef bulunmuyor.", Miami.lavender), 10)
            return
        }
        val scenes = (transcript?.scenes ?: mission.scenes).associateBy { it.id }
        val list = if (fullConversation) textCard(Miami.aqua).also { body.addBelow(it, 12) } else body
        val pending = mutableListOf<Triple<GtaMissionText, String?, TextView>>()
        entries.take(visibleRows).forEachIndexed { index, line ->
            val scene = scenes[line.sceneId]
            val previousLine = entries.getOrNull(index - 1)
            if (conversations && !fullConversation && (index == 0 || line.sceneId != previousLine?.sceneId)) {
                if (scene != null) {
                    body.addBelow(textCard(Miami.aqua).apply {
                        addView(sceneHeading(scene.title, scene.environment, Miami.aqua))
                        addSceneMedia(scene)
                    }, 12)
                } else if (scenes.isNotEmpty()) {
                    body.addBelow(copy(language.local("Sahnesi belirtilmemiş", "Scene unspecified"), 14, Miami.aqua, true), 12)
                }
            }
            val translated = line.turkish ?: translatedLines[line]
            val accent = if (conversations) Miami.aqua else if (index % 2 == 0) Miami.coral else Miami.lavender
            val lineView = if (fullConversation) stack() else textCard(accent)
            list.addBelow(lineView.apply {
                val title = if (conversations) line.speaker else line.type.label
                title?.let { addView(copy(it, 14, accent, true)) }
                val lineText = copy(translated ?: line.english.orEmpty(), 16, Palette.white).apply { setTextIsSelectable(true) }
                addBelow(lineText, if (title == null) 0 else 5)
                val timing = line.startMs?.takeUnless { fullConversation }?.let { start ->
                    val first = formatMissionTime(start)
                    line.endMs?.let { end -> "$first–${formatMissionTime(end)}" } ?: first
                }
                val previous = previousLine?.takeIf { it.sceneId == line.sceneId }?.english
                if (translated == null && line.english != null && line !in attemptedTranslations) {
                    pending.add(Triple(line, previous, lineText))
                }
                val environment = scene?.environment?.takeUnless { conversations }
                val context = line.context?.takeUnless { conversations }
                listOfNotNull(environment, context, timing).forEach { addBelow(copy(it, 13), 4) }
            }, if (fullConversation && index == 0) 0 else 8)
        }
        if (entries.size > visibleRows) body.addBelow(press("Daha fazla metin göster", Miami.lavender) {
            visibleRows += PAGE_SIZE
            render()
        }, 9)
        translateRows(version, pending.iterator())
    }

    private fun showSceneSummaries() {
        val summaries = mission.conversationSummaries
        if (summaries.isEmpty()) return
        body.addBelow(copy("Sahne özetleri", 19, Palette.white, true).apply { isAccessibilityHeading = true }, 13)
        val colors = listOf(Miami.aqua, Miami.coral, Miami.lavender)
        summaries.forEachIndexed { index, summary ->
            val accent = colors[index % colors.size]
            body.addBelow(textCard(accent).apply {
                addView(sceneHeading(summary.title, summary.location, accent))
                if (summary.speakers.isNotEmpty()) {
                    addBelow(copy(summary.speakers.joinToString(" · "), 13, accent, true).apply {
                        background = filled(mix(Miami.surface, accent, 0.15f), 10)
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                    }, 9)
                }
                addBelow(copy(summary.text, 16, Palette.white).apply { setTextIsSelectable(true) }, 9)
            }, 9)
        }
    }

    private fun sceneHeading(title: String, location: String?, accent: Int) = row().apply {
        gravity = Gravity.TOP
        addView(glyph(R.drawable.ic_mission_text, accent).apply {
            background = filled(mix(Miami.surface, accent, 0.2f), 10)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }, LinearLayout.LayoutParams(dp(35), dp(35)))
        addView(stack().apply {
            addView(copy(title, 17, Palette.white, true).apply { isAccessibilityHeading = true })
            location?.let { addBelow(copy(it, 13), 1) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
    }

    private fun textCard(accent: Int) = miamiPanel(accent).apply {
        background = detailSurface(accent)
    }

    private fun translateRows(version: Int, views: Iterator<Triple<GtaMissionText, String?, TextView>>) {
        if (version != renderVersion || isFinishing || isDestroyed || !views.hasNext()) return
        val (line, previous, view) = views.next()
        val original = checkNotNull(line.english)
        val engine = translation ?: GameTranslatorEngine(applicationContext, GameReadingProfile.GTA_6).also { translation = it }
        engine.translate(TranslationInput(original, previousSubtitle = previous)) { reply ->
            if (isFinishing || isDestroyed) return@translate
            val result = reply.text?.takeUnless { it.equals(original, ignoreCase = true) }
            if (result != null) {
                translatedLines[line] = result
                view.text = result
            }
            attemptedTranslations.add(line)
            translateRows(version, views)
        }
    }

    private fun showSources() {
        body.addBelow(copy("Kaynaklar", 18, Palette.white, true).apply { isAccessibilityHeading = true }, 15)
        val links = linkedMapOf<String, String>()
        fun add(url: String?, label: String) {
            if (url != null) links.putIfAbsent(url, label)
        }
        add(mission.source, "Görev kaynağını aç")
        add(mission.textSource?.takeUnless { it.startsWith("https://six.mics.dev/dialogue") },
            "Görev metinlerini kaynakta aç")
        add(mission.gallerySource, "Görev görüntülerinin kaynağını aç")
        add(mission.profileSource, "Karakteri kaynakta gör")
        add(mission.gameDataSource, "Oyun verisindeki kaydı aç")
        add("https://github.com/psviethoa/gta6-text-en", "GitHub İngilizce metin kaynağı")
        links.forEach { (url, label) -> body.addBelow(press(label) { openBrowserLink(url) }, 7) }
    }

    private fun formatMissionTime(ms: Long) =
        String.format(Locale.ROOT, "%d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000)

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("missionPane", pane.name)
        outState.putString("missionConversationView", conversationView.name)
        outState.putInt("missionRows", visibleRows)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        translation?.close()
        worker.shutdown()
        super.onDestroy()
    }

    private companion object { const val PAGE_SIZE = 34 }
}
