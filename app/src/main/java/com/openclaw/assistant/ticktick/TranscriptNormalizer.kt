package com.openclaw.assistant.ticktick

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic, zero-LLM post-STT normalizer:
 * 1. Converts spoken symbols ("hashtag <word>" -> "#<word>").
 * 2. Applies explicit user/project alias mappings ("naughty" -> "Noory").
 * 3. Applies phonetic + Levenshtein matching for domain/Arabic-origin vocabulary.
 */
object TranscriptNormalizer {

    data class DomainLexicon(
        val terms: List<String>,
        val explicitAliases: Map<String, String>,
    )

    val DEFAULT_RAW_VOCABULARY: String = """
        Noory = naughty, nouri, nuri, nori, no-tee, notee, moo-ree, mooree, moody, noorie, nory, nuori, new re
        Azmy = azmi, asmi, azmee, as me
        Zebeeb = zabib, zabeeb, zebib, the bib, zeeb
        Insights Base = insight base, insights base
        Brain II = brain two, brain 2
        TickTick = tick tick, tic tic, tik tik
        Antigravity = anti gravity
        OpenClaw = open claw
        WakeHermesClaw = wake hermes claw
        Garmin
        Maram = marom, marram, moram
        Yasmine = yasmin, jasmine
        Haider = hayder, heider, hider, haidar
        Alaa = alaa, alah
        Aya = aya, aia, ayah
        Sayed = saied, syed, said
        Mostafa = mustafa, moustafa, mostapha
        Sadaks = sadak, sadaqa, sadaqah
        Finanzamt Schöneberg = finanzamt schoenberg, finanzamt schoeneberg
        Finanzamt
        Bürgeramt = burgeramt, buergeramt, berger ant, berger amt, burger amt
        Anmeldung
        Quartiersweg = quartiers weg, quarters way
        Wohnpark Südkreuz = wohnpark sudkreuz, wohnpark suedkreuz
        Wohnpark
        Südkreuz = sudkreuz, suedkreuz, sud kreuz, sued kreuz, sid kreuz
        Schöneberg = schoenberg, schoeneberg, shoneberg, schoen berg, shone berg
        Bildungszeit = bildungs zeit
        Weiterbildungsbudget
        Gasthörer = gasthorer
        Techniker Krankenkasse = techniker, krankenkasse
        Familienversicherung
        Fraunhofer IPK = fraunhofer
        LUNOS = lunos
        Levothyroxine = levo thyroxine
        ROS2
    """.trimIndent()

