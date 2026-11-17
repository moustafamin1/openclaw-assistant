package com.openclaw.assistant.ticktick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptNormalizerTest {

    @Test
    fun convertsSpokenHashtagAndResolvesNooryAliases() {
        val raw = "please update the experiments for naughty app hashtag moody send a reminder tomorrow"
        val out = TranscriptNormalizer.normalize(raw)
        assertEquals(
            "Please update the experiments for Noory app #Noory send a reminder tomorrow",
            out,
        )
    }

    @Test
    fun preservesNormalEnglishUseOfAmbiguousWordsWithoutProjectContext() {
        val raw = "kids are being naughty today and I feel moody"
        val out = TranscriptNormalizer.normalize(raw)
        assertEquals("Kids are being naughty today and I feel moody", out)
    }

    @Test
    fun resolvesPhoneticNearMissesAndUserAddedRelatives() {
        val customVocab = """
            ${TranscriptNormalizer.DEFAULT_RAW_VOCABULARY}
            Khadija
            Tarek
        """.trimIndent()
        val raw = "call khadija about hashtag zabeeb and remind tarek"
        val out = TranscriptNormalizer.normalize(raw, customVocab)
        assertEquals("Call Khadija about #Zebeeb and remind Tarek", out)
    }

    @Test
    fun resolvesMultiWordAndHyphenatedAliases() {
        val raw = "check no-tee backlog and moo-ree sprint"
        val out = TranscriptNormalizer.normalize(raw)
        assertEquals("Check Noory backlog and Noory sprint", out)
    }

    @Test
    fun resolvesVaultExtractedPeopleAndGermanTerms() {
        val raw = "ask hayder and maram about tick tick bug at quartiersweg and bildungszeit"
        val out = TranscriptNormalizer.normalize(raw)
        assertEquals("Ask Haider and Maram about TickTick bug at Quartiersweg and Bildungszeit", out)
    }

    @Test
    fun mergeGlossariesPreservesLocalEditsAndAddsRemoteTerms() {
        val synced = """
            Noory = naughty, nouri
            Krankenkasse
            Karlsruhe = carlsruhe
        """.trimIndent()
        val userCustom = """
            Noory = custom alias
            Khadija = khadiga
        """.trimIndent()

        val merged = TranscriptNormalizer.mergeGlossaries(synced, userCustom)
        val out = TranscriptNormalizer.normalize("call khadiga in carlsruhe about custom alias", merged)
        assertEquals("Call Khadija in Karlsruhe about Noory", out)
    }

    @Test
    fun resolvesGermanUmlautPlaceNamesFromAsciiAndPhoneticSpellings() {
        val raw = "send tax letter to finanzamt schoenberg near wohnpark suedkreuz in schoeneberg"
        val out = TranscriptNormalizer.normalize(raw)
        assertEquals("Send tax letter to Finanzamt Schöneberg near Wohnpark Südkreuz in Schöneberg", out)

        // Phonetic approximation even without an exact alias in the glossary
        val phoneticOut = TranscriptNormalizer.normalize("visit shoeneberg office tomorrow", "Schöneberg")
        assertEquals("Visit Schöneberg office tomorrow", phoneticOut)
    }

    @Test
    fun resolvesNuriBuergeramtAndPreservesArabicText() {
        val raw = "visit schöneberg berger ant to update nuri app في برلين"
        val out = TranscriptNormalizer.normalize(raw)
        assertEquals("Visit Schöneberg Bürgeramt to update Noory app في برلين", out)
    }

    @Test
    fun convertsSpokenQuestionAndExclamationMarksCleansFillersAndSentenceCase() {
        val raw = "um should we update this this feature question mark also ping azmy exclamation mark"
        val out = TranscriptNormalizer.normalize(raw)
        assertEquals("Should we update this feature? Also ping Azmy!", out)
    }

    @Test
    fun biasingTermsPrioritizesUmlautAndMultiWordDomainTermsWithinCap() {
        val terms = TranscriptNormalizer.biasingTerms("Schöneberg\nNoory\nRote Insel", maxTerms = 25)
        assertTrue(terms.size <= 25)
        assertTrue(terms.contains("Schöneberg"))
        assertTrue(terms.contains("Wohnpark Südkreuz"))
    }

    @Test
    fun convertsSpokenBrainPrefixToDelegationSyntax() {
        val raw1 = "brain check this image and add event to calendar"
        assertEquals("@brain: Check this image and add event to calendar", TranscriptNormalizer.normalize(raw1))

        val raw2 = "at brain please move all overdue tasks to today"
        assertEquals("@brain: Please move all overdue tasks to today", TranscriptNormalizer.normalize(raw2))

        val raw3 = "@brain research gold prices in Germany versus Egypt"
        assertEquals("@brain: Research gold prices in Germany versus Egypt", TranscriptNormalizer.normalize(raw3))

        val raw4 = "Brain, contact the therapist to reschedule my appointment"
        assertEquals("@brain: Contact the therapist to reschedule my appointment", TranscriptNormalizer.normalize(raw4))
    }

    @Test
    fun normalizesOkrsAndTkMisrecognitionsAndSelectsBestHypothesis() {
        assertEquals("Fix OKRs", TranscriptNormalizer.normalize("fix Okey Ours"))
        assertEquals("Fix OKRs and TK", TranscriptNormalizer.normalize("fix okay ours and tk max"))

        val best = TranscriptNormalizer.selectBestHypothesis(
            listOf("fix okey ours", "fix OKRs", "fix okay are"),
        )
        assertEquals("fix OKRs", best)

        val biasing = TranscriptNormalizer.biasingTerms()
        assertTrue(biasing.contains("OKRs"))
        assertTrue(biasing.contains("TK"))
    }
}
