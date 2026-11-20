// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.OcrNoise
import com.gametranslator.gametranslator_mobile.core.TextMatch

private val misreadContraction = Regex("(?<![\\p{L}\\p{N}])[l|]'(?=m\\b|ll\\b|ve\\b|d\\b)")
private val misreadPronoun = Regex("(?<![\\p{L}\\p{N}])[l|](?= (?:am|was|can|can't|could|will|would|have|had|think|know|don't|need|want)\\b)")
private val punctuationGap = Regex(" +(?=[,!?;])")
private val invisible = Regex("[\\u00AD\\u200B\\u200E\\u200F\\uFEFF]")
internal val gameId = Regex("\\b(?:[LR][1-3]|PS[345]|HUD|FPS|[A-Z]+-\\d[A-Z]{2}|[A-Z]+\\d+(?:-[A-Z]+\\d+)+)\\b")

/** Repair narrow OCR confusions without removing words, currency or leading counts. */
internal fun cleanOcrLine(text: String): String = TextMatch.literal(invisible.replace(text, ""))
    .replace("\u2018", "'")
    .replace(misreadContraction, "I'")
    .replace(misreadPronoun, "I")
    .replace(punctuationGap, "")

internal fun looksDamaged(text: String): Boolean = OcrNoise.looksGarbled(gameId.replace(text, " "))
