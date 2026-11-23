// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.gametranslator.gametranslator_mobile.core.GamePack
import com.gametranslator.gametranslator_mobile.core.GamePackLibrary
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

internal object ImportedGamePack {
    private const val MAX_BYTES = 40 * 1024 * 1024
    private const val FILE_NAME = "oyun-paketi.json"
    private const val GAME_TRANSLATOR_APP = "com.gametranslator.gametranslator_mobile"

    fun hasPack(context: Context): Boolean = file(context).baseFile.isFile

    fun library(context: Context) = GamePackLibrary(
        bundled = {
            buildList {
                // Remote GTA 6 packs are optional and installed by the user, never shipped in the APK.
                // Bundled decoders read one file at a time; later user overrides take priority.
                if (Gta6PackInstaller.isInstalled(context)) {
                    add("gta6-community.json.gz" to { Gta6PackInstaller.installedFile(context).readBytes() })
                }
                if (hasPack(context)) add(FILE_NAME to { file(context).readFully() })
            }
        },
        userDirectory = null,
    )

    @Synchronized
    fun install(context: Context, uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Oyun paketi açılamadı.")
        return install(context, raw, "Oyun paketi")
    }

    fun installedAppPacks(context: Context): List<String> = runCatching {
        gameTranslatorAssets(context).list("packs").orEmpty()
            .filter { !it.contains('/') && (it.endsWith(".json") || it.endsWith(".json.gz")) }.sorted()
    }.getOrDefault(emptyList())

    @Synchronized
    fun installFromApp(context: Context, name: String): String {
        if (name !in installedAppPacks(context)) throw IOException("Oyun paketi bulunamadı.")
        return install(context, gameTranslatorAssets(context).open("packs/$name"), name.substringBefore(".json"))
    }

    private fun install(context: Context, raw: InputStream, title: String): String {
        val data = raw.buffered().use { input ->
            input.mark(2)
            val first = input.read()
            val second = input.read()
            input.reset()
            if (first == 0x1f && second == 0x8b) GZIPInputStream(input).use(::readLimited)
            else readLimited(input)
        }
        val pack = GamePack.decode(data, title)
        val file = file(context)
        val output = file.startWrite()
        try {
            output.write(data)
            file.finishWrite(output)
        } catch (error: IOException) {
            file.failWrite(output)
            throw error
        }
        return pack.title
    }

    @Synchronized
    fun remove(context: Context) = file(context).delete()

    private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE_NAME))
    private fun gameTranslatorAssets(context: Context) = context.createPackageContext(GAME_TRANSLATOR_APP, 0).assets

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > MAX_BYTES) throw IOException("Oyun paketi çok büyük.")
            output.write(buffer, 0, count)
        }
    }
}
