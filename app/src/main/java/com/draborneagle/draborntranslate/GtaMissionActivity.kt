// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.RippleDrawable
import android.net.Uri
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
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

internal const val MISSION_EXTRA = "mission"
internal const val MISSION_SCENE_EXTRA = "missionScene"
private const val SEARCH_LANGUAGE = "mission_search_language"

private fun Context.openMission(mission: GtaMission, language: GtaMissionSearchLanguage) {
    startActivity(Intent(this, GtaMissionDetailsActivity::class.java)
        .putExtra(MISSION_EXTRA, mission.id)
        .putExtra(SEARCH_LANGUAGE, language.name))
}

private fun Context.missionAvatar(giver: String?, portrait: Bitmap?, size: Int) = FrameLayout(this).apply {
    val colors = listOf(Miami.pink, Miami.aqua, Miami.coral, Miami.lavender)
    val accent = colors[Math.floorMod(giver.orEmpty().hashCode(), colors.size)]
    background = filled(mix(Miami.surface, accent, .23f), size / 2, mix(Miami.edge, accent, .45f))
    clipToOutline = true
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    if (portrait == null) {
        val initials = giver?.split(Regex("\\s+"))?.filter(String::isNotBlank)?.take(2)
            ?.joinToString("") { it.take(1) }?.uppercase(Locale.ROOT) ?: "?"
        addView(copy(initials, if (size > 52) 22 else 16, accent, true), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    } else addView(ImageView(context).apply {
        setImageBitmap(portrait)
        scaleType = ImageView.ScaleType.CENTER_CROP
    }, FrameLayout.LayoutParams(-1, -1))
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
    private var portraits = emptyMap<String, Bitmap>()
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
            val catalogImages = loaded.getOrNull()?.missions.orEmpty().mapNotNull { it.giver }.distinct()
                .mapNotNull { name ->
                    val file = MissionMedia.portrait(app, name)
                    if (!file.isFile) null else MissionMedia.thumbnail(file, 148)?.let { name to it }
                }.toMap()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                catalog = loaded.getOrNull()
                portraits = catalogImages
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
        addView(missionAvatar(mission.giver, mission.giver?.let(portraits::get), 49), LinearLayout.LayoutParams(dp(49), dp(49)))
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
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var infoButton: Button
    private lateinit var textButton: Button
    private lateinit var catalog: GtaMissionCatalog
    private lateinit var mission: GtaMission
    private lateinit var journal: MissionJournalPanel
    private val language by lazy { GtaMissionSearchLanguage.fromName(intent.getStringExtra(SEARCH_LANGUAGE)) }
    private var showingTexts = false
    private var showingTargets = false
    private var textPage = 0
    private var packLines: List<GtaMissionText>? = null
    private var packError: String? = null
    private var portrait: Bitmap? = null
    private var photos = emptyList<Pair<MissionPhoto, Bitmap>>()
    private var galleryScene: String? = null
    private var pendingResult: Pair<Int, Intent>? = null
    private var translation: GameTranslatorEngine? = null
    private val translatedPackLines = mutableMapOf<GtaMissionText, String>()
    private val translating = mutableSetOf<GtaMissionText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showingTexts = savedInstanceState?.getBoolean("texts") == true
        showingTargets = savedInstanceState?.getBoolean("targets") == true
        galleryScene = savedInstanceState?.getString("galleryScene")
        val content = miamiPage("Görev Detayı", Miami.pink)
        val loading = miamiSection("Görev bilgileri", "Yükleniyor…")
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
                if (selected == null) {
                    content.removeView(loading)
                    content.addBelow(miamiSection("Görev açılamadı", "Görev aramasından tekrar seç."))
                    return@runOnUiThread
                }
                catalog = selected.first
                mission = selected.second
                journal = MissionJournalPanel(this, mission, savedInstanceState, worker)
                content.removeView(loading)
                content.addBelow(miamiHero(mission.nameIn(language),
                    listOfNotNull(mission.alternateNameIn(language), mission.category).joinToString(" · "), Miami.pink))
                infoButton = press("Görev bilgileri") { showingTexts = false; renderBody() }
                textButton = press("Görev metinleri") { showingTexts = true; renderBody() }
                content.addBelow(actionRow(infoButton, textButton), 12)
                body = stack()
                content.addBelow(body, 13)
                renderBody()
                loadMedia()
                loadPackLines()
                pendingResult?.let { (code, data) -> handleResult(code, data) }
                pendingResult = null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::journal.isInitialized && showingTexts && !showingTargets) journal.refresh()
    }

    private fun renderBody() {
        body.removeAllViews()
        journal.detach()
        infoButton.miamiChoice(!showingTexts, Miami.pink)
        textButton.miamiChoice(showingTexts, Miami.aqua)
        if (!showingTexts) {
            showInfo()
            return
        }
        val tabs = actionRow(
            press("Konuşmalar") { showingTargets = false; textPage = 0; renderBody() }.apply { miamiChoice(!showingTargets, Miami.aqua) },
            press("Hedefler ve mesajlar") { showingTargets = true; textPage = 0; renderBody() }
                .apply { miamiChoice(showingTargets, Miami.coral) },
        )
        body.addBelow(tabs)
        if (showingTargets) showPackLines(targets = true)
        else {
            val personal = stack()
            body.addBelow(personal, 12)
            journal.attach(personal, packLines.orEmpty().count { it.type == GtaTextType.DIALOGUE })
            showPackLines(targets = false)
        }
        addSources()
    }

    private fun showInfo() {
        body.addBelow(miamiSection("Genel bilgiler", mission.summary))
        mission.location?.let { body.addBelow(miamiSection("Konum", it, Miami.aqua), 10) }
        if (mission.mapLabels.isNotEmpty()) {
            body.addBelow(miamiSection("Oyunda görünen adlar", mission.mapLabels.joinToString("\n"), Miami.coral), 10)
        }
        body.addBelow(giverPanel(), 10)
        if (mission.characters.isNotEmpty()) {
            body.addBelow(miamiSection("Görevde görülenler", mission.characters.joinToString(", ")), 10)
        }
        mission.scenes.forEach { scene ->
            val panel = miamiSection(scene.title, scene.context.orEmpty(), Miami.coral)
            scene.environment?.let { panel.addBelow(copy(it, 13, Miami.aqua), 6) }
            body.addBelow(panel, 8)
        }
        if (mission.prerequisites.isNotEmpty()) body.addBelow(copy("Önce tamamlanacak görev", 15, Miami.pink, true), 12)
        catalog.missions.filter { it.id in mission.prerequisites }.forEach { prerequisite ->
            body.addBelow(press(prerequisite.nameIn(language)) { openMission(prerequisite, language) }, 8)
        }
        body.addBelow(galleryPanel(), 10)
        addSources(includeDialogue = false)
    }

    private fun giverPanel() = miamiPanel(Miami.pink).apply {
        val name = mission.giver
        addView(row().apply {
            addView(missionAvatar(name, portrait, 62), LinearLayout.LayoutParams(dp(62), dp(62)))
            addView(stack().apply {
                addView(copy("Görevi veren", 13, Miami.pink, true))
                addBelow(copy(name ?: "Kaynakta belirtilmemiş", 18, Palette.white, true), 2)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(13) })
        })
        if (name != null) {
            val photo = press(if (portrait == null) "Profil fotoğrafı ekle" else "Profil fotoğrafını değiştir") {
                pickImages(PORTRAIT, multiple = false)
            }
            val source = mission.profileSource
            if (source == null) addBelow(photo, 10)
            else addBelow(actionRow(photo, press("Karakteri kaynakta gör") { openMissionSource(source) }), 10)
        }
    }

    private fun galleryPanel() = miamiPanel(Miami.aqua).apply {
        addView(copy("Oyun görüntüleri", 18, Palette.white, true).apply { isAccessibilityHeading = true })
        if (photos.isNotEmpty()) addBelow(HorizontalScrollView(this@GtaMissionDetailsActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row().apply {
                for ((photo, bitmap) in photos) {
                    val caption = mission.scenes.firstOrNull { it.id == photo.sceneId }?.title ?: "Oyun görüntüsü"
                    addView(stack().apply {
                        isFocusable = true
                        contentDescription = caption
                        setOnClickListener { viewPhoto(photo, caption) }
                        addView(ImageView(context).apply {
                            background = filled(Miami.night, 14, Miami.edge)
                            clipToOutline = true
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setImageBitmap(bitmap)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(dp(156), dp(90)))
                        addBelow(copy(caption, 13), 4)
                    }, LinearLayout.LayoutParams(dp(156), -2).apply { marginEnd = dp(9) })
                }
            })
        }, 9)
        addBelow(press("Oyundan görüntü ekle", Miami.aqua) {
            chooseMissionScene(mission, "Görüntü hangi sahneye ait?") { scene ->
                galleryScene = scene
                pickImages(GALLERY, multiple = true)
            }
        }.apply { isEnabled = photos.size < MissionMedia.MAX_CAPTURES }, 10)
        mission.gallerySource?.let { url ->
            addBelow(press("Görevin kaynak görsellerini aç") { openMissionSource(url) }, 7)
        }
    }

    private fun viewPhoto(photo: MissionPhoto, label: String) {
        worker.execute {
            val image = MissionMedia.thumbnail(photo.file, 1_440)
            runOnUiThread {
                if (image == null || isDestroyed || isFinishing) return@runOnUiThread
                val view = ImageView(this).apply {
                    setImageBitmap(image)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = label
                }
                AlertDialog.Builder(this).setTitle(label).setView(view).setPositiveButton("Kapat", null)
                    .setNeutralButton("Kaldır") { _, _ ->
                        AlertDialog.Builder(this).setTitle("Bu görüntü kaldırılsın mı?")
                            .setNegativeButton("Vazgeç", null).setPositiveButton("Kaldır") { _, _ ->
                                worker.execute {
                                    photo.file.delete()
                                    runOnUiThread { if (!isFinishing && !isDestroyed) loadMedia() }
                                }
                            }.show()
                    }.show()
            }
        }
    }

    private fun loadMedia() {
        val app = applicationContext
        val selected = mission
        worker.execute {
            val avatar = selected.giver?.let { MissionMedia.portrait(app, it) }?.takeIf { it.isFile }
                ?.let { MissionMedia.thumbnail(it, 200) }
            val captures = MissionMedia.photos(app, selected.id).mapNotNull { photo ->
                MissionMedia.thumbnail(photo.file, 200)?.let { photo to it }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                portrait = avatar
                photos = captures
                if (!showingTexts) renderBody()
            }
        }
    }

    private fun loadPackLines() {
        val app = applicationContext
        worker.execute {
            val result = runCatching { GtaMissionStore.texts(app, catalog, mission) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                packLines = result.getOrNull()
                packError = result.exceptionOrNull()?.message
                if (showingTexts) renderBody()
            }
        }
    }

    private fun showPackLines(targets: Boolean) {
        packError?.let {
            body.addBelow(miamiSection("Paket metinleri açılamadı", it), 10)
            body.addBelow(press("Yeniden dene") { loadPackLines() }, 6)
            return
        }
        val entries = packLines?.filter {
            if (targets) it.type != GtaTextType.DIALOGUE && it.type != GtaTextType.GUIDE else it.type == GtaTextType.DIALOGUE
        }
        if (entries.isNullOrEmpty()) {
            if (targets) body.addBelow(copy(if (entries == null) "Paket metinleri yükleniyor…" else
                "Bu görev için pakette hedef veya mesaj kaydı yok."), 12)
            return
        }
        val start = (textPage * PACK_PAGE).coerceAtMost(((entries.size - 1) / PACK_PAGE) * PACK_PAGE)
        val end = minOf(start + PACK_PAGE, entries.size)
        body.addBelow(copy(if (targets) "Paket hedefleri ve mesajlar" else "Paketteki diyaloglar",
            15, Miami.coral, true).apply { isAccessibilityHeading = true }, 13)
        if (entries.size > PACK_PAGE) body.addBelow(actionRow(
            press("Önceki") { textPage = start / PACK_PAGE - 1; renderBody() }.apply { isEnabled = start > 0 },
            press("Sonraki") { textPage = start / PACK_PAGE + 1; renderBody() }.apply { isEnabled = end < entries.size },
        ), 6)
        for (index in start until end) {
            val line = entries[index]
            val scene = mission.scenes.firstOrNull { it.id == line.sceneId }
            val translated = line.turkish ?: translatedPackLines[line]
            val title = listOfNotNull(line.speaker, scene?.title, line.type.label).joinToString(" · ")
            body.addBelow(miamiSection(title, translated ?: line.english.orEmpty(), Miami.coral).apply {
                listOfNotNull(scene?.environment, line.context,
                    line.startMs?.let { missionTimeRange(it, line.endMs) },
                    line.english?.takeIf { translated != null && it != translated }).forEach { addBelow(copy(it, 13), 4) }
                if (translated == null && line.english != null) {
                    val running = line in translating
                    addBelow(press(if (running) "Çevriliyor…" else "Türkçeye çevir", Miami.aqua) {
                        translatePackLine(line, entries.getOrNull(index - 1))
                    }.apply { isEnabled = !running }, 8)
                }
            }, 8)
        }
    }

    private fun translatePackLine(line: GtaMissionText, previous: GtaMissionText?) {
        val original = line.english ?: return
        if (!translating.add(line)) return
        val engine = translation ?: GameTranslatorEngine(applicationContext, GameReadingProfile.GTA_6).also { translation = it }
        val previousText = previous?.english?.takeIf { line.sceneId != null && line.sceneId == previous.sceneId }
        renderBody()
        engine.translate(TranslationInput(original, previousSubtitle = previousText)) { reply ->
            translating.remove(line)
            if (isDestroyed || isFinishing) return@translate
            val result = reply.text?.takeUnless { it.equals(original, ignoreCase = true) }
            if (result != null) translatedPackLines[line] = result
            else Toast.makeText(this, "Türkçe çeviri alınamadı.", Toast.LENGTH_SHORT).show()
            if (showingTexts) renderBody()
        }
    }

    private fun addSources(includeDialogue: Boolean = true) {
        mission.source?.let { body.addBelow(press("Görev kaynağını aç") { openMissionSource(it) }, 10) }
        mission.gameDataSource?.takeUnless { it == mission.source || it == mission.textSource }?.let { url ->
            body.addBelow(press("Oyun verisindeki kaydı aç") { openMissionSource(url) }, 7)
        }
        if (!includeDialogue) return
        val source = mission.textSource
        if (source != null && source != mission.source) {
            body.addBelow(press("Görev metinlerini kaynakta aç") { openMissionSource(source) }, 7)
        }
        if (source == null || !source.startsWith("https://six.mics.dev/dialogue")) {
            body.addBelow(press("GTA VI Explorer’da diyalog ara") {
                openMissionSource("https://six.mics.dev/dialogue")
            }, 7)
        }
        body.addBelow(press("GitHub İngilizce metin kaynağı") {
            openMissionSource("https://github.com/psviethoa/gta6-text-en")
        }, 7)
    }

    @Suppress("DEPRECATION")
    private fun pickImages(code: Int, multiple: Boolean) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }, code)
    }

    @Deprecated("Android activity result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (result != RESULT_OK || data == null) return
        if (::mission.isInitialized) handleResult(code, data) else pendingResult = code to data
    }

    private fun handleResult(code: Int, data: Intent) {
        if (journal.handleResult(code, data)) return
        val uris = buildList<Uri> {
            data.data?.let { add(it) }
            data.clipData?.let { selection -> for (i in 0 until selection.itemCount) add(selection.getItemAt(i).uri) }
        }.distinct()
        if (uris.isEmpty()) return
        val app = applicationContext
        val scene = galleryScene
        worker.execute {
            val message = runCatching {
                when (code) {
                    PORTRAIT -> {
                        mission.giver?.let { MissionMedia.savePortrait(app, it, uris.first()) }
                        "Profil fotoğrafı kaydedildi."
                    }
                    GALLERY -> "${MissionMedia.savePhotos(app, mission.id, scene, uris)} görüntü eklendi."
                    else -> return@execute
                }
            }.getOrElse { it.message ?: "Görüntü kaydedilemedi." }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                loadMedia()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("texts", showingTexts)
        outState.putBoolean("targets", showingTargets)
        outState.putString("galleryScene", galleryScene)
        if (::journal.isInitialized) journal.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::journal.isInitialized) journal.close()
        translation?.close()
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val PORTRAIT = 61
        const val GALLERY = 62
        const val PACK_PAGE = 25
    }
}

internal fun Activity.chooseMissionScene(mission: GtaMission, title: String, selected: (String?) -> Unit) {
    if (mission.scenes.isEmpty()) {
        selected(null)
        return
    }
    val labels = listOf("Genel · sahne belirtme") + mission.scenes.map { it.title }
    AlertDialog.Builder(this).setTitle(title).setItems(labels.toTypedArray()) { _, index ->
        selected(mission.scenes.getOrNull(index - 1)?.id)
    }.setNegativeButton("Vazgeç", null).show()
}

internal fun missionTimeRange(start: Long, end: Long? = null): String {
    fun time(value: Long) = String.format(Locale.ROOT, "%d:%02d.%03d", value / 60_000, value / 1000 % 60, value % 1000)
    return if (end == null) time(start) else "${time(start)}–${time(end)}"
}
