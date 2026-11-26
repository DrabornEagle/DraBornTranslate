// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

class GtaMissionSearchActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var search: EditText
    private lateinit var feedback: TextView
    private lateinit var results: LinearLayout
    private lateinit var install: Button
    private var catalog: GtaMissionCatalog? = null
    private var visibleCount = PAGE_SIZE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = contentPage("Görev ara", Palette.purple)
        content.addBelow(featureHeader("GTA 6 görevleri", "Türkçe veya İngilizce görev adıyla ara.",
            R.drawable.ic_game_package, Palette.purple))
        search = EditText(this).apply {
            hint = "Görev adı"
            setHintTextColor(Palette.quiet)
            setTextColor(Palette.white)
            setTextSize(16f)
            background = filled(Palette.surface, 15, Palette.border)
            setPadding(dp(15), dp(8), dp(15), dp(8))
            minHeight = dp(50)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            filters = arrayOf(InputFilter.LengthFilter(100))
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
                val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                keyboard.hideSoftInputFromWindow(view.windowToken, 0)
                true
            }
        }
        content.addBelow(search, 14)
        feedback = copy("", 14, Palette.quiet).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addBelow(feedback, 12)
        install = press("Oyun paketlerini aç", Palette.purple) {
            startActivity(Intent(this, GamePacksActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }.apply { visibility = View.GONE }
        content.addBelow(install, 10)
        results = stack()
        content.addBelow(results)
        content.isFocusableInTouchMode = true
        content.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext
        if (catalog == null) feedback.text = "Görevler yükleniyor…"
        worker.execute {
            val loaded = runCatching { GtaMissionStore.catalog(app) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                catalog = loaded.getOrNull()
                install.visibility = if (catalog == null) View.VISIBLE else View.GONE
                search.isEnabled = catalog != null
                if (catalog == null) {
                    results.removeAllViews()
                    feedback.text = if (loaded.isFailure) "Görev paketi açılamadı. Oyun paketlerinden yeniden indir."
                        else "Çevrimdışı arama için GTA 6 paketini indir."
                } else renderResults()
            }
        }
    }

    private fun renderResults() {
        val available = catalog ?: return
        val matches = available.search(search.text.toString())
        feedback.text = if (matches.isEmpty()) "Bu adla eşleşen görev bulunamadı."
            else "${matches.size} görev · ${available.gameBuild}"
        results.removeAllViews()
        matches.take(visibleCount).forEach { mission ->
            val description = listOfNotNull(mission.englishTitle?.takeUnless { it == mission.title }, mission.category)
                .joinToString(" · ")
            results.addBelow(navTile(mission.title, description, R.drawable.ic_game_package, Palette.purple) {
                startActivity(Intent(this, GtaMissionDetailsActivity::class.java).putExtra("mission", mission.id))
            }, 8)
        }
        if (matches.size > visibleCount) results.addBelow(press("Daha fazla görev göster") {
            visibleCount += PAGE_SIZE
            renderResults()
        }, 10)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", search.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private companion object { const val PAGE_SIZE = 30 }
}

class GtaMissionDetailsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var infoButton: Button
    private lateinit var textButton: Button
    private var showingTexts = false
    private var lines: List<GtaMissionText>? = null
    private var textPage = 0
    private var textLoadInProgress = false
    private var translation: GameTranslatorEngine? = null
    private val translations = mutableMapOf<Int, String>()
    private val pendingTranslations = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = contentPage("Görev detayı", Palette.purple)
        val loading = copy("Görev bilgileri yükleniyor…")
        content.addBelow(loading, 8)
        val id = intent.getStringExtra("mission")
        val app = applicationContext
        worker.execute {
            val selected = runCatching {
                val catalog = GtaMissionStore.catalog(app) ?: return@runCatching null
                val mission = catalog.missions.firstOrNull { it.id == id } ?: return@runCatching null
                catalog to mission
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (selected == null) {
                    loading.text = "Görev açılamadı. Görev aramasına dönüp tekrar dene."
                    return@runOnUiThread
                }
                content.removeView(loading)
                val (catalog, mission) = selected
                content.addBelow(featureHeader(mission.title, mission.category, R.drawable.ic_game_package, Palette.purple))
                mission.englishTitle?.takeUnless { it == mission.title }?.let { content.addBelow(copy(it), 10) }
                infoButton = press("Görev bilgileri") { showInfo(catalog, mission) }
                textButton = press("Görev metinleri") { showTexts(catalog, mission) }
                content.addBelow(actionRow(infoButton, textButton), 12)
                body = stack()
                content.addBelow(body, 12)
                if (savedInstanceState?.getBoolean("showingTexts") == true) showTexts(catalog, mission) else showInfo(catalog, mission)
            }
        }
    }

    private fun showInfo(catalog: GtaMissionCatalog, mission: GtaMission) {
        selectSection(texts = false)
        body.addBelow(section("Genel bilgiler", mission.summary))
        mission.location?.let { body.addBelow(section("Konum ve ortam", it), 10) }
        if (mission.characters.isNotEmpty()) body.addBelow(section("Karakterler", mission.characters.joinToString(", ")), 10)
        mission.scenes.forEach { scene ->
            val detail = listOfNotNull(scene.environment, scene.context).joinToString("\n\n")
            body.addBelow(section(scene.title, detail), 10)
        }
        if (mission.prerequisites.isNotEmpty()) body.addBelow(copy("Önce tamamlanacak görev", 15, Palette.purple, true), 10)
        catalog.missions.filter { it.id in mission.prerequisites }.forEach { prerequisite ->
            body.addBelow(press(prerequisite.title) {
                startActivity(Intent(this, GtaMissionDetailsActivity::class.java).putExtra("mission", prerequisite.id))
            }, 8)
        }
        mission.source?.let { body.addBelow(press("Görev kaynağını aç") { openSource(it) }, 12) }
    }

    private fun showTexts(catalog: GtaMissionCatalog, mission: GtaMission) {
        selectSection(texts = true)
        if (mission.textCount == 0) {
            body.addBelow(copy("Bu görev için Türkçe metin kaydı henüz eklenmedi."))
            val label = if (mission.textSource == null) "GTA VI Explorer’da diyalog ara" else "Görev metinlerini kaynakta aç"
            body.addBelow(press(label) { openSource(mission.textSource ?: "https://six.mics.dev/dialogue") }, 10)
            return
        }
        lines?.let {
            renderTexts(it, mission)
            return
        }
        body.addBelow(copy("Görev metinleri yükleniyor…"))
        if (textLoadInProgress) return
        textLoadInProgress = true
        val app = applicationContext
        worker.execute {
            val loaded = runCatching { GtaMissionStore.texts(app, catalog, mission) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                textLoadInProgress = false
                lines = loaded.getOrNull()
                if (!showingTexts) return@runOnUiThread
                val entries = lines
                if (entries != null) renderTexts(entries, mission) else {
                    body.removeAllViews()
                    body.addBelow(copy(loaded.exceptionOrNull()?.message ?: "Görev metinleri açılamadı."))
                    body.addBelow(press("Yeniden dene") { showTexts(catalog, mission) }, 10)
                }
            }
        }
    }

    private fun renderTexts(entries: List<GtaMissionText>, mission: GtaMission) {
        body.removeAllViews()
        val pageSize = 30
        val start = textPage * pageSize
        val end = minOf(start + pageSize, entries.size)
        val scenes = mission.scenes.associateBy { it.id }
        val pageControls = actionRow(
            press("Önceki metinler") { textPage--; renderTexts(entries, mission) }.apply { isEnabled = start > 0 },
            press("Sonraki metinler") { textPage++; renderTexts(entries, mission) }.apply { isEnabled = end < entries.size },
        )
        body.addBelow(copy("${start + 1}–$end / ${entries.size} metin", 14, Palette.mint, true))
        mission.textSource?.let { url ->
            body.addBelow(press("Görev metinlerini kaynakta aç") { openSource(url) }, 8)
        }
        if (entries.size > pageSize) body.addBelow(pageControls, 8)
        entries.subList(start, end).forEachIndexed { offset, line ->
            val index = start + offset
            val scene = scenes[line.sceneId]
            val title = listOfNotNull(line.type.label, line.speaker, scene?.title).joinToString(" · ")
            val time = line.startMs?.let { value ->
                val range = line.endMs?.let { "${formatTime(value)}–${formatTime(it)}" } ?: formatTime(value)
                "Sahne zamanı $range"
            }
            val translated = line.turkish ?: translations[index]
            body.addBelow(section(title, translated ?: line.english.orEmpty()).apply {
                if (translated == null && line.english != null) {
                    val running = index in pendingTranslations
                    addBelow(press(if (running) "Çevriliyor…" else "Türkçeye çevir", Palette.mint) {
                        translateLine(entries, mission, index, line.english)
                    }.apply { isEnabled = !running }, 8)
                }
                listOfNotNull(line.english?.takeIf { translated != null }, scene?.environment, line.context, time)
                    .forEach { addBelow(copy(it, 13), 5) }
            }, 8)
        }
    }

    private fun translateLine(entries: List<GtaMissionText>, mission: GtaMission, index: Int, english: String) {
        if (!pendingTranslations.add(index)) return
        val line = entries[index]
        val previous = entries.getOrNull(index - 1)?.takeIf {
            it.type == GtaTextType.DIALOGUE && it.sceneId == line.sceneId && line.sceneId != null
        }?.english
        val reader = translation ?: GameTranslatorEngine(applicationContext, GameReadingProfile.GTA_6).also { translation = it }
        renderTexts(entries, mission)
        reader.translate(TranslationInput(english, previousSubtitle = previous)) { result ->
            pendingTranslations.remove(index)
            if (isDestroyed || isFinishing) return@translate
            val turkish = result.text
            if (turkish != null) translations[index] = turkish
            else Toast.makeText(this, "Metin çevrilemedi. Yeniden dene.", Toast.LENGTH_SHORT).show()
            if (showingTexts) renderTexts(entries, mission)
        }
    }

    private fun selectSection(texts: Boolean) {
        showingTexts = texts
        listOf(infoButton, textButton).forEach { button ->
            val selected = if (button === textButton) texts else !texts
            button.isSelected = selected
            button.isClickable = !selected
            button.background = if (selected) filled(Palette.purple, 16) else filled(Palette.surface, 16, Palette.border)
            button.setTextColor(if (selected) Palette.background else Palette.white)
        }
        body.removeAllViews()
    }

    private fun section(title: String, detail: String) = stack().apply {
        background = filled(Palette.surface, 16, Palette.border)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addBelow(copy(title, 15, Palette.purple, true).apply { isAccessibilityHeading = true })
        if (detail.isNotBlank()) addBelow(copy(detail, 16, Palette.white).apply { setTextIsSelectable(true) }, 6)
    }

    private fun openSource(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Bağlantıyı açacak tarayıcı bulunamadı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(ms: Long) = String.format(Locale.ROOT, "%d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000)

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("showingTexts", showingTexts)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        translation?.close()
        worker.shutdown()
        super.onDestroy()
    }
}
