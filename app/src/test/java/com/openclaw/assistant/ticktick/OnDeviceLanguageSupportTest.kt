package com.openclaw.assistant.ticktick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceLanguageSupportTest {

    @Test
    fun pickBestTagSelectsPreferredGermanAndArabicVariants() {
        val available = listOf("en-GB", "en-US", "de-AT", "de-DE", "ar-SA", "fr-FR")

        assertEquals(
            "en-US",
            OnDeviceLanguageSupport.pickBestTag(available, "en", listOf("en-US", "en-GB", "en")),
        )
        assertEquals(
            "de-DE",
            OnDeviceLanguageSupport.pickBestTag(available, "de", listOf("de-DE", "de-AT", "de")),
        )
        assertEquals(
            "ar-SA",
            OnDeviceLanguageSupport.pickBestTag(available, "ar", listOf("ar-EG", "ar-SA", "ar-001", "ar")),
        )
        assertNull(
            OnDeviceLanguageSupport.pickBestTag(listOf("en-US", "de-DE"), "ar", listOf("ar-EG", "ar-SA", "ar")),
        )
    }

    @Test
    fun resolvePrimaryLanguageAvoidsUnsupportedEnDeLocaleAndPicksInstalledEnglishPack() {
        val installed = listOf("de-DE", "en-US", "en-GB")

        assertEquals(
            "en-US",
            OnDeviceLanguageSupport.resolvePrimaryLanguage(
                configuredLanguage = "",
                installedPacks = installed,
                systemTag = "en-DE",
            ),
        )
        assertEquals(
            "en-GB",
            OnDeviceLanguageSupport.resolvePrimaryLanguage(
                configuredLanguage = "",
                installedPacks = installed,
                systemTag = "en-GB",
            ),
        )
        assertEquals(
            "en-US",
            OnDeviceLanguageSupport.resolvePrimaryLanguage(
                configuredLanguage = "",
                installedPacks = emptyList(),
                systemTag = "de-DE",
            ),
        )
        assertEquals(
            "de-DE",
            OnDeviceLanguageSupport.resolvePrimaryLanguage(
                configuredLanguage = "de-AT",
                installedPacks = installed,
                systemTag = "en-US",
            ),
        )
    }
}