    fun parseVocabulary(rawConfig: String): DomainLexicon {
        val terms = mutableListOf<String>()
        val aliases = linkedMapOf<String, String>()

        rawConfig.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) return@forEach
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val canonical = trimmed.substring(0, eqIdx).trim()
                if (canonical.isNotEmpty()) {
                    terms += canonical
                    trimmed.substring(eqIdx + 1)
                        .split(',')
                        .map { it.trim().lowercase(Locale.ROOT) }
                        .filter { it.isNotEmpty() }
                        .forEach { alias -> aliases[alias] = canonical }
                }
            } else {
                terms += trimmed
            }
        }
        return DomainLexicon(terms = terms.distinct(), explicitAliases = aliases)
    }

    /**
     * Merges remote weekly-synced glossary lines with user-edited local glossary lines.
     * Local user additions and aliases are always preserved.
     */
    fun mergeGlossaries(syncedRaw: String, userCustomRaw: String): String {
        val synced = parseVocabulary(syncedRaw)
        val user = parseVocabulary(userCustomRaw)
        if (synced.terms.isEmpty()) return userCustomRaw

        val mergedAliasesByTerm = linkedMapOf<String, LinkedHashSet<String>>()
        val canonicalCase = linkedMapOf<String, String>()

        fun addFrom(lexicon: DomainLexicon) {
            for (term in lexicon.terms) {
                val key = term.lowercase(Locale.ROOT)
                canonicalCase.putIfAbsent(key, term)
                mergedAliasesByTerm.getOrPut(key) { linkedSetOf() }
            }
            for ((alias, target) in lexicon.explicitAliases) {
                val key = target.lowercase(Locale.ROOT)
                canonicalCase.putIfAbsent(key, target)
                mergedAliasesByTerm.getOrPut(key) { linkedSetOf() }.add(alias)
            }
        }

        addFrom(synced)
        addFrom(user)

        return canonicalCase.entries.joinToString("\n") { (key, canonical) ->
            val aliases = mergedAliasesByTerm[key].orEmpty()
            if (aliases.isEmpty()) canonical
            else "$canonical = ${aliases.joinToString(", ")}"
        }
    }

    fun normalize(rawTranscript: String, customVocabularyRaw: String = DEFAULT_RAW_VOCABULARY): String {
        var text = rawTranscript.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return ""

        // 1. Spoken hashtag & punctuation conversion ("hashtag foo" -> "#foo", "question mark" -> "?")
        text = text.replace(Regex("""\bhash\s*tag\s+([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)) { match ->
            "#${match.groupValues[1]}"
        }
        text = text.replace(
            Regex("""[,.]?\s*\b(?:question\s+mark|fragezeichen)\b[,.?!]*""", RegexOption.IGNORE_CASE),
            "?",
        )
        text = text.replace(
            Regex("""[,.]?\s*\b(?:exclamation\s+(?:mark|point)|ausrufezeichen)\b[,.?!]*""", RegexOption.IGNORE_CASE),
            "!",
        )

        // 1b. Hesitation filler cleanup (WisprFlow style)
        text = text.replace(
            Regex("""(?:^|\s)\b(?:u+m+|u+h+|e+r+m*|h+m+m*|ä+h+m?)\b[,.]*(?=\s|$)""", RegexOption.IGNORE_CASE),
            " ",
        )
        text = text.trim().replace(Regex("\\s+"), " ")

        // 2. Apply multi-word explicit aliases first (longest-first, e.g. "tick tick" -> "TickTick")
        val lexicon = parseVocabulary(customVocabularyRaw.ifBlank { DEFAULT_RAW_VOCABULARY })
        val sortedMultiAliases = lexicon.explicitAliases.entries
            .filter { it.key.contains(' ') || it.key.contains('-') }
            .sortedByDescending { it.key.length }

        for ((alias, canonical) in sortedMultiAliases) {
            val pattern = Regex("""(#?)\b${Regex.escape(alias)}\b""", RegexOption.IGNORE_CASE)
            text = text.replace(pattern) { match ->
                "${match.groupValues[1]}$canonical"
            }
        }

        // 2b. Collapse accidental duplicate-word stutters after multi-word alias mapping
        text = text.replace(
            Regex("""\b([A-Za-zÄÖÜäöüß]{2,})(?:\s+\1\b)+""", RegexOption.IGNORE_CASE),
            "$1",
        )

        // 3. Token-level alias & phonetic fuzzy replacement
        val singleWordTerms = lexicon.terms.filter { !it.contains(' ') }
        val tokens = text.split(' ').toMutableList()

        for (i in tokens.indices) {
            val token = tokens[i]
            val leadingPunct = token.takeWhile { it == '#' || it == '@' || it == '"' || it == '(' }
            val trailingPunct = token.takeLastWhile { it in setOf('.', ',', '!', '?', ':', ';', '"', ')') }
            val coreEnd = (token.length - trailingPunct.length).coerceAtLeast(leadingPunct.length)
            val core = token.substring(leadingPunct.length, coreEnd)
            if (core.isEmpty()) continue

            val lowerCore = core.lowercase(Locale.ROOT)
            val prevLower = tokens.getOrNull(i - 1)?.lowercase(Locale.ROOT)?.trimEnd('.', ',', ':', ';')
            val nextLower = tokens.getOrNull(i + 1)?.lowercase(Locale.ROOT)?.trimEnd('.', ',', ':', ';')
            val inProjectContext = leadingPunct.contains('#') ||
                leadingPunct.contains('@') ||
                nextLower in CONTEXT_AFTER_WORDS ||
                prevLower in CONTEXT_BEFORE_WORDS

            // 3a. Explicit single-word alias
            val explicit = lexicon.explicitAliases[lowerCore]
            if (explicit != null) {
                // Guard "naughty" / "moody" unless near project/tag context or exact uncommon alias
                val needsContext = lowerCore in AMBIGUOUS_ENGLISH_WORDS
                if (!needsContext || inProjectContext) {
                    tokens[i] = "$leadingPunct$explicit$trailingPunct"
                    continue
                }
            }

            // 3b. Exact case-canonical match
            val exactCanonical = singleWordTerms.firstOrNull { it.equals(core, ignoreCase = true) }
            if (exactCanonical != null) {
                tokens[i] = "$leadingPunct$exactCanonical$trailingPunct"
                continue
            }

            // 3c. Phonetic + edit-distance match (Latin/German words only; preserve Arabic/non-Latin scripts verbatim)
            val isLatinToken = core.any { it in 'A'..'Z' || it in 'a'..'z' || it in "äöüßÄÖÜ" }
            if (isLatinToken && (inProjectContext || core.length >= 5) && lowerCore !in COMMON_STOP_WORDS) {
                val bestMatch = findBestPhoneticMatch(lowerCore, singleWordTerms, strict = !inProjectContext)
                if (bestMatch != null) {
                    tokens[i] = "$leadingPunct$bestMatch$trailingPunct"
                }
            }
        }

        val joined = tokens.joinToString(" ")
            .replace(Regex("\\s+([?.!,;:])"), "$1")
            .replace(Regex("([?.!,;:])([^\\s\"')\\d/:])"), "$1 $2")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd(',')
        return capitalizeSentences(joined)
    }

    private fun capitalizeSentences(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        var capitalizeNext = true
        for (ch in input) {
            if (capitalizeNext && ch.isLetter()) {
                sb.append(ch.titlecaseChar())
                capitalizeNext = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '?' || ch == '!') {
                    capitalizeNext = true
                } else if (!ch.isWhitespace() && ch != '"' && ch != '(') {
                    capitalizeNext = false
                }
            }
        }
        return sb.toString()
    }

    /**
     * Returns prioritized canonical terms to pass to Android's on-device speech recognizer
     * (`RecognizerIntent.EXTRA_BIASING_STRINGS`, API 33+).
     */
    fun biasingTerms(customVocabularyRaw: String = DEFAULT_RAW_VOCABULARY, maxTerms: Int = 80): ArrayList<String> {
        val merged = mergeGlossaries(DEFAULT_RAW_VOCABULARY, customVocabularyRaw)
        val lexicon = parseVocabulary(merged)
        val ordered = lexicon.terms
            .map { it.trim() }
            .filter { it.length in 3..48 }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(
                compareByDescending<String> { term ->
                    var score = 0
                    if (term.any { it in "äöüßÄÖÜ" }) score += 4
                    if (term.contains(' ')) score += 2
                    if (term.any { it.isUpperCase() }) score += 1
                    score
                }
            )
            .take(maxTerms)
        return ArrayList(ordered)
    }

    private fun findBestPhoneticMatch(
        word: String,
        candidates: List<String>,
        strict: Boolean,
    ): String? {
        val foldedWord = foldGermanUmlauts(word)
        val wordSkeleton = consonantSkeleton(foldedWord)
        var bestTerm: String? = null
        var bestScore = 0.0

        for (candidate in candidates) {
            val candFolded = foldGermanUmlauts(candidate.lowercase(Locale.ROOT))
            val sim = normalizedSimilarity(foldedWord, candFolded)
            val skelSim = normalizedSimilarity(wordSkeleton, consonantSkeleton(candFolded))
            val combined = sim * 0.65 + skelSim * 0.35
            val threshold = if (strict) 0.78 else 0.68
            if (combined >= threshold && combined > bestScore) {
                bestScore = combined
                bestTerm = candidate
            }
        }
        return bestTerm
    }

    internal fun foldGermanUmlauts(s: String): String {
        return s.lowercase(Locale.ROOT)
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
    }

    internal fun consonantSkeleton(s: String): String {
        val lower = foldGermanUmlauts(s)
            .replace("sch", "sh")
            .replace("ph", "f")
            .replace("gh", "g")
            .replace("kh", "k")
            .replace("oo", "u")
            .replace("ee", "i")
            .replace("ou", "u")
        val sb = StringBuilder()
        for (idx in lower.indices) {
            val c = lower[idx]
            if (idx == 0 || c !in charArrayOf('a', 'e', 'i', 'o', 'u', 'y', 'h', 'w')) {
                if (sb.isEmpty() || sb.last() != c) {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    internal fun normalizedSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - (levenshtein(a, b).toDouble() / maxLen.toDouble())
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                prev = temp
            }
        }
        return dp[b.length]
    }

    private val AMBIGUOUS_ENGLISH_WORDS = setOf("naughty", "moody", "said", "army")
    private val CONTEXT_AFTER_WORDS = setOf("app", "project", "team", "backlog", "task", "tasks", "build")
    private val CONTEXT_BEFORE_WORDS = setOf("for", "under", "to", "with", "call", "message", "ping", "email", "ask", "tell")
    private val COMMON_STOP_WORDS = setOf(
        "please", "update", "those", "these", "there", "their", "about", "tomorrow",
        "today", "reminder", "remind", "experiments", "experiment", "project", "create",
        "check", "review", "meeting", "schedule", "around", "something", "research",
    )
}
