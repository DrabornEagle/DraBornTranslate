// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.gametranslator.gametranslator_mobile.core.TextTranslator
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/** Quantized CPU inference. Each decoder step reuses the preceding step's key and value tensors. */
internal class TozTranslationModel(model: File) : TextTranslator, AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val tokenizer = File(model, "tokenizer.model").inputStream().use(::TozTokenizer)
    private val encoder = open(File(model, "encoder_q8.onnx"))
    private val decoder = try {
        open(File(model, "decoder_q8.onnx"))
    } catch (error: Exception) {
        encoder.close()
        throw error
    }

    private data class Beam(val tokens: IntArray, val score: Float, val parent: Int)
    private data class Candidate(val token: Int, val score: Float, val parent: Int)

    private fun open(file: File): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        environment.createSession(file.absolutePath, options)
    }

    override fun translate(text: String): String? {
        val source = tokenizer.encode(text)
        if (source.isEmpty() || source.size > MAX_INPUT_TOKENS) return null
        val ids = (listOf(tokenizer.turkish) + source + EOS).map(Int::toLong).toLongArray()
        return ints(ids, longArrayOf(1, ids.size.toLong())).use { input ->
            encoder.run(mapOf("src" to input)).use { cross ->
                decode(cross, minOf(2 * source.size + 10, MAX_INPUT_TOKENS), numberFacts(text))
            }
        }
    }

    private fun decode(cross: OrtSession.Result, steps: Int, expectedNumbers: List<String>): String? {
        val emptyShape = longArrayOf(6, 1, 8, 0, 64)
        return OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), emptyShape).use { empty ->
            var previous: OrtSession.Result? = null
            try {
                var beams = listOf(Beam(intArrayOf(BOS), 0f, 0))
                var bestFinished: Beam? = null
                var bestFinishedScore = Float.NEGATIVE_INFINITY
                var numberPreservingText: String? = null
                var numberPreservingScore = Float.NEGATIVE_INFINITY
                var finished = 0
                for (step in 0 until steps) {
                    val current = advance(beams, step, previous, cross, empty)
                    previous?.close()
                    previous = current
                    val logits = (current[0] as OnnxTensor).floatBuffer
                    val next = ArrayList<Beam>(BEAM_WIDTH)
                    for (candidate in bestCandidates(beams, logits)) {
                        val prefix = beams[candidate.parent].tokens
                        if (candidate.token == EOS) {
                            if (prefix.size == 1) continue
                            val score = candidate.score / prefix.size
                            if (score > bestFinishedScore) {
                                bestFinishedScore = score
                                bestFinished = Beam(prefix, candidate.score, candidate.parent)
                            }
                            if (expectedNumbers.isNotEmpty() && score > numberPreservingScore) {
                                val text = tokenizer.decode(prefix.copyOfRange(1, prefix.size))
                                if (numberFacts(text) == expectedNumbers) {
                                    numberPreservingText = text
                                    numberPreservingScore = score
                                }
                            }
                            finished++
                        } else if (next.size < BEAM_WIDTH) {
                            next.add(Beam(prefix + candidate.token, candidate.score, candidate.parent))
                        }
                    }
                    if (next.isEmpty() || finished >= BEAM_WIDTH) break
                    beams = next
                }
                numberPreservingText?.let { return@use it }
                val output = (bestFinished ?: beams.first()).tokens
                tokenizer.decode(output.copyOfRange(1, output.size)).takeIf(String::isNotBlank)
            } finally {
                previous?.close()
            }
        }
    }

    private fun advance(
        beams: List<Beam>, step: Int, past: OrtSession.Result?, cross: OrtSession.Result, empty: OnnxTensor,
    ): OrtSession.Result {
        val count = beams.size.toLong()
        val ids = LongArray(beams.size) { beams[it].tokens.last().toLong() }
        val parents = LongArray(beams.size) { beams[it].parent.toLong() }
        return ints(ids, longArrayOf(count, 1)).use { token ->
            ints(longArrayOf(step.toLong()), longArrayOf(1)).use { position ->
                ints(parents, longArrayOf(count)).use { order ->
                    decoder.run(mapOf(
                        "token" to token,
                        "pos" to position,
                        "past_k" to ((past?.get(1) as? OnnxTensor) ?: empty),
                        "past_v" to ((past?.get(2) as? OnnxTensor) ?: empty),
                        "beam_idx" to order,
                        "cross_k" to cross[0] as OnnxTensor,
                        "cross_v" to cross[1] as OnnxTensor,
                    ))
                }
            }
        }
    }

    private fun ints(values: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)

    private fun bestCandidates(beams: List<Beam>, logits: FloatBuffer): List<Candidate> {
        val limit = 2 * BEAM_WIDTH
        val best = ArrayList<Candidate>(limit)
        val row = FloatArray(VOCABULARY)
        for ((parent, beam) in beams.withIndex()) {
            logits.get(row)
            val maximum = row.max()
            var sum = 0.0
            for (value in row) sum += exp((value - maximum).toDouble())
            val base = beam.score - maximum - ln(sum).toFloat()
            for (token in EOS until VOCABULARY) {
                if (token == 4 || token == 5) continue // Language tags belong only in the source.
                val score = row[token] + base
                if (best.size == limit && score <= best.last().score) continue
                val position = best.indexOfFirst { score > it.score }.let { if (it < 0) best.size else it }
                best.add(position, Candidate(token, score, parent))
                if (best.size > limit) best.removeAt(limit)
            }
        }
        return best
    }

    override fun close() {
        try {
            decoder.close()
        } finally {
            encoder.close()
        }
    }

    companion object {
        private const val BEAM_WIDTH = 4
        private const val BOS = 2
        private const val EOS = 3
        private const val MAX_INPUT_TOKENS = 254
        private const val VOCABULARY = 32_000
        private val numberOrCode = Regex("""(?<![\p{L}\d])([a-z]{1,4}\d+|\d+(?:[.,:]\d+)*)(?:st|nd|rd|th)?(?![\p{L}\d])""", RegexOption.IGNORE_CASE)

        private fun numberFacts(text: String): List<String> = numberOrCode.findAll(text).map { match ->
            match.groupValues[1].lowercase(Locale.ROOT).filter(Char::isLetterOrDigit).trimStart('0').ifEmpty { "0" }
        }.sorted().toList()
    }
}
