package com.openclaw.assistant.ticktick

import java.util.Locale

/**
 * Builds compact personal-vocabulary hints and Wispr-Flow-style dictation prompts
 * for on-device Gemma 4 E2B native audio transcription, plus output cleanup.
 */
object Gemma4PromptBuilder {

    const val SILENCE_TOKEN = "[SILENCE]"

    fun buildCompactVocabulary(
        customVocabularyRaw: String = TranscriptNormalizer.DEFAULT_RAW_VOCABULARY,
        contextIndex: ContextIndex? = null,
        maxTerms: Int = 60,
    ): List<String> {
        val explicitLexicon = TranscriptNormalizer.parseVocabulary(customVocabularyRaw)
        val mergedRanked = TranscriptNormalizer.biasingTerms(
            customVocabularyRaw = customVocabularyRaw,
            maxTerms = maxTerms * 3,
            contextIndex = contextIndex,
        )

        val scored = linkedMapOf<String, Pair<String, Double>>()

        fun addCandidate(rawTerm: String, baseScore: Double) {
            val clean = rawTerm.trim()
            if (clean.length !in 2..48) return
            val key = clean.lowercase(Locale.ROOT)
            var score = baseScore
            if (contextIndex != null) {
                val w = contextIndex.unigramWeight(key)
                if (w > 0.0) score += kotlin.math.ln(1.0 + w) * 3.0
            }
            if (Regex("^[A-Z]{2,}s?[0-9]*$").matches(clean)) score += 4.0
            if (clean.any { it in "äöüßÄÖÜ" }) score += 3.0
            if (clean.any { it.isUpperCase() }) score += 1.0
            val prev = scored[key]
            if (prev == null || score > prev.second) {
                scored[key] = (prev?.first ?: clean) to score
            }
        }

        // Prioritize explicit user/synced terms by frequency in Second Brain rather than alphabetical order
        val aliasedCanonicals = explicitLexicon.explicitAliases.values
            .mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
        for (term in explicitLexicon.terms) {
            val bonus = if (term.lowercase(Locale.ROOT) in aliasedCanonicals) 6.0 else 0.0
            addCandidate(term, baseScore = 25.0 + bonus)
        }

        mergedRanked.forEachIndexed { idx, term ->
            val rankBonus = ((mergedRanked.size - idx).toDouble() / mergedRanked.size.coerceAtLeast(1)) * 10.0
            addCandidate(term, baseScore = 10.0 + rankBonus)
        }

        return scored.values
            .sortedByDescending { it.second }
            .map { it.first }
            .take(maxTerms)
    }

    fun buildSystemInstruction(vocabularyTerms: List<String>): String {
        val vocabLine = if (vocabularyTerms.isNotEmpty()) {
            "Personal Vocabulary: ${vocabularyTerms.joinToString(", ")}"
        } else {
            "Personal Vocabulary: (none)"
        }
        return """
Verbatim voice-to-task transcriber (English, German, Arabic, or mixed).
Output ONLY final clean text: no quotes, markdown, prefixes, or fillers. Resolve verbal self-corrections.
Convert leading spoken "Brain..." or "at brain..." to "@brain: ...", and "hashtag X" to "#X".
Use Personal Vocabulary spellings ONLY when phonetically spoken; never alter unrelated everyday words.
Output $SILENCE_TOKEN if no speech.
$vocabLine
        """.trimIndent()
    }

    fun sanitizeModelOutput(rawOutput: String): String {
        if (rawOutput.isBlank()) return ""

        // Strip any defensive thinking tags in case a model emits them despite enable_thinking=false
        var text = rawOutput
            .replace(Regex("""<thought>[\s\S]*?</thought>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<\|thought\|>[\s\S]*?<\|/thought\|>""", RegexOption.IGNORE_CASE), " ")
            .trim()

        // Strip common leading labels if any leaked
        text = text.replace(
            Regex("""^(?:transcription|transcript|task|output|audio|text)\s*:\s*""", RegexOption.IGNORE_CASE),
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
