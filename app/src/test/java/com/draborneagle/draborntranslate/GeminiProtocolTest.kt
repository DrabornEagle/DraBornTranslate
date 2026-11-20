// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiProtocolTest {
    @Test
    fun requestKeepsCurrentDialogueAndEarlierLinesAsSeparateData() {
        val request = geminiRequest(TranslationInput("Lose them!", listOf("A patrol car is tailing us.")))
        val part = request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        val quotedGameText = JSONObject(part.getString("text"))
        assertEquals("Lose them!", quotedGameText.getString("current"))
        assertEquals("A patrol car is tailing us.", quotedGameText.getJSONArray("previous").getString(0))
        assertEquals("application/json", request.getJSONObject("generationConfig").getString("responseMimeType"))
    }

    @Test
    fun completedStructuredOutputReturnsOnlyTheTurkishCue() {
        val part = JSONObject().put("text", JSONObject().put("translation", "İzini kaybettir!").toString())
        val body = response(part, "STOP").toString()
        assertEquals(TranslationReply("İzini kaybettir!"), geminiReply(body))
        assertNull(geminiReply(response(part, "MAX_TOKENS").toString()).text)
        assertNull(geminiReply("unstructured response").text)
    }

    private fun response(part: JSONObject, reason: String): JSONObject =
        JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", reason)
            .put("content", JSONObject().put("parts", JSONArray().put(part)))))
}
