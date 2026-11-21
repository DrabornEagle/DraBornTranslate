// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.google.protobuf.CodedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.Normalizer

/** SentencePiece unigram and UTF-8 byte fallback for the bundled Töz-Çevir-1 vocabulary. */
internal class TozTokenizer(input: InputStream) {
    private data class Piece(val id: Int, val score: Float)
    private val vocabulary = ArrayList<String>(32_000)
    private val pieces = HashMap<String, Piece>(32_000)
    private val bytes = IntArray(256)
    private val byteById = HashMap<Int, Int>(256)
    private val maximumLength: Int
    private val unknownScore: Float
    val turkish: Int

    init {
        val stream = CodedInputStream.newInstance(input)
        while (!stream.isAtEnd) {
            val tag = stream.readTag()
            if (tag != 10) {
                stream.skipField(tag)
                continue
            }
            val limit = stream.pushLimit(stream.readRawVarint32())
            var piece = ""
            var score = 0f
            var type = 1
            while (stream.bytesUntilLimit > 0) {
                when (val field = stream.readTag()) {
                    10 -> piece = stream.readStringRequireUtf8()
                    21 -> score = stream.readFloat()
                    24 -> type = stream.readEnum()
                    else -> stream.skipField(field)
                }
            }
            stream.popLimit(limit)
            val id = vocabulary.size
            vocabulary.add(piece)
            if (type == 1) pieces[piece] = Piece(id, score)
            if (type == 6) {
                val value = piece.substring(3, 5).toInt(16)
                bytes[value] = id
                byteById[id] = value
            }
        }
        require(vocabulary.size == 32_000 && vocabulary.take(4) == listOf("<pad>", "<unk>", "<s>", "</s>"))
        require(byteById.size == 256)
        maximumLength = pieces.keys.maxOf(String::length)
        unknownScore = pieces.values.minOf(Piece::score) - 10
        turkish = vocabulary.indexOf("<2tr>").also { require(it >= 0) }
    }

    fun encode(input: String): List<Int> {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFKC)
        val text = buildString {
            var space = true
            for (char in normalized) {
                if (char.isWhitespace() || char == SPACE) {
                    space = true
                } else if (!char.isISOControl()) {
                    if (space) append(SPACE)
                    append(char)
                    space = false
                }
            }
        }
        val scores = FloatArray(text.length + 1) { Float.NEGATIVE_INFINITY }
        val starts = IntArray(text.length + 1)
        val tokens = IntArray(text.length + 1)
        scores[0] = 0f
        fun update(start: Int, end: Int, id: Int, score: Float) {
            val total = scores[start] + score
            if (total > scores[end]) {
                scores[end] = total
                starts[end] = start
                tokens[end] = id
            }
        }
        var start = 0
        while (start < text.length) {
            val firstEnd = start + Character.charCount(text.codePointAt(start))
            var hasSinglePiece = false
            for (end in firstEnd..minOf(start + maximumLength, text.length)) {
                val piece = pieces[text.substring(start, end)] ?: continue
                update(start, end, piece.id, piece.score)
                hasSinglePiece = hasSinglePiece || end == firstEnd
            }
            if (!hasSinglePiece) update(start, firstEnd, UNK, unknownScore)
            start = firstEnd
        }
        var end = text.length
        val reversed = ArrayList<Int>()
        while (end > 0) {
            val id = tokens[end]
            val previous = starts[end]
            if (id == UNK) {
                val encoded = text.substring(previous, end).toByteArray(Charsets.UTF_8)
                for (index in encoded.indices.reversed()) reversed.add(bytes[encoded[index].toInt() and 255])
            } else {
                reversed.add(id)
            }
            end = previous
        }
        return reversed.asReversed()
    }

    fun decode(tokens: IntArray): String {
        val pending = ByteArrayOutputStream()
        return buildString {
            fun flushBytes() {
                if (pending.size() > 0) {
                    append(pending.toString(Charsets.UTF_8.name()))
                    pending.reset()
                }
            }
            for (id in tokens) {
                val value = byteById[id]
                if (value != null) pending.write(value)
                else {
                    flushBytes()
                    append(vocabulary[id])
                }
            }
            flushBytes()
        }.replace(SPACE, ' ').trim()
    }

    companion object {
        private const val UNK = 1
        private const val SPACE = '▁'
    }
}
