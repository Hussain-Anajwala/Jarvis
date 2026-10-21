package com.jarvis.core

import com.jarvis.data.Event
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingDetailsTest {
    @Test
    fun aVenueCountsOnlyIfTheUserSaidIt() {
        val said = listOf("I have a meeting tomorrow at 5 pm")
        assertFalse("a model-invented venue is not used", MeetingDetails.grounded("Office", said))
        assertFalse(MeetingDetails.grounded("TBD", said))
        assertFalse(MeetingDetails.grounded("", said))
        assertTrue(MeetingDetails.grounded("college", listOf("meeting at college tomorrow at 10")))
        assertTrue(MeetingDetails.grounded("Dadar station", listOf("Meeting at Dadar railway station at 6 pm")))
        assertTrue("an earlier turn counts", MeetingDetails.grounded("VIT", listOf("at 5 pm", "the meeting is at VIT")))
        assertTrue("a saved memory sent with the request counts", MeetingDetails.grounded("VIT Mumbai", listOf("meeting at college", "I study at VIT Mumbai")))
        assertFalse("partly invented", MeetingDetails.grounded("VIT Mumbai campus hall", listOf("meeting at VIT")))
    }

    @Test
    fun onlineOrNoLocationMeansNoVenue() {
        listOf("no location", "it's online", "on a Zoom call", "no venue please", "virtual meeting", "skip the location").forEach {
            assertTrue(it, MeetingDetails.saysNoPlace(it))
        }
        assertFalse(MeetingDetails.saysNoPlace("at the library"))
    }

    @Test
    fun shortAnswersBecomeTheVenueButQuestionsAndNewRequestsDoNot() {
        assertEquals("VIT college", MeetingDetails.placeFromAnswer("at VIT college."))
        assertEquals("Dadar station", MeetingDetails.placeFromAnswer("It's at Dadar station"))
        assertNull(MeetingDetails.placeFromAnswer("what is the weather like in Mumbai today and tomorrow evening"))
        assertNull(MeetingDetails.placeFromAnswer("where?"))
        assertNull(MeetingDetails.placeFromAnswer("TBD"))
        // Calling it off, or a different short request, is never saved as the venue.
        listOf("cancel", "never mind", "set a timer for 5 minutes", "call Asha", "show my plans", "stop").forEach {
            assertNull(it, MeetingDetails.placeFromAnswer(it))
        }
        assertTrue(MeetingDetails.cancels("never mind"))
        assertTrue(MeetingDetails.looksLikeRequest("show me my teams schedule"))
    }

    @Test
    fun noPlaceMeansNoGeocodingAndNoInventedTravelTime() = runBlocking {
        val agent = OsrmTravelContextAgent() // would fail loudly if it tried the network or location
        val event = Event("e", 0, "Meeting", 0, 0, "", true)
        val estimate = agent.estimate(event, 30)
        assertFalse(estimate.known)
        assertEquals(0, estimate.minutes)
        assertEquals("No travel time, because no place was given.", TravelReplies.sentence(estimate))
        assertFalse("never \"place not found\" when no place was supplied", TravelReplies.sentence(estimate).contains("not found"))
    }

    @Test
    fun travelSentencesSeparateTrafficRoadNetworkAndUnknown() {
        assertEquals("Travel time: 22 min (live traffic via Google Routes).", TravelReplies.sentence(TravelTimeEstimate(22, "traffic", "live traffic via Google Routes")))
        assertTrue(TravelReplies.sentence(TravelTimeEstimate(9, "routed", "no traffic data: road-network time via OSRM")).contains("no traffic data"))
        assertEquals("Travel time unknown (couldn't find \"Zork\" on the map).", TravelReplies.sentence(TravelTimeEstimate(0, "unknown", "couldn't find \"Zork\" on the map")))
    }
}
