package com.openclaw.assistant.ticktick

import java.util.Locale

/**
 * Builds the English-only dictation prompt for on-device Gemma 4 E2B native audio
 * transcription (zero German/Arabic dilution, zero bloated vocabulary dump) plus output cleanup.
 */
object Gemma4PromptBuilder {

    const val SILENCE_TOKEN = "[SILENCE]"

    fun buildEnglishSystemInstruction(): String = """
You are an English voice-to-task dictation transcriber.
Transcribe ONLY in English.
Output ONLY the clean, properly capitalized final English text — no quotes, markdown, prefixes, or explanations.
Remove hesitation fillers (um, uh) and resolve immediate verbal self-corrections.
Use natural English spelling, compound words, and technical terms accurately based on sentence context.
Write numbers as digits (e.g., 3 instead of three).
If there is no speech, output exactly $SILENCE_TOKEN.
    """.trimIndent()

    fun sanitizeModelOutput(rawOutput: String): String {
        if (rawOutput.isBlank()) return ""

        // Strip any defensive thinking tags in case a model emits them despite enable_thinking=false
        var text = rawOutput
            .replace(Regex("""<thought>[\s\S]*?</thought>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<\|thought\|>[\s\S]*?<\|/thought\|>""", RegexOption.IGNORE_CASE), " ")
            .trim()

        // Strip common leading labels if any leaked
        text = text.replace(
            Regex("""^(?:transcription|transcript|task|output|audio|text|corrected)\s*:\s*""", RegexOption.IGNORE_CASE),
            "",
        ).trim()

        // Strip enclosing matching quotes or backticks
        if (text.length >= 2) {
            val first = text.first()
            val last = text.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'') || (first == '`' && last == '`')) {
                text = text.substring(1, text.length - 1).trim()
            }
        }

        text = text.replace(Regex("\\s+"), " ").trim()
        val lower = text.lowercase(Locale.ROOT)
        if (lower.isEmpty() ||
            lower == "[silence]" ||
            lower == "silence" ||
            lower == "[no speech]" ||
            lower == "(no speech)" ||
            lower == "[inaudible]"
        ) {
            return ""
        }
        return text
    }
}
