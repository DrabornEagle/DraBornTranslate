// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal data class MissionPhoto(val file: File, val sceneId: String?)

internal object MissionMedia {
    const val MAX_CAPTURES = 12

    fun portrait(context: Context, giver: String): File {
        val key = giver.lowercase(Locale.ROOT).trim().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
        return File(File(root(context), "karakterler"), "$hash.jpg")
    }

    fun photos(context: Context, missionId: String): List<MissionPhoto> =
        missionFolder(context, missionId).listFiles()?.filter(File::isDirectory).orEmpty().flatMap { folder ->
            folder.listFiles()?.filter { it.isFile && it.extension == "jpg" }.orEmpty().map { file ->
                MissionPhoto(file, folder.name.takeUnless { it == "genel" })
            }
        }.sortedByDescending { it.file.name }

    fun savePortrait(context: Context, giver: String, uri: Uri) = save(context, uri, portrait(context, giver), 320)

    @Synchronized
    fun savePhotos(context: Context, missionId: String, sceneId: String?, uris: List<Uri>): Int {
        val room = MAX_CAPTURES - photos(context, missionId).size
        if (room <= 0) throw IOException("Bu görevde $MAX_CAPTURES görüntü var.")
        val folder = File(missionFolder(context, missionId), sceneId ?: "genel")
        var added = 0
        for (uri in uris.distinct().take(room)) {
            val file = File(folder, "${System.currentTimeMillis()}-${UUID.randomUUID()}.jpg")
            val saved = runCatching { save(context, uri, file, 1440) }.isSuccess
            if (saved) added++
        }
        if (added == 0) throw IOException("Seçilen oyun görüntüleri okunamadı.")
        return added
    }

    fun thumbnail(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val options = BitmapFactory.Options()
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > maxEdge * 2) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun save(context: Context, uri: Uri, destination: File, maxEdge: Int) {
        destination.parentFile?.mkdirs()
        val temp = File.createTempFile("gta-image-", ".part", destination.parentFile)
        try {
            val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (width, height) = info.size.let { it.width to it.height }
                val ratio = 1f.coerceAtMost(maxEdge.toFloat() / maxOf(width, height))
                decoder.setTargetSize((width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                temp.outputStream().use {
                    if (!image.compress(Bitmap.CompressFormat.JPEG, 88, it)) throw IOException("Görüntü yazılamadı.")
                }
            } finally {
                image.recycle()
            }
            Files.move(temp.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private fun root(context: Context) = File(context.noBackupFilesDir, "gta-misyon-galerisi")
    private fun missionFolder(context: Context, missionId: String) = File(File(root(context), "gorevler"), missionId)
}
