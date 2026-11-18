package com.openclaw.assistant.ticktick

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

@Serializable
data class ContextUnigramDto(
    val w: Double = 1.0,
    val c: String = "",
)

@Serializable
data class ContextIndexPayloadDto(
    val unigrams: Map<String, ContextUnigramDto> = emptyMap(),
    val bigrams: Map<String, Double> = emptyMap(),
)

@Serializable
data class StandaloneContextAssetDto(
    val version: Long = 0L,
    val updatedAt: String = "",
    val contextIndex: ContextIndexPayloadDto = ContextIndexPayloadDto(),
)

data class ContextUnigram(
    val key: String,
    val canonical: String,
    val weight: Double,
)

/**
 * Offline O(1) phonetic-bucket + bigram context scorer built from Second Brain + TickTick history.
 */
class ContextIndex(
    val unigrams: Map<String, ContextUnigram>,
    val bigrams: Map<String, Double>,
) {
    private val phoneticBuckets: Map<String, List<ContextUnigram>> by lazy {
        val buckets = hashMapOf<String, MutableList<ContextUnigram>>()
        for (entry in unigrams.values) {
            if (entry.key.length < 2 || entry.weight < 1.5) continue
            for (pkey in ContextLanguageModel.bucketKeys(entry.key)) {
                buckets.getOrPut(pkey) { mutableListOf() }.add(entry)
            }
        }
        buckets
    }

    fun unigramWeight(word: String): Double = unigrams[word.lowercase(Locale.ROOT)]?.weight ?: 0.0

    fun bigramWeight(prev: String?, next: String?): Double {
        if (prev.isNullOrBlank() || next.isNullOrBlank()) return 0.0
        val p = prev.lowercase(Locale.ROOT)
        val n = next.lowercase(Locale.ROOT)
        return bigrams["$p $n"] ?: 0.0
    }

    fun isEmpty(): Boolean = unigrams.isEmpty()

    /**
     * Top N-best hypothesis scoring combining STT rank prior, corpus unigrams, and corpus bigrams.
     */
    fun scoreHypothesis(candidate: String, rankIndex: Int, totalCandidates: Int): Double {
        val rankPrior = (totalCandidates - rankIndex) * 2.0
        val rawTokens = candidate.trim()
            .split(Regex("\\s+"))
            .map { ContextLanguageModel.stripTokenPunct(it).second }
            .filter { it.isNotEmpty() }
        if (rawTokens.isEmpty()) return rankPrior

        var score = rankPrior
        for (i in rawTokens.indices) {
            val tok = rawTokens[i]
            val lower = tok.lowercase(Locale.ROOT)
            val uni = unigrams[lower]
            if (uni != null) {
                score += 0.75 * ln(1.0 + uni.weight)
                if (uni.canonical != lower && tok == uni.canonical) {
                    score += 1.5
                }
            }
            if (i + 1 < rawTokens.size) {
                val bi = bigramWeight(lower, rawTokens[i + 1])
                if (bi > 0.0) {
                    score += 1.8 * ln(1.0 + bi)
                }
            }
        }
        return score
    }

    /**
     * Corrects tokens in-place using O(1) phonetic bucket lookup scored against neighbor bigrams.
     */
    fun correctTokens(
        tokens: MutableList<String>,
        protectedCommonWords: Set<String> = DEFAULT_PROTECTED_WORDS,
        phrasalVerbBeforeUp: Set<String> = DEFAULT_PHRASAL_BEFORE_UP,
    ) {
        if (unigrams.isEmpty() || tokens.isEmpty()) return

        collapseSplitTerms(tokens, protectedCommonWords)

        val cores = tokens.map { ContextLanguageModel.stripTokenPunct(it) }
        val resolvedLowers = cores.map { it.second.lowercase(Locale.ROOT) }.toMutableList()

        for (i in tokens.indices) {
            val (lead, core, trail) = cores[i]
            if (core.isEmpty() || !core.any { it in 'A'..'Z' || it in 'a'..'z' || it in "äöüßÄÖÜ" }) continue

            val lowerCore = resolvedLowers[i]
            val prevLower = resolvedLowers.getOrNull(i - 1)?.takeIf { it.isNotEmpty() }
            val nextLower = resolvedLowers.getOrNull(i + 1)?.takeIf { it.isNotEmpty() }

            // 1. Apply mixed/acronym/high-confidence proper casing when word is already in context
            val exactUni = unigrams[lowerCore]
            if (exactUni != null && exactUni.canonical != lowerCore) {
                val isAcronymOrMixed = Regex(".*(?:[A-Z]{2,}|[a-z][A-Z]).*").matches(exactUni.canonical)
                if (isAcronymOrMixed || (exactUni.weight >= 12.0 && lowerCore !in protectedCommonWords)) {
                    tokens[i] = "$lead${exactUni.canonical}$trail"
                }
            }

            // 2. Decide if this position warrants phonetic candidate search
            val rawUniW = unigramWeight(lowerCore)
            val prevIsStrong = prevLower != null && unigramWeight(prevLower) >= 4.0
            val prevNextBi = bigramWeight(prevLower, lowerCore)
            val nextIsStrong = nextLower != null && unigramWeight(nextLower) >= 4.0
            val currNextBi = bigramWeight(lowerCore, nextLower)

            val outOfContext = rawUniW < 0.8 && lowerCore !in protectedCommonWords
            val neighborPullMismatch = (prevIsStrong && prevNextBi == 0.0) || (nextIsStrong && currNextBi == 0.0)
            if (!outOfContext && !neighborPullMismatch) continue

            // Guard against corrupting phrasal verbs like "set ... up", "back ... up"
            val prevPrevLower = resolvedLowers.getOrNull(i - 2)?.takeIf { it.isNotEmpty() }
            if (lowerCore == "up" && (prevLower in phrasalVerbBeforeUp || prevPrevLower in phrasalVerbBeforeUp)) continue
            if (lowerCore.length <= 2 && !neighborPullMismatch) continue

            val rawCtxScore = ln(1.0 + rawUniW) +
                2.0 * (ln(1.0 + prevNextBi) + ln(1.0 + currNextBi))

            val seenCandidates = hashSetOf<String>()
            var bestCand: ContextUnigram? = null
            var bestGain = 0.0

            for (pkey in ContextLanguageModel.bucketKeys(lowerCore)) {
                val bucket = phoneticBuckets[pkey] ?: continue
                for (cand in bucket) {
                    if (!seenCandidates.add(cand.key) || cand.key == lowerCore) continue

                    val sim = ContextLanguageModel.combinedSimilarity(lowerCore, cand.key)
                    val pairPull = max(bigramWeight(prevLower, cand.key), bigramWeight(cand.key, nextLower))

                    // Never hijack common glue words unless neighbor bigram strongly expects cand
                    val allowedByTrigger = if (lowerCore in protectedCommonWords || rawUniW >= 0.8) {
                        pairPull >= 2.0 && sim >= 0.56
                    } else {
                        (sim >= 0.72 && cand.weight >= 3.5) || (sim >= 0.58 && pairPull >= 2.0)
                    }
                    if (!allowedByTrigger) continue

                    val candCtxScore = ln(1.0 + cand.weight) +
                        2.5 * (ln(1.0 + bigramWeight(prevLower, cand.key)) + ln(1.0 + bigramWeight(cand.key, nextLower)))
                    val improvement = candCtxScore - rawCtxScore
                    val totalScore = improvement + sim * 2.5

                    if (improvement >= 1.6 && totalScore > bestGain) {
                        bestGain = totalScore
                        bestCand = cand
                    }
                }
            }

            if (bestCand != null) {
                val replacement = if (bestCand.canonical != bestCand.key) bestCand.canonical else bestCand.key
                tokens[i] = "$lead$replacement$trail"
                resolvedLowers[i] = bestCand.key
            }
        }
    }

    /**
     * Collapses adjacent 2-word and 3-word STT splits (e.g. "okey ours" -> "OKRs",
     * "finanz amt" -> "Finanzamt", "ay pee kay" -> "APK") into single unigrams in the index.
     */
    private fun collapseSplitTerms(
        tokens: MutableList<String>,
        protectedCommonWords: Set<String>,
    ) {
        if (tokens.size < 2) return
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            var matched = false
            for (winSize in listOf(3, 2)) {
                if (i + winSize > tokens.size) continue
                val slice = tokens.subList(i, i + winSize)
                val firstParts = ContextLanguageModel.stripTokenPunct(slice.first())
                val lastParts = ContextLanguageModel.stripTokenPunct(slice.last())
                if (firstParts.third.isNotEmpty() || lastParts.first.isNotEmpty()) continue

                val middleOk = (1 until winSize - 1).all { idx ->
                    val mid = ContextLanguageModel.stripTokenPunct(slice[idx])
                    mid.first.isEmpty() && mid.third.isEmpty()
                }
                if (!middleOk) continue

                val cores = slice.mapIndexed { idx, tok ->
                    when (idx) {
                        0 -> firstParts.second
                        winSize - 1 -> lastParts.second
                        else -> tok
                    }
                }
                val allLatin = cores.all { c ->
                    c.isNotEmpty() && c.all { it in 'A'..'Z' || it in 'a'..'z' || it in "äöüßÄÖÜ" }
                }
                if (!allLatin) continue

                val lowers = cores.map { it.lowercase(Locale.ROOT) }
                val letterJoined = ContextLanguageModel.joinSpokenLetters(lowers)
                if (winSize == 3 && letterJoined == null) continue

                // Skip known frequent 2-word phrases and pure common-word pairs
                if (winSize == 2 && letterJoined == null) {
                    if (bigramWeight(lowers[0], lowers[1]) >= 1.5) continue
                    if (lowers[0] in protectedCommonWords && lowers[1] in protectedCommonWords) continue
                }

                val joined = lowers.joinToString("")
                val winner = findSplitMatch(joined, letterJoined)
                if (winner != null) {
                    val outText = if (winner.canonical != winner.key) winner.canonical else winner.key
                    out += "${firstParts.first}$outText${lastParts.third}"
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
        tokens.clear()
        tokens.addAll(out)
    }

    private fun findSplitMatch(joined: String, letterJoined: String?): ContextUnigram? {
        if (letterJoined != null) {
            val exactLetters = unigrams[letterJoined]
            if (exactLetters != null && exactLetters.weight >= 2.0) return exactLetters
        }
        val exactJoined = unigrams[joined]
        if (exactJoined != null && exactJoined.weight >= 2.0) return exactJoined

        val joinedCode = ContextLanguageModel.phoneticCode(joined)
        val bucket = phoneticBuckets[joinedCode] ?: return null
        var best: ContextUnigram? = null
        var bestScore = 0.0
        for (cand in bucket) {
            if (cand.weight < 3.5) continue
            if (ContextLanguageModel.phoneticCode(cand.key) != joinedCode) continue
            val charSim = TranscriptNormalizer.normalizedSimilarity(
                TranscriptNormalizer.foldGermanUmlauts(joined),
                TranscriptNormalizer.foldGermanUmlauts(cand.key),
            )
            if (charSim < 0.45) continue
            val acronymBonus = if (cand.canonical != cand.key) 1.5 else 0.0
            val score = ln(1.0 + cand.weight) + charSim * 3.0 + acronymBonus
            if (score > bestScore) {
                bestScore = score
                best = cand
            }
        }
        return best
    }

    companion object {
        val EMPTY = ContextIndex(emptyMap(), emptyMap())

        val DEFAULT_PROTECTED_WORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "being", "been", "but", "by", "can",
            "check", "create", "do", "for", "from", "get", "go", "have", "he", "her", "his",
            "i", "if", "in", "is", "it", "kids", "me", "my", "no", "not", "of", "on", "or",
            "our", "out", "please", "read", "remind", "reminder", "review", "schedule", "set",
            "she", "so", "that", "the", "their", "them", "then", "there", "these", "they",
            "this", "to", "today", "tomorrow", "up", "update", "us", "we", "will", "with",
            "you", "your",
        )

        val DEFAULT_PHRASAL_BEFORE_UP = setOf(
            "set", "back", "pick", "clean", "hook", "spin", "start", "stand", "warm", "wake",
        )
    }
}

object ContextLanguageModel {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun fromDto(dto: ContextIndexPayloadDto): ContextIndex {
        if (dto.unigrams.isEmpty()) return ContextIndex.EMPTY
        val uniMap = HashMap<String, ContextUnigram>(dto.unigrams.size)
        for ((k, v) in dto.unigrams) {
            val cleanKey = k.trim().lowercase(Locale.ROOT)
            if (cleanKey.isEmpty()) continue
            val canon = v.c.trim().ifEmpty { cleanKey }
            uniMap[cleanKey] = ContextUnigram(cleanKey, canon, v.w)
        }
        val biMap = HashMap<String, Double>(dto.bigrams.size)
        for ((pair, w) in dto.bigrams) {
            val cleanPair = pair.trim().lowercase(Locale.ROOT)
            if (cleanPair.contains(' ') && w > 0.0) {
                biMap[cleanPair] = w
            }
        }
        return ContextIndex(uniMap, biMap)
    }

    fun parseStandaloneJson(rawJson: String): ContextIndexPayloadDto? {
        if (rawJson.isBlank()) return null
        return runCatching {
            val trimmed = rawJson.trim()
            val asset = json.decodeFromString<StandaloneContextAssetDto>(trimmed)
            if (asset.contextIndex.unigrams.isNotEmpty()) {
                asset.contextIndex
            } else {
                json.decodeFromString<ContextIndexPayloadDto>(trimmed)
            }
        }.getOrNull()
    }

    fun encodePayload(dto: ContextIndexPayloadDto): String = json.encodeToString(dto)

    fun mergePayloads(vararg layers: ContextIndexPayloadDto?): ContextIndex {
        val mergedUni = HashMap<String, ContextUnigramDto>()
        val mergedBi = HashMap<String, Double>()

        for (layer in layers) {
            if (layer == null) continue
            for ((k, v) in layer.unigrams) {
                val key = k.trim().lowercase(Locale.ROOT)
                if (key.isEmpty()) continue
                val existing = mergedUni[key]
                mergedUni[key] = if (existing == null) {
                    ContextUnigramDto(w = v.w, c = v.c.ifEmpty { key })
                } else {
                    ContextUnigramDto(
                        w = existing.w + v.w,
                        c = if (v.w >= existing.w && v.c.isNotEmpty()) v.c else existing.c,
                    )
                }
            }
            for ((pair, w) in layer.bigrams) {
                val cleanPair = pair.trim().lowercase(Locale.ROOT)
                if (cleanPair.contains(' ')) {
                    mergedBi[cleanPair] = (mergedBi[cleanPair] ?: 0.0) + w
                }
            }
        }
        return fromDto(ContextIndexPayloadDto(mergedUni, mergedBi))
    }

    fun stripTokenPunct(token: String): Triple<String, String, String> {
        val lead = token.takeWhile { it == '#' || it == '@' || it == '"' || it == '(' }
        val trail = token.takeLastWhile { it in setOf('.', ',', '!', '?', ':', ';', '"', ')') }
        val coreEnd = (token.length - trail.length).coerceAtLeast(lead.length)
        return Triple(lead, token.substring(lead.length, coreEnd), trail)
    }

    fun phoneticCode(word: String): String {
        var w = TranscriptNormalizer.foldGermanUmlauts(word)
            .replace("'", "")
            .replace("-", "")
        if (w.isEmpty()) return ""
        w = w
            .replace(Regex("gh(?=[t]|$)"), "")
            .replace("sch", "sh")
            .replace(Regex("c([eiy])"), "s$1")
            .replace('c', 'k')
            .replace("ph", "f")
            .replace("qu", "k")
            .replace(Regex("^kn"), "n")
            .replace(Regex("^wr"), "r")
            .replace('z', 's')
            .replace('v', 'f')
            .replace("oo", "u")
            .replace("ee", "i")
            .replace("ou", "u")

        val leadingVowelPrefix = w.first() in charArrayOf('a', 'e', 'i', 'o', 'u', 'y')
        val sb = StringBuilder()
        if (leadingVowelPrefix) sb.append('@')
        for (idx in w.indices) {
            val ch = w[idx]
            val keep = if (idx == 0) {
                !leadingVowelPrefix
            } else {
                ch !in charArrayOf('a', 'e', 'i', 'o', 'u', 'y', 'h', 'w')
            }
            if (keep && (sb.isEmpty() || sb.last() != ch)) {
                sb.append(ch)
            }
        }
        return sb.toString().ifEmpty { w.take(2) }
    }

    fun bucketKeys(word: String): List<String> {
        val primary = phoneticCode(word)
        if (primary.isEmpty()) return emptyList()
        val keys = ArrayList<String>(2)
        keys += primary
        // Secondary flap-tolerant bucket: drops medial 't'/'d' inside words ("naughty" -> "nr")
        val lower = word.lowercase(Locale.ROOT)
        if (lower.length >= 4 && (lower.contains('t') || lower.contains('d'))) {
            val noFlap = lower.first() + lower.substring(1, lower.length - 1)
                .replace("t", "")
                .replace("d", "") + lower.last()
            val flapCode = phoneticCode(noFlap)
            if (flapCode.isNotEmpty() && flapCode != primary) {
                keys += flapCode
            }
        }
        return keys
    }

    fun combinedSimilarity(a: String, b: String): Double {
        val foldedA = TranscriptNormalizer.foldGermanUmlauts(a.lowercase(Locale.ROOT))
        val foldedB = TranscriptNormalizer.foldGermanUmlauts(b.lowercase(Locale.ROOT))
        val charSim = TranscriptNormalizer.normalizedSimilarity(foldedA, foldedB)
        val phonSim = TranscriptNormalizer.normalizedSimilarity(phoneticCode(foldedA), phoneticCode(foldedB))
        return charSim * 0.55 + phonSim * 0.45
    }

    private val SPOKEN_LETTER_MAP = mapOf(
        "ay" to "a", "a" to "a",
        "bee" to "b", "be" to "b", "b" to "b",
        "see" to "c", "sea" to "c", "c" to "c",
        "dee" to "d", "d" to "d",
        "ee" to "e", "e" to "e",
        "eff" to "f", "f" to "f",
        "gee" to "g", "g" to "g",
        "aitch" to "h", "h" to "h",
        "eye" to "i", "i" to "i",
        "jay" to "j", "j" to "j",
        "kay" to "k", "okey" to "ok", "okay" to "ok", "k" to "k",
        "el" to "l", "l" to "l",
        "em" to "m", "m" to "m",
        "en" to "n", "n" to "n",
        "oh" to "o", "o" to "o",
        "pee" to "p", "p" to "p",
        "cue" to "q", "queue" to "q", "q" to "q",
        "are" to "r", "r" to "r",
        "ess" to "s", "s" to "s",
        "tee" to "t", "t" to "t",
        "you" to "u", "u" to "u",
        "vee" to "v", "v" to "v",
        "doubleyou" to "w", "w" to "w",
        "ex" to "x", "x" to "x",
        "why" to "y", "y" to "y",
        "zee" to "z", "zed" to "z", "z" to "z",
        "eyes" to "is", "ares" to "rs", "ours" to "rs", "rs" to "rs",
    )

    fun joinSpokenLetters(tokens: List<String>): String? {
        if (tokens.isEmpty()) return null
        val mapped = tokens.map { SPOKEN_LETTER_MAP[it] ?: return null }
        return mapped.joinToString("")
    }
}
