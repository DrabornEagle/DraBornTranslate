// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Yazı tanımanın atladığı ya da yanlış okuduğu kumanda simgesini görüntüden tanır (Windows: ButtonIcons.cs, aynı
 * kurallar ve sabitler; Nino Game Translator'ın AGPL-3.0 ButtonIcons.swift'inin uyarlaması). Oyunlar düğmeyi yazının
 * içine küçük bir resim olarak koyar ("Hold ⓧ to climb"); yazı tanıma o resmi ya hiç okumaz ya da "•", "O", "X" sanır.
 *
 * Sıra: simgenin kendisi bulunur (kesitteki en büyük, kareye yakın parlak leke). Renkliyse PlayStation yüz tuşudur (△
 * yeşil, ✕ mavi, □ pembe, ○ kırmızı) ya da haritadaki işarettir. Artı biçimliyse yön tuşudur, enliyse dokunmatik
 * yüzeydir. Değilse içindeki yazı ("L2", "R1") okunur; yazı yoksa içindeki sembol ✕ ○ △ □ şablonlarıyla karşılaştırılır.
 * Yalnız [PixelImage] üstünde çalışır (Android'e bağlı değildir; JVM testleri yapay simgelerle sınar).
 */
object ButtonIcons {
    /** Geliştirme: simge kararlarının izi (testler için). */
    @Volatile internal var trace: ((String) -> Unit)? = null

    /**
     * [box]: simgenin görüntüdeki kabaca yeri (0–1, üstten). Tanınamazsa null.
     * [readText]: küçük bir kesitteki yazıyı okur ("L2" gibi); verilmezse yazılı simgeler tanınmaz.
     * [strict]: satırın kenarında aranırken (orada simge olmayabilir) yalnız belirgin, tek başına duran bir düğme rozeti
     * kabul edilir; arka plandaki leke simge sanılmasın.
     * [line] ve [readWords]: simgenin içinde durduğu satır ve sözcükleri yerleriyle okuyan tanıyıcı. Yazılı simge önce
     * satırın siyah-beyaz okumasında aranır: simgenin çerçevesi silinir, satır okunur, simgenin yerine düşen sözcük alınır.
     */
    fun classify(image: PixelImage, box: TextBox, readText: ((PixelImage) -> String?)? = null, strict: Boolean = false,
                 line: TextBox? = null, readWords: ((PixelImage) -> List<OcrWord>)? = null, layout: ButtonLayoutVote? = null): String? {
        val at = around(image, box) ?: return null
        var readLine: ((Int, Int, Int, Int) -> String?)? = null
        if (readWords != null && line != null) {
            val lineAt = around(image, line)
            if (lineAt != null) readLine = { x0, y0, x1, y1 -> wordAt(lineAt, at.x + x0, at.y + y0, at.x + x1, at.y + y1, readWords) }
        }
        return classify(at.crop, readText, strict, readLine, layout)
    }

    private class Placed(val crop: PixelImage, val x: Int, val y: Int)

    /**
     * Satır siyah-beyaz okunur; simgenin (görüntüde x0..x1, y0..y1) çerçevesi silinir, yalnız içi kalır. Simgenin
     * ortasına düşen sözcük döner.
     */
    private fun wordAt(line: Placed, x0: Int, y0: Int, x1: Int, y1: Int, readWords: (PixelImage) -> List<OcrWord>): String? {
        val bin = binarized(line.crop)
        val cx = (x0 + x1) / 2.0 - line.x
        val cy = (y0 + y1) / 2.0 - line.y
        val rx = max(1.0, (x1 - x0) / 2.0)
        val ry = max(1.0, (y1 - y0) / 2.0)
        // Dolu beyaz daire (GTA 6: içinde koyu "R3"): siyah-beyaz okumada daire siyah, yazı beyaz kalır ve okunmaz. İçi
        // çoğunlukla siyahsa ters çevrilir, yazı beyaz zeminde siyah olur.
        var inside = 0
        var dark = 0
        for (y in max(0, y0 - line.y) until min(bin.height, y1 - line.y))
            for (x in max(0, x0 - line.x) until min(bin.width, x1 - line.x)) {
                val dx = (x - cx) / rx
                val dy = (y - cy) / ry
                if (dx * dx + dy * dy > 0.8 * 0.8) continue
                inside++
                if (bin.blue(x, y) == 0) dark++
            }
        val filled = inside > 0 && dark * 2 > inside
        for (y in max(0, y0 - line.y - 2) until min(bin.height, y1 - line.y + 3))
            for (x in max(0, x0 - line.x - 2) until min(bin.width, x1 - line.x + 3)) {
                val dx = (x - cx) / rx
                val dy = (y - cy) / ry
                if (dx * dx + dy * dy <= 0.8 * 0.8) {
                    if (filled) {
                        val v = 255 - bin.blue(x, y)
                        bin.set(x, y, v, v, v)
                    }
                    continue
                }
                bin.set(x, y, 255, 255, 255)
            }
        val map = paddedMap(bin)
        val left = (map.x + x0 - line.x) / map.width
        val right = (map.x + x1 - line.x) / map.width
        val top = (map.y + y0 - line.y) / map.height
        val bottom = (map.y + y1 - line.y) / map.height
        val words = readWords(map.image)
        trace?.invoke("satır okuması: " + words.joinToString(" ") { it.text })
        return words.firstOrNull { word ->
            val mx = (word.box.left + word.box.right) / 2
            val my = (word.box.top + word.box.bottom) / 2
            mx >= left && mx <= right && my >= top && my <= bottom
        }?.text
    }

    /**
     * [readLine]: simgenin (kesitte x0, y0, x1, y1) yerindeki sözcüğü satırın okumasından verir.
     * [layout]: beklenen kumanda (PlayStation / Xbox / Otomatik). Otomatik'te kesin biçimli simgeler (dairede okunan "A",
     * üçgen) kararı besler; belirsiz olanlar (dairede X: ✕ mı Ⓧ mi) o ana kadarki karara göre okunur.
     */
    fun classify(crop: PixelImage, readText: ((PixelImage) -> String?)? = null, strict: Boolean = false,
                 readLine: ((Int, Int, Int, Int) -> String?)? = null, layout: ButtonLayoutVote? = null): String? {
        val vote = layout ?: ButtonLayoutVote()
        val (token, sure) = decide(crop, readText, strict, readLine, vote)
        // Yalnız kesin biçimli simge kararı besler: dairedeki X ya da yön tuşu iki ailede de olabilir.
        if (token != null && sure) ButtonTokens.familyOf(token)?.let { vote.add(it) }
        if (token != null) trace?.invoke("sonuç $token${if (sure) " (kesin)" else ""} aile ${vote.current}")
        return token
    }

    private fun isXboxFace(token: String?) = token == ButtonTokens.A || token == ButtonTokens.B || token == ButtonTokens.X || token == ButtonTokens.Y

    private fun circled(c: Char) = (0x24B6 + (c - 'A')).toChar().toString()

    private fun decide(crop: PixelImage, readText: ((PixelImage) -> String?)?, strict: Boolean,
                       readLine: ((Int, Int, Int, Int) -> String?)?, vote: ButtonLayoutVote): Pair<String?, Boolean> {
        val none = null to false
        val gray = Gray.from(crop, 48)
        val blob = gray?.iconBlob() ?: gray?.iconBlob(grow = true)
        if (gray == null || blob == null) {
            trace?.invoke("leke yok (${crop.width}x${crop.height})")
            return none
        }
        val w = blob.maxX - blob.minX + 1
        val h = blob.maxY - blob.minY + 1
        val aspect = w.toDouble() / h
        trace?.invoke("leke ${blob.minX},${blob.minY}-${blob.maxX},${blob.maxY} / ${gray.width}x${gray.height} oran $aspect doygunluk ${blob.saturation}")
        if (strict) {
            // Kesit satırın iki katı yüksekliğinde: rozet satır boyunda (kesitin %27–65'i), kareye yakın, kenara değmiyor.
            val relative = h.toDouble() / gray.height
            if (relative <= 0.27 || relative >= 0.65 || aspect <= 0.78 || aspect >= 1.28 ||
                blob.minX <= 0 || blob.minY <= 0 || blob.maxX >= gray.width - 1 || blob.maxY >= gray.height - 1) return none
        }
        val family = vote.current
        val forced = vote.setting != ButtonLayout.AUTO
        // Otomatik'te iki ailenin şablonları da denenir; seçilen ailede öteki ailenin şekilleri aranmaz.
        val letters = !(forced && family == ButtonFamily.PLAYSTATION)
        val symbols = family != ButtonFamily.XBOX
        val shapeOf by lazy { gray.symbol(blob, letters, symbols) }
        // Yazılı simge (L2, R1, LT, sol çubuk "L"): önce satırın siyah-beyaz okumasında, simgenin yerine düşen sözcüğe bakılır.
        // Renkli yazılmış ad (GTA 6'da pembe "Donnie") renk denetiminde pembe □ sanılıyordu; önce sözcüğe bakılır.
        if (aspect >= 0.62 && readLine != null) {
            val scale = crop.height.toDouble() / gray.height
            val word = readLine((blob.minX * scale).toInt(), (blob.minY * scale).toInt(), ((blob.maxX + 1) * scale).toInt(), ((blob.maxY + 1) * scale).toInt())
            trace?.invoke("simgenin yerindeki sözcük: '$word'")
            label(word, stick = true, family = family, leanXbox = vote.leansXbox)?.let { return it to sureName(it, word) }
            val trimmed = word?.trim()
            val letter = if (trimmed == "x" || trimmed == "×") 'X' else letter(word)
            // Dolu dairedeki çarpı satır okumasında "X" okunur (GTA 6). Xbox'ta aynı daire X tuşudur.
            if (letter == 'X') return (if (family == ButtonFamily.XBOX) ButtonTokens.X else "✕") to false
            // Dairedeki "A" / "B" / "Y": Xbox yüz tuşu. PlayStation'ın △'ı da "A" okunabildiği için Otomatik'te harf şekli
            // ya da Xbox rengi doğrulamalı.
            val face = if (letter == 'A' || letter == 'B' || letter == 'Y') ButtonTokens.xboxFace(letter) else null
            if (face != null) {
                if (family == ButtonFamily.XBOX) return face to (shapeOf?.name == face)
                if (!forced && (shapeOf?.name == face ||
                        (blob.saturation > 0.28 && ButtonTokens.xboxFaceByHue(blob.hue) == face && shapeOf?.name !in setOf("△", "○", "□"))))
                    return face to true
            }
            // Simgenin yerinde gerçek bir sözcük var: renkli yazılmış ad ("Follow Brian") simge değildir.
            if (word != null && word.count { it.isLetter() } >= 2) return none
            // Simgede rakam okundu ("2"): yarısı okunmuş "R2" rozeti; yüz tuşu sembolü değildir.
            if (word != null && word.any { it.isDigit() }) return none
            // Yazı tanıma daire içindeki harfi ("L3") "@" okuyor: yüz tuşu sembolü değildir. Xbox'ın "A" dairesi de "@"
            // okunabilir: harf şekli bakılır.
            if (word?.contains('@') == true && blob.saturation <= 0.28 && !(letters && shapeOf?.name == ButtonTokens.A)) return none
        }
        // Xbox'ta koyu dairedeki renkli harf (GTA V: yeşil "A") lekenin küçük bir parçasıdır, ortalama doygunluk düşük kalır.
        if (blob.saturation > (if (family == ButtonFamily.XBOX) 0.18 else 0.28)) {
            trace?.invoke("renk tonu ${blob.hue}")
            val round = aspect > 0.7 && aspect < 1.4
            if (family == ButtonFamily.XBOX) {
                // Xbox'ın yüz tuşları: yeşil A, kırmızı B, mavi X, sarı Y.
                if (round && isXboxFace(shapeOf?.name)) return shapeOf!!.name to false
                if (round) ButtonTokens.xboxFaceByHue(blob.hue)?.let { return it to false }
                letter(readText?.invoke(padded(crop)))?.let { return (ButtonTokens.xboxFace(it) ?: circled(it)) to false }
                return "◆" to false
            }
            // PlayStation'ın yüz tuşları renklidir: △ yeşil, ○ kırmızı, ✕ mavi, □ pembe. Sarı/turuncu daire düğme değil,
            // haritadaki görev işaretidir. Otomatik'te önce harf şekline bakılır (dairedeki harf de okunmalı).
            val letterShape = shapeOf
            if (!forced && round && letterShape != null && letterShape.sure && isXboxFace(letterShape.name) &&
                ButtonTokens.xboxFaceByHue(blob.hue) == letterShape.name) {
                val confirm = letter(readText?.invoke(padded(crop)))
                if (confirm != null && ButtonTokens.xboxFace(confirm) == letterShape.name) return letterShape.name to true
            }
            if (round) faceButtonByHue(blob.hue)?.let { return it to (shapeOf?.name == it) }
            val one = letter(readText?.invoke(padded(crop)))
            if (one != null) {
                // Sarı dairede "Y": Xbox'ın Y tuşu. PlayStation'da dairedeki A/B/X/Y harfi harita işaretidir.
                val xbox = ButtonTokens.xboxFace(one)
                if (!forced && xbox != null && ButtonTokens.xboxFaceByHue(blob.hue) == xbox) return xbox to true
                if (xbox != null) return "◆" to false
                return circled(one) to false
            }
            return "◆" to false
        }
        if (aspect < 0.62) return none
        gray.dpad(blob)?.let { return it to false }
        val readLetters = ArrayList<Char>()
        // Yazı simgenin kendisinden okunur (sıkı kesit; koyu yazı beyaz kutuda olabilir, ters renkle de denenir).
        if (readText != null) {
            val scale = crop.height.toDouble() / gray.height
            val padX = w * 0.15
            val padY = h * 0.15
            val x0 = max(0.0, (blob.minX - padX) * scale).toInt()
            val y0 = max(0.0, (blob.minY - padY) * scale).toInt()
            val x1 = min(crop.width.toDouble(), (blob.maxX + 1 + padX) * scale).toInt()
            val y1 = min(crop.height.toDouble(), (blob.maxY + 1 + padY) * scale).toInt()
            val icon = if (x1 - x0 >= 4 && y1 - y0 >= 4) crop.crop(x0, y0, x1 - x0, y1 - y0) else crop
            var read: String? = null
            fun named(text: String?): String? {
                read = text
                trace?.invoke("simge okuması: '${text?.trim()}'")
                letter(text)?.let { readLetters.add(it) }
                return label(text, family = family, leanXbox = vote.leansXbox)
            }
            // Son çare: yalnız simgenin içi siyah-beyaz okunur (ince çerçeveli kapsüldeki "RT").
            val found = named(readText(padded(icon))) ?: named(readText(padded(inverted(icon)))) ?: named(readText(padded(crop)))
                ?: named(readText(padded(interior(icon))))
            if (found != null) return found to sureName(found, read)
        }
        // Dokunmatik yüzey: yazısız, en boyunun ~1,6 katı, içi dikey çizgili (Xbox'ta yoktur).
        if (symbols && aspect > 1.45 && gray.stripes(blob) >= 4) return "▦" to true
        // Yazılı kutu ama yazı okunamadı: daire/kare sembol sanılmasın.
        if (aspect > 1.15) return none
        val found = shapeOf ?: return none
        if (found.score <= (if (strict) 0.5 else 0.45)) return none
        return when (found.name) {
            // Çarpı ile X harfi aynı biçimdedir: aileye göre okunur.
            "✕" -> (if (family == ButtonFamily.XBOX) ButtonTokens.X else "✕") to false
            // Harf şekli tek başına yetmez (sol çubuk simgesi ve yarısı dolu "LT" dairesi "B"ye benziyor): dairedeki harf
            // de okunmuş olmalı.
            ButtonTokens.A, ButtonTokens.B, ButtonTokens.Y ->
                if (readLetters.any { ButtonTokens.xboxFace(it) == found.name }) found.name to (family != ButtonFamily.XBOX) else none
            // View ve Menu yalnız Xbox beklenirken ve satırın içinde aranır.
            ButtonTokens.VIEW, ButtonTokens.MENU -> if (family == ButtonFamily.XBOX && !strict) found.name to false else none
            else -> found.name to found.sure
        }
    }

    /** Okunan yazı tek bir büyük harf mi ("A", "(B)", "[Y]"); değilse null. */
    internal fun letter(read: String?): Char? {
        if (read == null) return null
        val core = read.trim().trim('(', ')', '[', ']', '{', '}', '<', '>', '.', ',', ':')
        return if (core.length == 1 && core[0] in 'A'..'Z') core[0] else null
    }

    /**
     * Ad kendi ailesini kesin gösteriyor mu: okunan yazıda gerçekten "LT" / "RS" (Xbox) ya da "L2" / "R1" / OPTIONS
     * (PlayStation) yazıyor. Benzer harflerden düzeltilen ad ("RI" → R1, "LB" → L3) kesin sayılmaz.
     */
    private fun sureName(named: String, read: String?): Boolean {
        val text = (read ?: "").uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return when (named) {
            "LT", "RT", "LS", "RS", "L1", "L2", "R1", "R2" -> text.contains(named)
            "OPTIONS" -> true
            else -> false
        }
    }

    /** Renk tonundan (0–360) PlayStation yüz tuşu: yeşil △, mavi ✕, pembe □, kırmızı ○; sarı/turuncu düğme değildir. */
    internal fun faceButtonByHue(hue: Double): String? = when {
        hue >= 120 && hue < 190 -> "△"
        hue >= 190 && hue < 260 -> "✕"
        hue >= 285 && hue < 335 -> "□"
        hue >= 335 || hue < 18 -> "○"
        else -> null
    }

    private val shoulderInside = Regex("[LR][123IZ7]")
    private val xboxShoulderInside = Regex("[LR][TBS8]")

    /**
     * Simgenin içindeki "L2", "R1", "L3", "OPTIONS" (PlayStation) ya da "LB", "RT", "LS" (Xbox) gibi yazı; düğme adı
     * değilse null. Benzer harfler aileye göre düzeltilir: PlayStation'da "RI" → R1, "LB" → L3; Xbox'ta "RI" → RT,
     * "L8" → LB. "LT" / "RT" / "LS" / "RS" her ailede Xbox'tır.
     * [stick]: yazı simgenin tam yerinden okundu; tek "L" / "R" sol / sağ çubuktur (Xbox oyunları "LS" / "RS" yazar).
     * [leanXbox]: Otomatik'te karar Xbox'a yatıyor; okunan "LB" / "RB" L3 / R3'e çevrilmez.
     */
    internal fun label(read: String?, stick: Boolean = false, family: ButtonFamily = ButtonFamily.PLAYSTATION, leanXbox: Boolean = false): String? {
        if (read == null) return null
        val xbox = family == ButtonFamily.XBOX
        var text = read.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        if (stick && (text == "L" || text == "R")) return if (xbox) null else text
        if (text.startsWith("OPTION")) return "OPTIONS"
        if (xbox && stick && text == "VIEW") return ButtonTokens.VIEW
        if (xbox && stick && text == "MENU") return ButtonTokens.MENU
        if (text.length == 3 && (text[0] == 'C' || text[0] == 'I')) text = text.substring(1)
        // Kesite komşu harf karışabilir ("DL2T"): kısa yazının içinde L/R + rakam (Xbox'ta harf) aranır.
        if (text.length in 3..5) (if (xbox) xboxShoulderInside else shoulderInside).find(text)?.let { text = it.value }
        if (text.length != 2 || (text[0] != 'L' && text[0] != 'R')) return null
        // Xbox'ın tetik ve çubuk tuşu adları PlayStation'da yoktur.
        if (text[1] == 'T' || text[1] == 'S') return text
        if (xbox) {
            val second = when (text[1]) {
                'I', '1', '7', 'L' -> 'T'
                'B', '8', '3' -> 'B'
                '5' -> 'S'
                else -> text[1]
            }
            val named = "${text[0]}$second"
            if (named in ButtonTokens.xboxNames) return named
        }
        if (leanXbox && (text[1] == 'B' || text[1] == '8')) return "${text[0]}B"
        val digit = when (text[1]) {
            'I', '7' -> '1'
            'Z' -> '2'
            'B' -> '3'
            else -> text[1]
        }
        val name = "${text[0]}$digit"
        return if (name in setOf("L1", "L2", "L3", "R1", "R2", "R3")) name else null
    }

    /** Simgenin aranacağı kesit: boşluğun tamamı (dar ise satır yüksekliği kadar genişletilir), satırın 2 katı yükseklik. */
    internal fun cropAround(image: PixelImage, box: TextBox): PixelImage? = around(image, box)?.crop

    private fun around(image: PixelImage, box: TextBox): Placed? {
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        val lineHeight = (box.bottom - box.top) * h
        val cy = (box.top + box.bottom) / 2 * h
        var left = box.left * w
        var right = box.right * w
        val minWidth = lineHeight * 2.0
        if (right - left < minWidth) {
            val cx = (left + right) / 2
            left = cx - minWidth / 2
            right = cx + minWidth / 2
        }
        val x0 = floor(max(0.0, left - 2)).toInt()
        val y0 = floor(max(0.0, cy - lineHeight)).toInt()
        val x1 = ceil(min(w, right + 2)).toInt()
        val y1 = ceil(min(h, cy + lineHeight)).toInt()
        if (x1 - x0 < 6 || y1 - y0 < 6) return null
        return Placed(image.crop(x0, y0, x1 - x0, y1 - y0), x0, y0)
    }

    /** Parlak yazı siyah, gerisi beyaz (Otsu eşiği): yazı tanıma simgedeki ince beyaz yazıyı böyle okuyor. */
    internal fun binarized(image: PixelImage): PixelImage {
        val values = DoubleArray(image.width * image.height)
        for (y in 0 until image.height) for (x in 0 until image.width) values[y * image.width + x] = image.luma(x, y)
        val t = otsu(values)
        val result = PixelImage(image.width, image.height)
        for (i in values.indices) {
            val v = if (values[i] > t) 0 else 255
            result.pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return result
    }

    /**
     * Simgenin yalnız içi: siyah-beyaza çevrilir (parlak yazı siyah), kenardaki çerçeve (daire, kapsül) silinir; içi
     * çoğunlukla siyahsa (dolu daire) ters çevrilir.
     */
    private fun interior(icon: PixelImage): PixelImage {
        val bin = binarized(icon)
        val cx = bin.width / 2.0
        val cy = bin.height / 2.0
        val rx = bin.width * 0.5 * 0.7
        val ry = bin.height * 0.5 * 0.7
        var inside = 0
        var dark = 0
        for (y in 0 until bin.height) for (x in 0 until bin.width) {
            val dx = (x - cx) / rx
            val dy = (y - cy) / ry
            if (dx * dx + dy * dy > 1) continue
            inside++
            if (bin.blue(x, y) == 0) dark++
        }
        val filled = inside > 0 && dark * 2 > inside
        for (y in 0 until bin.height) for (x in 0 until bin.width) {
            val dx = (x - cx) / rx
            val dy = (y - cy) / ry
            val v = if (dx * dx + dy * dy > 1) 255 else if (filled) 255 - bin.blue(x, y) else bin.blue(x, y)
            bin.set(x, y, v, v, v)
        }
        return bin
    }

    /** Renkleri ters çevrilmiş kopya (beyaz kutudaki koyu yazı için). */
    private fun inverted(image: PixelImage): PixelImage {
        val result = PixelImage(image.width, image.height)
        for (y in 0 until image.height) for (x in 0 until image.width)
            result.set(x, y, 255 - image.red(x, y), 255 - image.green(x, y), 255 - image.blue(x, y))
        return result
    }

    /**
     * Yazı tanıma çok kısa ve tek başına duran yazıyı ("L1") çoğu zaman okumaz: kesit, kenar rengiyle doldurulmuş geniş
     * bir zeminin ortasına konur ve en az 140 piksel boya büyütülür.
     */
    internal fun padded(image: PixelImage): PixelImage = paddedMap(image).image

    /** [padded] ve görüntünün zemindeki yeri (büyütmeden önceki piksel ve zemin boyutu). */
    private class PaddedMap(val image: PixelImage, val x: Int, val y: Int, val width: Double, val height: Double)

    private fun paddedMap(image: PixelImage): PaddedMap {
        val w = image.width
        val h = image.height
        var sum = 0.0
        var n = 0
        for (x in 0 until w) for (y in intArrayOf(0, h - 1)) {
            sum += image.red(x, y) + image.green(x, y) + image.blue(x, y)
            n += 3
        }
        val edge = (sum / max(n, 1)).coerceIn(0.0, 255.0).toInt()
        val cw = w * 4
        val ch = h * 2
        val canvas = PixelImage(cw, ch)
        canvas.pixels.fill((0xFF shl 24) or (edge shl 16) or (edge shl 8) or edge)
        val ox = (cw - w) / 2
        val oy = (ch - h) / 2
        for (y in 0 until h) System.arraycopy(image.pixels, y * w, canvas.pixels, (oy + y) * cw + ox, w)
        val factor = ceil(140.0 / max(ch, 1)).toInt()
        return PaddedMap(if (factor > 1) canvas.scaled(min(factor, 8)) else canvas, ox, oy, cw.toDouble(), ch.toDouble())
    }

    /** Otsu eşiği: parlak ile koyuyu en iyi ayıran değer. */
    internal fun otsu(values: DoubleArray): Double {
        val hist = DoubleArray(64)
        for (v in values) hist[(v * 63.999).toInt().coerceIn(0, 63)]++
        val total = values.size.toDouble()
        var sumAll = 0.0
        for (i in 0 until 64) sumAll += i * hist[i]
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var threshold = 32
        for (i in 0 until 64) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = i
            }
        }
        return (threshold + 0.5) / 64
    }

    /** Kesitin gri tonlu ve renk doygunluğu tutan küçük kopyası (satırlar üstten). */
    private class Gray(val width: Int, val height: Int) {
        val luma = DoubleArray(width * height)
        val sat = DoubleArray(width * height)
        val hue = DoubleArray(width * height)

        class Blob(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val mask: BooleanArray, val saturation: Double, val hue: Double)

        companion object {
            fun from(image: PixelImage, target: Int): Gray? {
                if (image.width < 2 || image.height < 2) return null
                val scale = target.toDouble() / image.height
                val width = max(4, (image.width * scale).toInt())
                val gray = Gray(width, target)
                for (y in 0 until target) {
                    // Kutu ortalaması: küçültürken ince çizgiler kaybolmasın.
                    val sy0 = (y / scale).toInt()
                    val sy1 = max(sy0 + 1, min(image.height, ((y + 1) / scale).toInt()))
                    for (x in 0 until width) {
                        val sx0 = (x / scale).toInt()
                        val sx1 = max(sx0 + 1, min(image.width, ((x + 1) / scale).toInt()))
                        var r = 0.0
                        var g = 0.0
                        var b = 0.0
                        var count = 0
                        for (yy in min(sy0, image.height - 1) until sy1)
                            for (xx in min(sx0, image.width - 1) until sx1) {
                                b += image.blue(xx, yy)
                                g += image.green(xx, yy)
                                r += image.red(xx, yy)
                                count++
                            }
                        r /= count * 255.0
                        g /= count * 255.0
                        b /= count * 255.0
                        val i = y * width + x
                        gray.luma[i] = 0.299 * r + 0.587 * g + 0.114 * b
                        val mx = max(r, max(g, b))
                        val mn = min(r, min(g, b))
                        gray.sat[i] = if (mx > 0.15) (mx - mn) / mx else 0.0
                        val d = mx - mn
                        gray.hue[i] = if (d < 1e-6) 0.0
                        else if (mx == r) 60 * (((g - b) / d % 6 + 6) % 6)
                        else if (mx == g) 60 * ((b - r) / d + 2)
                        else 60 * ((r - g) / d + 4)
                    }
                }
                return gray
            }
        }

        /**
         * Kesitteki simge: yüksekliği kesitin üçte birinden büyük, kareye yakın, ortaya yakın en büyük parlak leke.
         * [grow]: parlak alan bir piksel büyütülür; ince çizgili çerçeve (Starfield'ın "RT" kapsülü) küçültmede kopuk kalıyor.
         */
        fun iconBlob(grow: Boolean = false): Blob? {
            val t = otsu(luma)
            var mask = BooleanArray(luma.size) { luma[it] > t }
            if (grow) {
                val grown = mask.copyOf()
                for (y in 0 until height) for (x in 0 until width) {
                    if (!mask[y * width + x]) continue
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height) grown[ny * width + nx] = true
                    }
                }
                mask = grown
            }
            var best: Blob? = null
            var bestScore = 0.0
            val stack = ArrayDeque<Int>()
            for (start in 0 until width * height) {
                if (!mask[start]) continue
                val pixels = ArrayList<Int>()
                mask[start] = false
                stack.addLast(start)
                var minX = width
                var minY = height
                var maxX = 0
                var maxY = 0
                while (stack.isNotEmpty()) {
                    val p = stack.removeLast()
                    pixels.add(p)
                    val x = p % width
                    val y = p / width
                    minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                    if (x + 1 < width && mask[p + 1]) { mask[p + 1] = false; stack.addLast(p + 1) }
                    if (x > 0 && mask[p - 1]) { mask[p - 1] = false; stack.addLast(p - 1) }
                    if (y + 1 < height && mask[p + width]) { mask[p + width] = false; stack.addLast(p + width) }
                    if (y > 0 && mask[p - width]) { mask[p - width] = false; stack.addLast(p - width) }
                }
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                if (bh < height / 3 || bh >= height) continue
                val aspect = bw.toDouble() / bh
                if (aspect <= 0.4 || aspect >= 2.2) continue
                val cx = (minX + maxX) / 2.0 / width
                val squareness = 1 - min(1.0, abs(ln(aspect)))
                val score = bw * bh * (0.5 + squareness) * (1.2 - abs(cx - 0.5))
                if (score <= bestScore) continue
                bestScore = score
                val s = pixels.sumOf { sat[it] } / max(pixels.size, 1)
                // Renk tonu, doygun piksellerin ton açılarının ortalaması (kırmızı 0/360 sınırında bölünmesin diye vektörle).
                var hx = 0.0
                var hy = 0.0
                for (i in pixels) {
                    if (sat[i] < 0.25) continue
                    hx += cos(hue[i] * PI / 180) * sat[i]
                    hy += sin(hue[i] * PI / 180) * sat[i]
                }
                val h = (atan2(hy, hx) * 180 / PI + 360) % 360
                val blobMask = BooleanArray(width * height)
                for (i in pixels) blobMask[i] = true
                best = Blob(minX, minY, maxX, maxY, blobMask, s, h)
            }
            return best
        }

        /** Lekenin orta satırında açık-koyu geçiş sayısının yarısı (dokunmatik yüzey ızgarası çok çizgilidir). */
        fun stripes(b: Blob): Int {
            val y = (b.minY + b.maxY) / 2
            val row = DoubleArray(b.maxX - b.minX + 1) { luma[y * width + b.minX + it] }
            val t = otsu(row)
            var flips = 0
            for (i in 1 until row.size) if ((row[i] > t) != (row[i - 1] > t)) flips++
            return flips / 2
        }

        /** Yön tuşu: artı biçimli leke; ötekilerden parlak kol yönü verir. */
        fun dpad(b: Blob): String? {
            val w = b.maxX - b.minX + 1
            val h = b.maxY - b.minY + 1
            if (w < 6 || h < 6) return null
            fun cell(cx: Int, cy: Int): Pair<Double, Double> {
                val x0 = b.minX + cx * w / 3
                val x1 = max(x0 + 1, b.minX + (cx + 1) * w / 3)
                val y0 = b.minY + cy * h / 3
                val y1 = max(y0 + 1, b.minY + (cy + 1) * h / 3)
                var on = 0
                var n = 0
                var light = 0.0
                for (y in y0 until y1) for (x in x0 until x1) {
                    n++
                    val p = y * width + x
                    if (b.mask[p]) on++
                    light += luma[p]
                }
                return on.toDouble() / max(n, 1) to light / max(n, 1)
            }
            val corners = listOf(cell(0, 0), cell(2, 0), cell(0, 2), cell(2, 2)).map { it.first }
            val arms = listOf("↑" to cell(1, 0), "←" to cell(0, 1), "→" to cell(2, 1), "↓" to cell(1, 2))
            if (corners.max() >= 0.2 || !arms.all { it.second.first > 0.35 }) return null
            val sorted = arms.sortedByDescending { it.second.second }
            return if (sorted[0].second.second > sorted[1].second.second * 1.12) sorted[0].first else "✜"
        }

        /**
         * Simgenin içindeki sembol: iç dairede azınlıkta kalan renk ✕ ○ △ □ ([symbols]) ve Xbox'ın A B Y harfleri, View ⧉
         * ve Menu ☰ ([letters]) şablonlarıyla karşılaştırılır. Simgeler iç içe halkalı olabilir: birkaç iç yarıçapta
         * aranır. Eşik uygulanmaz; çağıran puana bakar.
         */
        fun symbol(b: Blob, letters: Boolean = false, symbols: Boolean = true): Shape? {
            var best: Shape? = null
            for (factor in doubleArrayOf(0.74, 0.6, 0.48)) {
                val found = symbolAt(b, factor, letters, symbols) ?: continue
                if (best == null || found.score > best.score) best = found
            }
            return best
        }

        private class Mark(val x: Double, val y: Double, val v: Double)

        private fun symbolAt(b: Blob, factor: Double, letters: Boolean, symbols: Boolean): Shape? {
            val w = b.maxX - b.minX + 1
            val h = b.maxY - b.minY + 1
            val cx = (b.minX + b.maxX) / 2.0
            val cy = (b.minY + b.maxY) / 2.0
            val r = min(w, h) / 2.0 * factor
            if (r < 4) return null
            val inner = ArrayList<Mark>()
            for (y in (cy - r).toInt()..(cy + r).toInt())
                for (x in (cx - r).toInt()..(cx + r).toInt()) {
                    if (x < 0 || y < 0 || x >= width || y >= height) continue
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy > r * r) continue
                    inner.add(Mark(dx / r, dy / r, luma[y * width + x]))
                }
            if (inner.size <= 30) return null
            val values = DoubleArray(inner.size) { inner[it].v }
            if (values.max() - values.min() <= 0.15) return null
            val t = otsu(values)
            val bright = inner.count { it.v > t }
            val minorityIsBright = bright * 2 < inner.size
            val marks = inner.filter { if (minorityIsBright) it.v > t else it.v <= t }
            if (marks.size < 6) return null
            // İşaretli noktalar sembolün kendi kutusuna oranlanır, 16x16 ızgarada şablonlarla karşılaştırılır.
            val mx0 = marks.minOf { it.x }
            val mx1 = marks.maxOf { it.x }
            val my0 = marks.minOf { it.y }
            val my1 = marks.maxOf { it.y }
            val n = Templates.N
            val grid = BooleanArray(n * n)
            for (m in marks) {
                val gx = min(n - 1, ((m.x - mx0) / max(mx1 - mx0, 0.01) * n).toInt())
                val gy = min(n - 1, ((m.y - my0) / max(my1 - my0, 0.01) * n).toInt())
                grid[gy * n + gx] = true
            }
            // İçinde yan yana iki parça olan işaret ("R3", "L3") yazıdır, sembol değildir: şablonla karşılaştırılmaz.
            // Örnekleme ızgarada tek hücrelik boşluklar bırakır: parçalar bir hücre büyütülüp sayılır.
            // Alt alta üç yatay çizgi ise Xbox'ın Menu tuşudur (☰); çizgiler yakın olduğu için yalnız yatayda büyütülmüş
            // ızgarada da bakılır.
            val parts = componentBoxes(Templates.grown(grid), n)
            if (letters && parts.size < 3 && isMenu(componentBoxes(Templates.grownSideways(grid), n), n)) return Shape(ButtonTokens.MENU, 0.9, true)
            if (parts.size >= 2) {
                if (letters && isMenu(parts, n)) return Shape(ButtonTokens.MENU, 0.9, true)
                trace?.invoke("sembol $factor: birden çok parça (yazı)")
                return null
            }
            // Karenin köşeleri doludur; daire ve çarpıda köşe hücreleri boş kalır. Köşeleri yuvarlak kare (GTA 6) için
            // köşe hücresinin bir hücre içerisi de sayılır.
            var corners = 0
            for ((qx, qy) in listOf(0 to 0, n - 3 to 0, 0 to n - 3, n - 3 to n - 3)) {
                var hit = false
                for (yy in qy until qy + 3) {
                    for (xx in qx until qx + 3) if (grid[yy * n + xx]) { hit = true; break }
                    if (hit) break
                }
                if (hit) corners++
            }
            // Kare ile daire, şeklin merkezden uzaklığıyla ayrılır: karede çapraz yönler kenar yönlerinden ~1,4 kat uzaktır.
            val corneriness = corneriness(marks.map { (it.x - (mx0 + mx1) / 2) to (it.y - (my0 + my1) / 2) })
            trace?.invoke("köşelilik $corneriness")
            var bestName: String? = null
            var bestScore = 0.0
            var bestSymbol = 0.0
            var bestLetter = 0.0
            for ((name, template) in Templates.all) {
                val letter = Templates.isLetter(name)
                // Çarpı X harfiyle aynıdır: iki ailede de aranır.
                if (if (letter) !letters else !symbols && name != "✕") continue
                if (name == "□" && corners < 4) continue
                // Harfin içi boşluklu: dolu leke (yarısı dolu "LT" dairesi) B sanılmasın diye doluluk da uymalı.
                if (letter && abs(Templates.fill(grid) - Templates.fill(template)) > 0.25) continue
                val score = Templates.similarity(grid, template)
                trace?.invoke("sembol $factor $name $score köşe $corners")
                if (letter) bestLetter = max(bestLetter, score) else bestSymbol = max(bestSymbol, score)
                if (bestName == null || score > bestScore) { bestName = name; bestScore = score }
            }
            var name = bestName ?: return null
            if (name == "○" && corneriness > 1.10) name = "□"
            else if (name == "□" && corneriness < 1.06) name = "○"
            // Kesin karar: harf, PlayStation şekillerinden belirgin biçimde iyi uyuyor (ya da tersi). Çarpı iki ailede de var.
            val margin = 0.06
            val sure = name != "✕" && if (Templates.isLetter(name)) bestScore - bestSymbol >= margin else bestScore - bestLetter >= margin
            return Shape(name, bestScore, sure)
        }

        /** Alt alta, eşit aralıklı, en az ızgaranın yarısı genişliğinde üç yatay çizgi: Xbox'ın Menu tuşu (☰). */
        private fun isMenu(found: List<IntArray>, n: Int): Boolean {
            // Örnekleme çizgi kenarında tek satırlık kırıntı bırakabilir: en çok bir ince parça atlanır.
            val parts = if (found.size == 4) found.sortedByDescending { (it[2] - it[0] + 1) * (it[3] - it[1] + 1) }.take(3) else found
            if (parts.size != 3) return false
            val sorted = parts.sortedBy { it[1] }
            if (sorted.any { it[2] - it[0] + 1 < n * 0.6 || it[3] - it[1] + 1 > n * 0.4 }) return false
            // Üç çizgi aynı boyda ve hizada.
            if (sorted.maxOf { it[2] - it[0] } - sorted.minOf { it[2] - it[0] } > n * 0.2 ||
                sorted.maxOf { it[0] } - sorted.minOf { it[0] } > n * 0.15) return false
            val c0 = (sorted[0][1] + sorted[0][3]) / 2.0
            val c1 = (sorted[1][1] + sorted[1][3]) / 2.0
            val c2 = (sorted[2][1] + sorted[2][3]) / 2.0
            return abs((c1 - c0) - (c2 - c1)) <= n * 0.15
        }
    }

    /**
     * Çapraz yönlerdeki en uzak işaretin merkeze uzaklığı / eksen yönlerindeki en uzak işaretinki. Kare ~1,4, daire ~1
     * (yalnız ○ / □ kararında kullanılır).
     */
    private fun corneriness(points: List<Pair<Double, Double>>): Double {
        val far = DoubleArray(16)
        for ((x, y) in points) {
            val bin = ((atan2(y, x) + PI) / (2 * PI) * 16).toInt() % 16
            far[bin] = max(far[bin], sqrt(x * x + y * y))
        }
        // 16 dilim: 0, 4, 8, 12 eksen yönleri (sol, üst, sağ, alt); 2, 6, 10, 14 çaprazlar.
        val axis = (far[0] + far[4] + far[8] + far[12]) / 4
        val diagonal = (far[2] + far[6] + far[10] + far[14]) / 4
        return if (axis <= 0) 1.0 else diagonal / axis
    }

    /** Şablonla eşleşen şekil: ad, benzerlik (0–1) ve öteki ailenin şekillerinden belirgin biçimde iyi mi. */
    private class Shape(val name: String, val score: Double, val sure: Boolean)

    /** Izgaradaki sekiz yönlü bağlı parçaların kutuları [minX, minY, maxX, maxY] (tek hücrelik kırıntılar sayılmaz). */
    private fun componentBoxes(grid: BooleanArray, n: Int): List<IntArray> {
        val seen = BooleanArray(grid.size)
        val boxes = ArrayList<IntArray>()
        val stack = ArrayDeque<Int>()
        for (start in grid.indices) {
            if (!grid[start] || seen[start]) continue
            var size = 0
            var minX = n
            var minY = n
            var maxX = 0
            var maxY = 0
            seen[start] = true
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                size++
                val x = p % n
                val y = p / n
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= n || ny >= n) continue
                    val q = ny * n + nx
                    if (grid[q] && !seen[q]) { seen[q] = true; stack.addLast(q) }
                }
            }
            if (size > 2) boxes.add(intArrayOf(minX, minY, maxX, maxY))
        }
        return boxes
    }

    /** ✕ ○ △ □ ve Xbox'ın A B Y harfleri ile View ⧉ şablonları (16x16, sembolün kendi kutusuna oranlı çizgi). */
    private object Templates {
        const val N = 16

        val all: List<Pair<String, BooleanArray>> = listOf(
            "✕" to draw { x, y -> abs(x - y) < 0.17 || abs(x + y - 1) < 0.17 },
            "○" to draw { x, y -> val d = sqrt((x - 0.5) * (x - 0.5) + (y - 0.5) * (y - 0.5)); d > 0.34 && d < 0.52 },
            "□" to draw { x, y -> x < 0.18 || x > 0.82 || y < 0.18 || y > 0.82 },
            // Tepe üstte; iki yan kenar ve taban.
            "△" to draw { x, y -> y > 0.82 || abs(x - (0.5 - y / 2)) < 0.12 || abs(x - (0.5 + y / 2)) < 0.12 },
            // A: üçgenin iki kenarı gibi iki bacak, tabanı yok; ortanın altında yatay çizgi.
            ButtonTokens.A to draw { x, y -> abs(x - (0.5 - y / 2)) < 0.13 || abs(x - (0.5 + y / 2)) < 0.13 ||
                (abs(y - 0.63) < 0.08 && x > 0.5 - y / 2 && x < 0.5 + y / 2) },
            // B: solda dikey gövde, üstte, ortada ve altta yatay çizgi, sağda iki göbek.
            ButtonTokens.B to draw { x, y -> x < 0.2 || (x < 0.7 && (y < 0.14 || abs(y - 0.5) < 0.08 || y > 0.86)) ||
                (x >= 0.6 && (bowl(x, y, 0.25) || bowl(x, y, 0.75))) },
            // Y: üstten ortaya inen iki kol ve alta inen gövde.
            ButtonTokens.Y to draw { x, y -> (y < 0.58 && (abs(x - y * 0.9) < 0.13 || abs(x - (1 - y * 0.9)) < 0.13)) ||
                (y >= 0.45 && abs(x - 0.5) < 0.11) },
            // View: üst üste binen iki kare çerçeve.
            ButtonTokens.VIEW to draw { x, y -> frame(x, y, 0.0, 0.0, 0.66, 0.66) || frame(x, y, 0.34, 0.34, 1.0, 1.0) },
        )

        /** Izgaranın dolu hücre oranı. */
        fun fill(g: BooleanArray): Double = g.count { it }.toDouble() / g.size

        /** Xbox şablonu mu (harf ya da View; PlayStation şekilleriyle yarışır). */
        fun isLetter(name: String) = name == ButtonTokens.A || name == ButtonTokens.B || name == ButtonTokens.Y ||
            name == ButtonTokens.VIEW || name == ButtonTokens.MENU

        private fun bowl(x: Double, y: Double, cy: Double): Boolean {
            val d = sqrt(((x - 0.6) / 0.38) * ((x - 0.6) / 0.38) + ((y - cy) / 0.25) * ((y - cy) / 0.25))
            return abs(d - 0.82) < 0.2
        }

        private fun frame(x: Double, y: Double, x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
            val s = 0.12
            val inside = x >= x0 && x <= x1 && y >= y0 && y <= y1
            return inside && (x - x0 < s || x1 - x < s || y - y0 < s || y1 - y < s)
        }

        /** Yalnız sağa ve sola bir hücre büyütülmüş ızgara (yatay çizgilerdeki örnekleme boşlukları kapanır). */
        fun grownSideways(g: BooleanArray): BooleanArray {
            val output = g.copyOf()
            for (y in 0 until N) for (x in 0 until N) {
                if (!g[y * N + x]) continue
                if (x + 1 < N) output[y * N + x + 1] = true
                if (x > 0) output[y * N + x - 1] = true
            }
            return output
        }

        private fun draw(f: (Double, Double) -> Boolean): BooleanArray {
            val g = BooleanArray(N * N)
            for (y in 0 until N) for (x in 0 until N) g[y * N + x] = f((x + 0.5) / N, (y + 0.5) / N)
            return g
        }

        fun grown(g: BooleanArray): BooleanArray {
            val output = g.copyOf()
            for (y in 0 until N) for (x in 0 until N) {
                if (!g[y * N + x]) continue
                if (x + 1 < N) output[y * N + x + 1] = true
                if (x > 0) output[y * N + x - 1] = true
                if (y + 1 < N) output[(y + 1) * N + x] = true
                if (y > 0) output[(y - 1) * N + x] = true
            }
            return output
        }

        /** İşaretlerin ne kadarı şablonda (bir hücre payla) × şablonun ne kadarı işaretlenmiş (bir hücre payla). */
        fun similarity(a: BooleanArray, t: BooleanArray): Double {
            val gt = grown(t)
            val ga = grown(a)
            var inA = 0
            var aCount = 0
            var inT = 0
            var tCount = 0
            for (i in 0 until N * N) {
                if (a[i]) { aCount++; if (gt[i]) inA++ }
                if (t[i]) { tCount++; if (ga[i]) inT++ }
            }
            return inA.toDouble() / max(aCount, 1) * inT / max(tCount, 1)
        }
    }
}
