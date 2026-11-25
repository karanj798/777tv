package xyz.billybishop4.fleet.tv

import org.junit.Assert.*
import org.junit.Test

class AudioLanguageTest {
    @Test fun blankPreferenceFallsBackToEnglish() {
        assertEquals("en", preferredAudioLanguage(Preferences()))
        assertEquals("en", preferredAudioLanguage(Preferences(audioLanguage = "")))
    }
    @Test fun explicitPreferenceWins() {
        assertEquals("es", preferredAudioLanguage(Preferences(audioLanguage = "es")))
        assertEquals("hi", preferredAudioLanguage(Preferences(audioLanguage = "hi")))
    }
    @Test fun explicitEnglishStaysEnglish() {
        assertEquals("en", preferredAudioLanguage(Preferences(audioLanguage = "en")))
    }
}
