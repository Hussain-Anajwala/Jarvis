package com.jarvis.core

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    private fun say(text: String) = SpeechText.forSpeech(text, Locale.ENGLISH)

    @Test
    fun datesAndOrdinalsAreReadNaturally() {
        assertEquals("Briefing for 18th of October", say("Briefing for 18th Oct."))
        assertEquals("Sunday, the 18th of October", say("Sun 18 Oct"))
        assertEquals("from 20th of October to 22nd of October", say("from 20 October to 22 October"))
        assertEquals("on October 3rd", say("on Oct 3"))
        assertEquals("1st", SpeechText.ordinal(1))
        assertEquals("11th", SpeechText.ordinal(11))
        assertEquals("22nd", SpeechText.ordinal(22))
        assertEquals("103rd", SpeechText.ordinal(103))
    }

    @Test
    fun unitsAndSymbolsAreSpelledOut() {
        assertEquals("It is 30 degrees Celsius.", say("It is 30°C."))
        assertEquals("Today 27 degrees Celsius to 34 degrees Celsius, 60 percent chance of rain.", say("Today 27°C–34°C, 60% chance of rain."))
        assertEquals("1 degree Celsius", say("1°C"))
        assertEquals("12 kilometres, about 25 minutes", say("12 km, about 25 min"))
        assertEquals("wind 14 kilometres per hour", say("wind 14 km/h"))
        assertEquals("500 rupees", say("₹500"))
        assertEquals("for example, tea and coffee", say("e.g., tea & coffee"))
    }

    @Test
    fun addressesNumbersTimesAndIdsAreNeverRewritten() {
        val email = "Write to asha.rao@example.com about 18 Oct"
        assertEquals("Write to asha.rao@example.com about 18th of October", say(email))
        assertEquals("Open https://example.com/18-oct?x=30%25 now", say("Open https://example.com/18-oct?x=30%25 now"))
        assertEquals("Call +91 98765 43210 at 10:30 am", say("Call +91 98765 43210 at 10:30 am"))
        assertEquals("Order AB12CD and build_42 are ready", say("Order AB12CD and build_42 are ready"))
        assertTrue(say("Meeting at 5:30 pm").contains("5:30 pm"))
    }

    @Test
    fun otherLanguagesOnlyGetSymbolCleanUp() {
        assertEquals("27°C to 34°C", SpeechText.forSpeech("27°C–34°C", Locale("hi")))
    }
}
