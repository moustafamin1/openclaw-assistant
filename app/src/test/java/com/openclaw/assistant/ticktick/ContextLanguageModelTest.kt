package com.openclaw.assistant.ticktick

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextLanguageModelTest {

    private fun buildSampleContext(): ContextIndex {
        val payload = ContextIndexPayloadDto(
            unigrams = mapOf(
                "noory" to ContextUnigramDto(w = 148.0, c = "Noory"),
                "app" to ContextUnigramDto(w = 99.0, c = "app"),
                "streak" to ContextUnigramDto(w = 14.0, c = "streak"),
                "specs" to ContextUnigramDto(w = 6.0, c = "specs"),
                "tazkiyah" to ContextUnigramDto(w = 18.0, c = "Tazkiyah"),
                "grafana" to ContextUnigramDto(w = 16.0, c = "Grafana"),
                "dashboard" to ContextUnigramDto(w = 10.0, c = "dashboard"),
                "zebeeb" to ContextUnigramDto(w = 60.0, c = "Zebeeb"),
                "evals" to ContextUnigramDto(w = 12.0, c = "evals"),
                "set" to ContextUnigramDto(w = 5.0, c = "set"),
                "up" to ContextUnigramDto(w = 5.0, c = "up"),
                "it" to ContextUnigramDto(w = 10.0, c = "it"),
            ),
            bigrams = mapOf(
                "noory app" to 30.0,
                "streak specs" to 4.0,
                "specs noory" to 3.0,
                "grafana dashboard" to 8.0,
                "tazkiyah specs" to 6.0,
                "set it" to 3.0,
                "it up" to 3.0,
            ),
        )
        return ContextLanguageModel.fromDto(payload)
    }

    @Test
    fun fixesUnlistedDomainTermsUsingPhoneticBucketsAndBigramContext() {
        val ctx = buildSampleContext()
        // Use minimal vocabulary (no explicit aliases for Tazkiyah, Grafana, nuri, up->app)
        val minimalVocab = "TickTick"

        assertEquals(
            "Update Tazkiyah specs",
            TranscriptNormalizer.normalize("update taskiyah specs", minimalVocab, ctx),
        )
        assertEquals(
            "Fix Grafana dashboard",
            TranscriptNormalizer.normalize("fix graphana dashboard", minimalVocab, ctx),
        )
        assertEquals(
            "Read streak specs Noory app",
            TranscriptNormalizer.normalize("read streak specs nuri up", minimalVocab, ctx),
        )
    }

    @Test
    fun preservesNormalEnglishSentencesAndPhrasalVerbs() {
        val ctx = buildSampleContext()
        assertEquals(
            "Set it up tomorrow",
            TranscriptNormalizer.normalize("set it up tomorrow", "TickTick", ctx),
        )
        assertEquals(
            "Set Noory up on staging",
            TranscriptNormalizer.normalize("set noory up on staging", "TickTick", ctx),
        )
        assertEquals(
            "Kids are being naughty today",
            TranscriptNormalizer.normalize("kids are being naughty today", "TickTick", ctx),
        )
    }

    @Test
    fun hypothesisRescoringFavorsContextBigrams() {
        val ctx = buildSampleContext()
        val best = TranscriptNormalizer.selectBestHypothesis(
            hypotheses = listOf(
                "read streak specs naughty up",
                "read streak specs nuri up",
                "read streak specs Noory app",
            ),
            customVocabularyRaw = "TickTick",
            contextIndex = ctx,
        )
        assertEquals("read streak specs Noory app", best)
    }

    @Test
    fun incrementalAccumulationReinforcesCapturedVocabulary() {
        val base = ContextIndexPayloadDto()
        val updatedDto = ContextIndexManager.accumulateSentences(
            base = base,
            sentences = listOf(
                "Check Tazkiyah streak flow",
                "Fix Tazkiyah bug",
                "Deploy Tazkiyah flow",
            ),
            weight = 2.0,
        )
        val idx = ContextLanguageModel.fromDto(updatedDto)
        assertEquals(
            "Check Tazkiyah bug",
            TranscriptNormalizer.normalize("check taskiyah bug", "TickTick", idx),
        )
    }
}
