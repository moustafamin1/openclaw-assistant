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
                "okrs" to ContextUnigramDto(w = 24.6, c = "OKRs"),
                "tk" to ContextUnigramDto(w = 70.5, c = "TK"),
                "apk" to ContextUnigramDto(w = 10.0, c = "APK"),
                "mvp" to ContextUnigramDto(w = 8.0, c = "MVP"),
                "finanzamt" to ContextUnigramDto(w = 20.0, c = "Finanzamt"),
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
                "set it" to 3.0,
                "it up" to 3.0,
            ),
        )
        return ContextLanguageModel.fromDto(payload)
    }

    @Test
    fun fixesUnlistedDomainTermsUsingPhoneticBucketsAndBigramContext() {
        val ctx = buildSampleContext()
        // Minimal vocabulary (no explicit aliases for Grafana, nuri, up->app, okey ours, tee kay, apk, finanzamt)
        val minimalVocab = "TickTick"

        assertEquals(
            "Fix Grafana dashboard",
            TranscriptNormalizer.normalize("fix graphana dashboard", minimalVocab, ctx),
        )
        assertEquals(
            "Read streak specs Noory app",
            TranscriptNormalizer.normalize("read streak specs nuri up", minimalVocab, ctx),
        )
        assertEquals(
            "Fix OKRs and TK",
            TranscriptNormalizer.normalize("fix okey ours and tee kay", minimalVocab, ctx),
        )
        assertEquals(
            "Download APK for MVP at Finanzamt",
            TranscriptNormalizer.normalize("download ay pee kay for em vee pee at finanz amt", minimalVocab, ctx),
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

    @Test
    fun doesNotCollapseFunctionWordPhrasesLikeTheLongOrAReport() {
        val idx = ContextLanguageModel.fromDto(
            ContextIndexPayloadDto(
                unigrams = mapOf(
                    "tooling" to ContextUnigramDto(w = 4.0, c = "tooling"),
                    "airport" to ContextUnigramDto(w = 12.6, c = "airport"),
                    "after" to ContextUnigramDto(w = 27.95, c = "after"),
                    "accrued" to ContextUnigramDto(w = 4.0, c = "accrued"),
                    "long" to ContextUnigramDto(w = 17.7, c = "long"),
                    "report" to ContextUnigramDto(w = 16.25, c = "report"),
                    "noory" to ContextUnigramDto(w = 148.0, c = "Noory"),
                    "app" to ContextUnigramDto(w = 100.0, c = "app"),
                ),
            ),
        )

        assertEquals(
            "Study the long onboarding for Noory app and compile a report, including all the findings, including video, screenshots.",
            TranscriptNormalizer.normalize(
                "Study the long onboarding for Noory app and compile a report, including all the findings, including video, screenshots.",
                "Noory",
                idx,
            ),
        )
        assertEquals(
            "Build a feature and add a card",
            TranscriptNormalizer.normalize("build a feature and add a card", "TickTick", idx),
        )
    }

    @Test
    fun doesNotHijackEverydayEnglishWordsWithoutContext() {
        val idx = ContextLanguageModel.fromDto(
            ContextIndexPayloadDto(
                unigrams = mapOf(
                    "broad" to ContextUnigramDto(w = 10.0, c = "broad"),
                    "better" to ContextUnigramDto(w = 26.5, c = "better"),
                    "past" to ContextUnigramDto(w = 12.0, c = "past"),
                    "solid" to ContextUnigramDto(w = 10.0, c = "solid"),
                    "total" to ContextUnigramDto(w = 53.8, c = "total"),
                    "message" to ContextUnigramDto(w = 10.0, c = "message"),
                    "short" to ContextUnigramDto(w = 20.9, c = "short"),
                    "show" to ContextUnigramDto(w = 12.0, c = "show"),
                ),
            ),
        )

        assertEquals(
            "Buy bread and butter, pasta, salad, towel, massage, shirt and shoes",
            TranscriptNormalizer.normalize(
                "Buy bread and butter, pasta, salad, towel, massage, shirt and shoes",
                "TickTick",
                idx,
            ),
        )
    }

    @Test
    fun mergePayloadsUsesMaxWeightWithoutDoubleCounting() {
        val layer1 = ContextIndexPayloadDto(
            unigrams = mapOf("tooling" to ContextUnigramDto(w = 2.0, c = "tooling")),
            bigrams = mapOf("noory app" to 15.0),
        )
        val layer2 = ContextIndexPayloadDto(
            unigrams = mapOf("tooling" to ContextUnigramDto(w = 2.0, c = "tooling")),
            bigrams = mapOf("noory app" to 15.0),
        )

        val merged = ContextLanguageModel.mergePayloads(layer1, layer2)
        assertEquals(2.0, merged.unigramWeight("tooling"), 0.0001)
        assertEquals(15.0, merged.bigramWeight("noory", "app"), 0.0001)
    }

    @Test
    fun voicingNeutralBucketsMatchPlosivePairsLikeCluteClaudeAndGbtGpt() {
        val ctx = ContextLanguageModel.fromDto(
            ContextIndexPayloadDto(
                unigrams = mapOf(
                    "gpt" to ContextUnigramDto(w = 12.0, c = "GPT"),
                    "claude" to ContextUnigramDto(w = 18.0, c = "Claude"),
                    "to" to ContextUnigramDto(w = 40.0, c = "to"),
                ),
                bigrams = mapOf(
                    "to claude" to 4.0,
                ),
            ),
        )

        assertEquals(
            "Migrate to Claude and GPT",
            TranscriptNormalizer.normalize("migrate to Clute and gbt", "TickTick", ctx),
        )
    }

    @Test
    fun skipContextRewritePreservesCommonWordsAndFormatsHashtagsAndBrain() {
        val ctx = ContextLanguageModel.fromDto(
            ContextIndexPayloadDto(
                unigrams = mapOf(
                    "tooling" to ContextUnigramDto(w = 40.0, c = "tooling"),
                    "airport" to ContextUnigramDto(w = 40.0, c = "airport"),
                ),
            ),
        )

        assertEquals(
            "@brain: Study long onboarding for Noory app and compile a report #urgent",
            TranscriptNormalizer.normalize(
                rawTranscript = "Brain study long onboarding for noory app and compile a report hashtag urgent",
                customVocabularyRaw = TranscriptNormalizer.DEFAULT_RAW_VOCABULARY,
                contextIndex = ctx,
                skipContextRewrite = true,
            ),
        )
    }
}
