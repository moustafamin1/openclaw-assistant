package com.openclaw.assistant.ticktick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gemma4PromptBuilderTest {

    @Test
    fun buildEnglishSystemInstructionIsStrictlyEnglishAndFreeOfWordDump() {
        val prompt = Gemma4PromptBuilder.buildEnglishSystemInstruction()
        assertTrue(prompt.contains("ONLY in English"))
        assertTrue(prompt.contains("[SILENCE]"))
        assertFalse(prompt.contains("German"))
        assertFalse(prompt.contains("Arabic"))
        assertFalse(prompt.contains("Personal Vocabulary"))
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
