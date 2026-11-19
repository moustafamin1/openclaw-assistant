package com.openclaw.assistant.ticktick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gemma4PromptBuilderTest {

    @Test
    fun buildCompactVocabularyDeduplicatesAndCapsTerms() {
        val ctx = ContextLanguageModel.fromDto(
            ContextIndexPayloadDto(
                unigrams = mapOf(
                    "claude" to ContextUnigramDto(w = 18.0, c = "Claude"),
                    "chatgpt" to ContextUnigramDto(w = 10.0, c = "ChatGPT"),
                    "study" to ContextUnigramDto(w = 46.0, c = "study"),
                ),
            ),
        )
        val custom = "Claude\nNoory\nnoory\nZebeeb = the bib\nChatGPT"
        val terms = Gemma4PromptBuilder.buildCompactVocabulary(custom, ctx, maxTerms = 20)

        assertTrue(terms.size <= 20)
        assertEquals(1, terms.count { it.equals("Noory", ignoreCase = true) })
        assertEquals(1, terms.count { it.equals("Claude", ignoreCase = true) })
        assertFalse(terms.any { it.equals("study", ignoreCase = true) })
    }

    @Test
    fun buildSystemInstructionIncludesRulesAndVocabularyLine() {
        val prompt = Gemma4PromptBuilder.buildSystemInstruction(listOf("Noory", "Claude", "Finanzamt"))
        assertTrue(prompt.contains("Personal Vocabulary: Noory, Claude, Finanzamt"))
        assertTrue(prompt.contains("[SILENCE]"))
        assertTrue(prompt.contains("English, German, Arabic"))
    }

    @Test
    fun sanitizeModelOutputCleansThinkingQuotesPrefixesAndSilence() {
        assertEquals(
            "Ask ChatGPT to migrate the scorer model to Claude",
            Gemma4PromptBuilder.sanitizeModelOutput(
                "<thought>considering options</thought> Task: \"Ask ChatGPT to   migrate the scorer model to Claude\"",
            ),
        )
        assertEquals("", Gemma4PromptBuilder.sanitizeModelOutput("[SILENCE]"))
        assertEquals("", Gemma4PromptBuilder.sanitizeModelOutput("  [No Speech] "))
        assertEquals("", Gemma4PromptBuilder.sanitizeModelOutput("   "))
    }
}
