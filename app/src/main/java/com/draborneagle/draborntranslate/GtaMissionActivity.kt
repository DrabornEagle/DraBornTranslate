// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
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

private fun Context.missionAvatar(giver: String?, portrait: GtaMissionImage?, size: Int) = FrameLayout(this).apply {
    background = filled(mix(Miami.surface, Miami.aqua, .16f), size / 2, mix(Miami.edge, Miami.aqua, .4f))
    clipToOutline = true
    val placeholder = glyph(R.drawable.ic_mission_character, Miami.aqua)
    addView(placeholder, FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER))
    if (portrait != null) {
        addView(context.missionImageView(portrait, 240) { placeholder.visibility = View.INVISIBLE }.apply {
            contentDescription = "$giver profil resmi"
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
    private var language = GtaMissionSearchLanguage.TURKISH
    private var catalog: GtaMissionCatalog? = null
    private var visibleCount = PAGE_SIZE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = miamiPage("Görev Ara", Miami.pink)
        content.addBelow(miamiHero("GTA 6 görevleri", "Oyunda gördüğün görev adıyla ara.", Miami.pink))
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
                install.visibility = if (catalog == null) View.VISIBLE else View.GONE
                if (catalog != null) renderResults() else {
                    results.removeAllViews()
                    feedback.text = if (loaded.isFailure) "Görev paketi açılamadı. Oyun paketlerinden yeniden indir."
                        else "Görev rehberini Oyun Paketleri’nden indir."
                }
            }
        }
    }

    private fun selectLanguage(selected: GtaMissionSearchLanguage) {
        language = selected
        getPreferences(MODE_PRIVATE).edit().putString(SEARCH_LANGUAGE, selected.name).apply()
        visibleCount = PAGE_SIZE
        search.hint = language.searchHint
        turkish.miamiChoice(language == GtaMissionSearchLanguage.TURKISH, Miami.pink)
        english.miamiChoice(language == GtaMissionSearchLanguage.ENGLISH, Miami.aqua)
        renderResults()
    }

    private fun renderResults() {
        val available = catalog ?: return
        val query = search.text.toString()
        val matches = available.search(query, language)
        feedback.text = if (matches.isEmpty()) "Bu adla eşleşen görev bulunamadı." else "${matches.size} görev"
        results.removeAllViews()
        matches.take(visibleCount).forEach { mission ->
            results.addBelow(missionTile(mission, query), 8)
        }
        if (matches.size > visibleCount) results.addBelow(press("Daha fazla görev göster") {
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
        val alternate = secondary ?: mission.code.takeIf { mission.englishTitle == null && mapLabel == null }
        background = RippleDrawable(ColorStateList.valueOf(0x28FFFFFF),
            filled(Miami.surface, 20, Miami.edge), null)
        setPadding(dp(12), dp(11), dp(10), dp(11))
        minimumHeight = dp(78)
        isFocusable = true
        contentDescription = title
        setOnClickListener { openMission(mission, language) }
        addView(missionAvatar(mission.giver, catalog?.portrait(mission.giver), 53),
            LinearLayout.LayoutParams(dp(53), dp(53)))
        addView(stack().apply {
            addView(copy(title, 18, Palette.white, true))
            if (mapLabel != null) addBelow(copy(mapLabel, 13, Miami.lavender, true), 2)
            if (alternate != null) addBelow(copy(alternate, 13), 2)
            addBelow(copy(listOfNotNull(mission.giver, mission.category).joinToString(" · "), 12), 3)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(11) })
        addView(glyph(R.drawable.ic_disclosure, Miami.pink).apply { rotation = -90f },
            LinearLayout.LayoutParams(dp(18), dp(18)))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", search.text.toString())
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

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var infoButton: Button
    private lateinit var textButton: Button
    private lateinit var catalog: GtaMissionCatalog
    private lateinit var mission: GtaMission
    private val language by lazy { GtaMissionSearchLanguage.fromName(intent.getStringExtra(SEARCH_LANGUAGE)) }
    private var pane = Pane.INFO
    private var visibleRows = PAGE_SIZE
    private var rows: List<GtaMissionText>? = null
    private var packError: String? = null
    private var translation: GameTranslatorEngine? = null
    private val translatedLines = mutableMapOf<GtaMissionText, String>()
    private val attemptedTranslations = mutableSetOf<GtaMissionText>()
    private var renderVersion = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pane = Pane.entries.firstOrNull { it.name == savedInstanceState?.getString("missionPane") } ?: Pane.INFO
        visibleRows = savedInstanceState?.getInt("missionRows", PAGE_SIZE) ?: PAGE_SIZE
        val content = miamiPage("Görev Detayı", Miami.pink)
        val loading = copy("Görev yükleniyor…")
        content.addBelow(loading)
        val app = applicationContext
        val id = intent.getStringExtra(MISSION_EXTRA)
        worker.execute {
            val selected = runCatching {
                val pack = GtaMissionStore.catalog(app) ?: return@runCatching null
                val item = pack.missions.firstOrNull { it.id == id } ?: return@runCatching null
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
            }
        }
    }

    private fun select(target: Pane) {
        if (pane == target) return
        pane = target
        visibleRows = PAGE_SIZE
        render()
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
            val category = copy(mission.category, 12, Miami.pink, true).apply {
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
        infoButton.miamiChoice(pane == Pane.INFO, Miami.pink)
        textButton.miamiChoice(pane != Pane.INFO, Miami.aqua)
        if (pane == Pane.INFO) {
            showInfo()
            return
        }
        val options = actionRow(
            press("Konuşmalar") { select(Pane.CONVERSATIONS) }.apply {
                miamiChoice(pane == Pane.CONVERSATIONS, Miami.aqua)
            },
            press("Hedefler/Kaynaklar") { select(Pane.TARGETS) }.apply {
                miamiChoice(pane == Pane.TARGETS, Miami.coral)
            },
        )
        body.addBelow(options)
        showPackText(version, conversations = pane == Pane.CONVERSATIONS)
        if (pane == Pane.TARGETS) showSources()
    }

    private fun showInfo() {
        body.addBelow(miamiSection("Genel bilgiler", mission.summary, Miami.pink).apply {
            background = detailSurface(Miami.pink)
            mission.location?.let { addBelow(copy(it, 14, Miami.aqua, true), 10) }
        })
        if (mission.mapLabels.isNotEmpty()) {
            body.addBelow(miamiSection("Oyunda görünen adlar", mission.mapLabels.joinToString("\n"), Miami.coral), 8)
        }
        mission.giver?.let { body.addBelow(giverPanel(it), 10) }
        if (mission.previews.size > 1) body.addBelow(missionStills(), 10)
        if (mission.characters.isNotEmpty()) {
            body.addBelow(miamiSection("Görevde görülenler", mission.characters.joinToString(", "), Miami.lavender), 10)
        }
        val notes = rows.orEmpty().filter { it.type == GtaTextType.GUIDE && it.sceneId != null }.groupBy { it.sceneId }
        if (mission.scenes.isNotEmpty()) body.addBelow(copy("Sahneler", 18, Palette.white, true).apply {
            isAccessibilityHeading = true
        }, 15)
        mission.scenes.forEachIndexed { index, scene ->
            val accent = if (index % 2 == 0) Miami.coral else Miami.aqua
            val description = scene.context ?: notes[scene.id]?.firstNotNullOfOrNull { it.turkish }?.takeUnless {
                it == mission.summary
            }
            body.addBelow(row().apply {
                background = detailSurface(accent)
                setPadding(dp(13), dp(12), dp(15), dp(12))
                addView(View(context).apply { background = filled(accent, 4) },
                    LinearLayout.LayoutParams(dp(4), dp(35)).apply { gravity = Gravity.TOP; topMargin = dp(3) })
                addView(stack().apply {
                    addView(copy(scene.title, 17, accent, true))
                    scene.environment?.let { addBelow(copy(it, 13, Palette.quiet), 3) }
                    description?.let { addBelow(copy(it, 15, Palette.white), 6) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            }, 8)
        }
        mission.prerequisites.mapNotNull { id -> catalog.missions.firstOrNull { it.id == id } }.forEach { previous ->
            body.addBelow(prerequisite(previous), 10)
        }
    }

    private fun detailSurface(accent: Int): GradientDrawable =
        blend(mix(Miami.surface, accent, 0.12f), Miami.surface, 22).apply {
            setStroke(dp(1), mix(Miami.edge, accent, 0.32f))
        }

    private fun giverPanel(name: String) = row().apply {
        background = detailSurface(Miami.aqua)
        setPadding(dp(13), dp(12), dp(13), dp(12))
        addView(missionAvatar(name, catalog.portrait(name), 62), LinearLayout.LayoutParams(dp(62), dp(62)))
        addView(stack().apply {
            addView(copy("Görevi veren", 12, Miami.aqua, true))
            addBelow(copy(name, 19, Palette.white, true), 2)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
    }

    private fun missionStills() = stack().apply {
        addView(HorizontalScrollView(this@GtaMissionDetailsActivity).apply {
            isHorizontalScrollBarEnabled = false
            val width = if (mission.previews.size == 2) 264 else 192
            addView(row().apply {
                mission.previews.drop(1).forEach { photo ->
                    addView(stack().apply {
                        val label = photo.caption ?: "${mission.nameIn(language)} görev görüntüsü"
                        isFocusable = true
                        contentDescription = label
                        setOnClickListener { openMissionSource(photo.url) }
                        addView(FrameLayout(context).apply {
                            background = filled(Miami.surface, 17, Miami.edge)
                            clipToOutline = true
                            val placeholder = glyph(R.drawable.ic_capture_album, Miami.lavender)
                            addView(placeholder, FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER))
                            addView(context.missionImageView(photo, 580) { placeholder.visibility = View.INVISIBLE },
                                FrameLayout.LayoutParams(-1, -1))
                        }, LinearLayout.LayoutParams(-1, dp(if (width == 264) 145 else 108)))
                        photo.caption?.let { addBelow(copy(it, 12, Palette.quiet), 4) }
                    }, LinearLayout.LayoutParams(dp(width), -2).apply { marginEnd = dp(8) })
                }
            })
        })
        mission.gallerySource?.let { gallery ->
            val source = if (gallery.startsWith("https://www.gtabase.com/")) "GTABase" else "PowerPyx"
            addBelow(copy("Görsel kaynağı · $source", 12, Miami.lavender).apply {
                setPadding(dp(2), dp(10), dp(2), dp(8))
                setOnClickListener { openMissionSource(gallery) }
                isFocusable = true
            }, 1)
        }
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
                render()
            }
        }
    }

    private fun showPackText(version: Int, conversations: Boolean) {
        val available = rows
        if (available == null) {
            val issue = packError
            body.addBelow(copy(issue ?: "Görev metinleri yükleniyor…"), 12)
            if (issue != null) body.addBelow(press("Yeniden dene") { loadTexts() }, 8)
            return
        }
        val entries = available.filter {
            if (conversations) it.type == GtaTextType.DIALOGUE
            else it.type != GtaTextType.DIALOGUE && it.type != GtaTextType.GUIDE
        }
        if (entries.isEmpty()) {
            if (conversations) {
                body.addBelow(miamiSection("Türkçe döküm", "Bu görevin Türkçe diyalog dökümü görev paketinde bulunmuyor.", Miami.aqua), 11)
                mission.dialogueSource?.let { url ->
                    body.addBelow(press("Görevin diyalog arşivini aç", Miami.aqua) { openMissionSource(url) }, 8)
                }
            } else body.addBelow(copy("Bu göreve ait paket hedefi bulunmuyor."), 12)
            return
        }
        val scenes = mission.scenes.associateBy { it.id }
        val pending = mutableListOf<Triple<GtaMissionText, String?, TextView>>()
        entries.take(visibleRows).forEachIndexed { index, line ->
            val scene = scenes[line.sceneId]
            val translated = line.turkish ?: translatedLines[line]
            val title = listOfNotNull(line.speaker, scene?.title).joinToString(" · ").ifBlank { line.type.label }
            body.addBelow(miamiSection(title, "", if (conversations) Miami.aqua else Miami.coral).apply {
                val lineText = copy(translated ?: line.english.orEmpty(), 16, Palette.white).apply { setTextIsSelectable(true) }
                addBelow(lineText, 5)
                val timing = line.startMs?.let { start ->
                    val first = formatMissionTime(start)
                    line.endMs?.let { end -> "$first–${formatMissionTime(end)}" } ?: first
                }
                val previous = entries.getOrNull(index - 1)?.takeIf { it.sceneId == line.sceneId }?.english
                if (translated == null && line.english != null && line !in attemptedTranslations) {
                    pending.add(Triple(line, previous, lineText))
                }
                listOfNotNull(scene?.environment, line.context, timing).forEach { addBelow(copy(it, 13), 4) }
            }, 8)
        }
        if (entries.size > visibleRows) body.addBelow(press("Daha fazla metin göster") {
            visibleRows += PAGE_SIZE
            render()
        }, 9)
        translateRows(version, pending.iterator())
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
        add(mission.dialogueSource, "Görevin diyalog arşivini aç")
        add(mission.textSource, "Görev metinlerini kaynakta aç")
        add(mission.gallerySource, "Görev görüntülerinin kaynağını aç")
        add(mission.profileSource, "Karakteri kaynakta gör")
        add(mission.gameDataSource, "Oyun verisindeki kaydı aç")
        if (mission.dialogueSource == null) add("https://six.mics.dev/dialogue", "GTA VI Explorer’da diyalog ara")
        add("https://github.com/psviethoa/gta6-text-en", "GitHub İngilizce metin kaynağı")
        links.forEach { (url, label) -> body.addBelow(press(label) { openMissionSource(url) }, 7) }
    }

    private fun formatMissionTime(ms: Long) =
        String.format(Locale.ROOT, "%d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000)

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("missionPane", pane.name)
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
