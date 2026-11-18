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
        Noory app = naughty app, naughty up, noory up, nouri app, nouri up, nuri app, nuri up, nori app, nori up, newry up, moody app, moody up, new re app, new re up
        Noory = naughty, nouri, nuri, nori, no-tee, notee, moo-ree, mooree, moody, noorie, nory, nuori, new re, newry, norie, norry, noori, nurie
        Azmy = azmi, asmi, azmee, as me
        Zebeeb = zabib, zabeeb, zebib, the bib, zeeb
        Insights Base = insight base, insights base
        Brain II = brain two, brain 2
        @brain = at brain, at-brain
        Brain = brian
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
        Südkreuz = sudkreuz, suedkreuz, sud kreuz, sued kreuz, sid kreuz, sweet cross, suit cross, sweet cruise, suit cruise, sud cross, sued cross
        Schöneberg = schoenberg, schoeneberg, schonberg, shownberg, shoneberg, show number
        Bildungszeit = bildungs zeit
        Weiterbildungsbudget
        Gasthörer = gasthorer
        Techniker Krankenkasse = techniker, krankenkasse
        Familienversicherung
        Fraunhofer IPK = fraunhofer
        LUNOS = lunos
        Levothyroxine = levo thyroxine
        ROS2
        OKRs = okay ours, okey ours, ok ours, oh kay ours, oh kay ares, ok ares, ok r's, ok rs, okey rs, okay rs, o k r s, okay are, okey are, okr
        TK = tkmax, tk max, t k, tee kay
        KPIs = k p i s, kpi, kay pee eyes, keep eyes
        Evals = e vals, i vals, evils, eval
        APK = a p k, ay pee kay
        API = a p i, ay pee eye
        MVP = m v p, em vee pee
        Grafana
        BigQuery
        Azab
        Tarek
        Asmaa
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

    fun normalize(
        rawTranscript: String,
        customVocabularyRaw: String = DEFAULT_RAW_VOCABULARY,
        contextIndex: ContextIndex? = null,
    ): String {
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

        // 1b. Hesitation filler & verbal self-correction cleanup (WisprFlow style)
        text = text.replace(
            Regex("""(?:^|\s)\b(?:u+m+|u+h+|e+r+m*|h+m+m*|ä+h+m?)\b[,.]*(?=\s|$)""", RegexOption.IGNORE_CASE),
            " ",
        )
        // Strip immediate verbal self-corrections ("tkmax I mean tk" -> "tk", "Friday, I mean Monday" -> "Monday")
        text = text.replace(
            Regex("""\b[A-Za-zÄÖÜäöüß0-9_-]+[,]?\s+\b(?:i\s+mean|sorry\s+no|no\s+wait|actually\s+no)\b[,]?\s+""", RegexOption.IGNORE_CASE),
            "",
        )
        text = text.trim().replace(Regex("\\s+"), " ")

        // 1c. Spoken @brain delegation prefix ("Brain ...", "Brain, ...", "@brain ...", "at brain ...")
        text = text.replace(
            Regex("""(?:^|[.!?]\s+)(?:(?:hey|please)\s+)?(?:at\s+|@)?(?:brain|brian)\b[:,\s]*""", RegexOption.IGNORE_CASE),
        ) { match ->
            val prefix = match.value.takeWhile { it == '.' || it == '!' || it == '?' || it.isWhitespace() }
            "${prefix}@brain: "
        }
        text = text.trim().replace(Regex("\\s+"), " ")

        // 2. Apply multi-word explicit aliases first (longest-first, e.g. "tick tick" -> "TickTick")
        val lexicon = parseVocabulary(customVocabularyRaw.ifBlank { DEFAULT_RAW_VOCABULARY })
        val sortedMultiAliases = lexicon.explicitAliases.entries
            .filter { it.key.contains(' ') || it.key.contains('-') }
            .sortedByDescending { it.key.length }

        for ((alias, canonical) in sortedMultiAliases) {
            val pattern = Regex("""(#?)\b${Regex.escape(alias)}\b""", RegexOption.IGNORE_CASE)
            val endsWithUp = alias.endsWith(" up")
            text = text.replace(pattern) { match ->
                if (endsWithUp) {
                    val prevWord = text.substring(0, match.range.first)
                        .trimEnd()
                        .substringAfterLast(' ')
                        .lowercase(Locale.ROOT)
                        .trimEnd('.', ',', ':', ';', '!', '?')
                    if (prevWord in PHRASAL_VERB_BEFORE_UP) return@replace match.value
                }
                "${match.groupValues[1]}$canonical"
            }
        }

        // 2b. Collapse accidental duplicate-word stutters after multi-word alias mapping
        text = text.replace(
            Regex("""\b([A-Za-zÄÖÜäöüß]{2,})(?:\s+\1\b)+""", RegexOption.IGNORE_CASE),
            "$1",
        )

        // 3. Multi-word phonetic sliding window (2-3 words) -> compound/multi-word glossary terms
        val singleWordTerms = lexicon.terms.filter { !it.contains(' ') }
        val multiWordTerms = lexicon.terms.filter { it.contains(' ') }
        val compoundSingleTerms = singleWordTerms.filter { it.length >= 7 }
        val tokens = applyMultiWordPhoneticWindow(
            tokens = text.split(' '),
            multiWordTerms = multiWordTerms,
            compoundSingleTerms = compoundSingleTerms,
        ).toMutableList()

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
                val blockedByNaughtyCopula = lowerCore == "naughty" &&
                    prevLower in NAUGHTY_COPULA_BEFORE_WORDS &&
                    !inProjectContext
                val needsContext = lowerCore in AMBIGUOUS_ENGLISH_WORDS
                if (!blockedByNaughtyCopula && (!needsContext || inProjectContext)) {
                    val cleanExplicit = if (leadingPunct.contains('@') && explicit.startsWith('@')) {
                        explicit.removePrefix("@")
                    } else if (leadingPunct.contains('#') && explicit.startsWith('#')) {
                        explicit.removePrefix("#")
                    } else {
                        explicit
                    }
                    tokens[i] = "$leadingPunct$cleanExplicit$trailingPunct"
                    continue
                }
            }

            // 3b. Exact case-canonical match
            if (leadingPunct.contains('@') && core.equals("brain", ignoreCase = true)) {
                tokens[i] = "@brain$trailingPunct"
                continue
            }
            val exactCanonical = singleWordTerms.firstOrNull { it.equals(core, ignoreCase = true) }
            if (exactCanonical != null) {
                val cleanCanonical = if (leadingPunct.contains('@') && exactCanonical.startsWith('@')) {
                    exactCanonical.removePrefix("@")
                } else if (leadingPunct.contains('#') && exactCanonical.startsWith('#')) {
                    exactCanonical.removePrefix("#")
                } else {
                    exactCanonical
                }
                tokens[i] = "$leadingPunct$cleanCanonical$trailingPunct"
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

        // 3c.5. Offline Second Brain + TickTick phonetic & bigram context rescoring
        if (contextIndex != null && !contextIndex.isEmpty()) {
            contextIndex.correctTokens(tokens)
        }

        // 3d. Merge "Noory" + "up" -> "Noory app" (Google STT often mishears "app" as "up" at phrase end)
        val mergedTokens = mutableListOf<String>()
        var ti = 0
        while (ti < tokens.size) {
            val curr = tokens[ti]
            val next = tokens.getOrNull(ti + 1)
            val prevCore = mergedTokens.lastOrNull()?.lowercase(Locale.ROOT)?.trim('.', ',', ':', ';', '!', '?')
            val currTrail = curr.takeLastWhile { it in setOf('.', ',', '!', '?', ':', ';', '"', ')') }
            val currCore = curr.substring(0, (curr.length - currTrail.length).coerceAtLeast(0))
            if (next != null && currTrail.isEmpty() && currCore.equals("Noory", ignoreCase = true)) {
                val nextTrail = next.takeLastWhile { it in setOf('.', ',', '!', '?', ':', ';', '"', ')') }
                val nextCore = next.substring(0, (next.length - nextTrail.length).coerceAtLeast(0))
                if (nextCore.equals("up", ignoreCase = true) && prevCore !in PHRASAL_VERB_BEFORE_UP) {
                    mergedTokens += "${currCore} app$nextTrail"
                    ti += 2
                    continue
                }
            }
            mergedTokens += curr
            ti++
        }

        val joined = mergedTokens.joinToString(" ")
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
                if (ch == '.' || ch == '?' || ch == '!' || ch == ':') {
                    capitalizeNext = true
                } else if (!ch.isWhitespace() && ch != '"' && ch != '(') {
                    capitalizeNext = false
                }
            }
        }
        return sb.toString()
    }

    /**
     * Picks the best recognition hypothesis from SpeechRecognizer's N-best list by
     * favoring hypotheses that contain canonical domain terms/acronyms directly.
     * Falls back to the top hypothesis while preserving rank tie-breaking.
     */
    fun selectBestHypothesis(
        hypotheses: List<String>,
        customVocabularyRaw: String = DEFAULT_RAW_VOCABULARY,
        contextIndex: ContextIndex? = null,
    ): String {
        val cleaned = hypotheses.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        if (cleaned.size == 1) return cleaned.first()

        val merged = mergeGlossaries(DEFAULT_RAW_VOCABULARY, customVocabularyRaw)
        val lexicon = parseVocabulary(merged)
        val terms = lexicon.terms.map { it.trim() }.filter { it.length >= 2 }

        var bestCandidate = cleaned.first()
        var bestScore = Double.NEGATIVE_INFINITY

        cleaned.forEachIndexed { rankIndex, candidate ->
            var score = if (contextIndex != null && !contextIndex.isEmpty()) {
                contextIndex.scoreHypothesis(candidate, rankIndex, cleaned.size)
            } else {
                (cleaned.size - rankIndex) * 2.0
            }
            val lower = candidate.lowercase(Locale.ROOT)
            for (term in terms) {
                val termLower = term.lowercase(Locale.ROOT)
                val regex = Regex("""(?<![A-Za-z0-9])${Regex.escape(termLower)}(?![A-Za-z0-9])""")
                if (regex.containsMatchIn(lower)) {
                    score += if (term.any { it.isUpperCase() } && term.length <= 6) 10.0 else 6.0
                }
                val exactRegex = Regex("""(?<![A-Za-z0-9])${Regex.escape(term)}(?![A-Za-z0-9])""")
                if (exactRegex.containsMatchIn(candidate)) {
                    score += 4.0
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestCandidate = candidate
            }
        }
        return bestCandidate
    }

    /**
     * Returns prioritized canonical terms to pass to Android's on-device speech recognizer
     * (`RecognizerIntent.EXTRA_BIASING_STRINGS`, API 33+).
     */
    fun biasingTerms(customVocabularyRaw: String = DEFAULT_RAW_VOCABULARY, maxTerms: Int = 100): ArrayList<String> {
        val merged = mergeGlossaries(DEFAULT_RAW_VOCABULARY, customVocabularyRaw)
        val lexicon = parseVocabulary(merged)
        val aliasedCanonicals = lexicon.explicitAliases.values.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
        val ordered = lexicon.terms
            .map { it.trim() }
            .filter { it.length in 2..48 }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(
                compareByDescending<String> { term ->
                    var score = 0
                    if (Regex("^[A-Z]{2,}s?[0-9]*$").matches(term)) score += 6
                    if (term.lowercase(Locale.ROOT) in aliasedCanonicals) score += 5
                    if (term.any { it in "äöüßÄÖÜ" }) score += 4
                    if (term.contains(' ')) score += 2
                    if (term.any { it.isUpperCase() }) score += 1
                    score
                }
            )
            .take(maxTerms)
        return ArrayList(ordered)
    }

    private fun applyMultiWordPhoneticWindow(
        tokens: List<String>,
        multiWordTerms: List<String>,
        compoundSingleTerms: List<String>,
    ): List<String> {
        if (tokens.size < 2 || (multiWordTerms.isEmpty() && compoundSingleTerms.isEmpty())) return tokens
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            var matched = false
            for (winSize in listOf(3, 2)) {
                if (i + winSize > tokens.size) continue
                val slice = tokens.subList(i, i + winSize)
                val firstLead = slice.first().takeWhile { it == '#' || it == '@' || it == '"' || it == '(' }
                val lastTrail = slice.last().takeLastWhile { it in setOf('.', ',', '!', '?', ':', ';', '"', ')') }
                val cores = slice.mapIndexed { idx, tok ->
                    val lead = if (idx == 0) firstLead else ""
                    val trail = if (idx == slice.lastIndex) lastTrail else ""
                    val end = (tok.length - trail.length).coerceAtLeast(lead.length)
                    tok.substring(lead.length, end)
                }
                val validWindow = cores.all { c ->
                    c.isNotEmpty() &&
                        c.any { ch -> ch in 'A'..'Z' || ch in 'a'..'z' || ch in "äöüßÄÖÜ" } &&
                        !c.startsWith("@") &&
                        !c.startsWith("#") &&
                        c.lowercase(Locale.ROOT) !in COMMON_STOP_WORDS &&
                        c.lowercase(Locale.ROOT) !in WINDOW_STOP_WORDS
                }
                if (!validWindow) continue

                val spacedPhrase = cores.joinToString(" ").lowercase(Locale.ROOT)
                val joinedPhrase = cores.joinToString("").lowercase(Locale.ROOT)

                // If the leading 2 tokens already form an exact canonical multi-word term, don't swallow a 3rd word.
                if (winSize == 3 && cores.size >= 2) {
                    val prefix2 = "${cores[0]} ${cores[1]}"
                    if (multiWordTerms.any { it.equals(prefix2, ignoreCase = true) }) continue
                }
                if (multiWordTerms.any { it.equals(spacedPhrase, ignoreCase = true) }) {
                    out += slice
                    i += winSize
                    matched = true
                    break
                }

                val sameWordCountMulti = multiWordTerms.filter { it.split(' ').size == winSize }
                val multiThreshold = if (spacedPhrase.length >= 10) 0.68 else 0.76
                val multiMatch = findBestWindowMatch(spacedPhrase, sameWordCountMulti, threshold = multiThreshold)
                val compoundMatch = if (multiMatch == null && winSize == 2) {
                    findBestWindowMatch(joinedPhrase, compoundSingleTerms, threshold = 0.80)
                } else {
                    null
                }
                val winner = multiMatch ?: compoundMatch
                if (winner != null) {
                    out += "$firstLead$winner$lastTrail"
                    i += winSize
                    matched = true
                    break
                }
            }
            if (!matched) {
                out += tokens[i]
                i++
            }
        }
        return out
    }

    private fun findBestWindowMatch(
        phrase: String,
        candidates: List<String>,
        threshold: Double,
    ): String? {
        val folded = foldGermanUmlauts(phrase)
        val skeleton = consonantSkeleton(folded)
        var best: String? = null
        var bestScore = 0.0
        for (candidate in candidates) {
            val candFolded = foldGermanUmlauts(candidate.lowercase(Locale.ROOT))
            val sim = normalizedSimilarity(folded, candFolded)
            val skelSim = normalizedSimilarity(skeleton, consonantSkeleton(candFolded))
            val combined = sim * 0.65 + skelSim * 0.35
            if (combined >= threshold && combined > bestScore) {
                bestScore = combined
                best = candidate
            }
        }
        return best
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
            .replace(Regex("c([eiy])"), "s$1")
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

    private val AMBIGUOUS_ENGLISH_WORDS = setOf("moody", "said", "army")
    private val NAUGHTY_COPULA_BEFORE_WORDS = setOf("be", "being", "been", "acting", "behaving", "very", "so")
    private val PHRASAL_VERB_BEFORE_UP = setOf("set", "back", "pick", "clean", "hook", "spin", "start", "stand", "warm", "wake")
    private val CONTEXT_AFTER_WORDS = setOf("app", "project", "team", "backlog", "task", "tasks", "build")
    private val CONTEXT_BEFORE_WORDS = setOf("for", "under", "to", "with", "call", "message", "ping", "email", "ask", "tell")
    private val COMMON_STOP_WORDS = setOf(
        "please", "update", "those", "these", "there", "their", "about", "tomorrow",
        "today", "reminder", "remind", "experiments", "experiment", "project", "create",
        "check", "review", "meeting", "schedule", "around", "something", "research",
    )
    private val WINDOW_STOP_WORDS = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "do", "for", "from",
        "get", "go", "have", "he", "her", "his", "i", "if", "in", "is", "it", "me",
        "my", "no", "not", "of", "on", "or", "our", "out", "she", "so", "that", "the",
        "this", "to", "up", "us", "we", "will", "with", "you", "your",
    )
}
