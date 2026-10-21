package com.jarvis.core

import com.jarvis.data.Event
import com.jarvis.data.Memory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reliability fix, Priorities D and E: no guessed places or absurd routes; yes only to the exact proposal, in time. */
class TravelAndConfirmationTest {
    private val mumbai = GeoPoint(19.0760, 72.8777)
    private val dadar = GeoPoint(19.0178, 72.8478)
    private val delhi = GeoPoint(28.6139, 77.2090)

    private class Fixed(val result: RouteResult) : RouteProvider {
        override val name = "OSRM"
        override val configured = true
        var calls = 0
        override suspend fun eta(from: GeoPoint, to: GeoPoint): RouteResult { calls++; return result }
    }

    private fun agent(geocodeBody: String, route: Fixed, server: MockWebServer) = OsrmTravelContextAgent(
        object : LocationSource { override suspend fun current() = DeviceLocation.Available(mumbai, 0) },
        NominatimGeocoder(baseUrl = server.url("/search").toString()),
        TrafficRouter(emptyList(), route)
    ).also { server.enqueue(MockResponse().setBody(geocodeBody)) }

    private fun event(place: String) = Event("e", 0, "Meeting", 0, 0, place, true)

    @Test
    fun genericPlacesAreNeverLookedUp() = runBlocking {
        listOf("college", "my college", "the office", "Gym", "home.").forEach { assertTrue(it, TravelPlausibility.genericPlace(it)) }
        listOf("VJTI college", "Dadar station", "Phoenix Mall, Lower Parel", "IIT Bombay").forEach { assertFalse(it, TravelPlausibility.genericPlace(it)) }
        MockWebServer().use { server ->
            server.start()
            val route = Fixed(RouteResult.Ok(RouteEta(20, EtaKind.NO_TRAFFIC, "OSRM")))
            val estimate = agent("""[{"lat":"28.61","lon":"77.20"}]""", route, server).estimate(event("college"), 0)
            assertFalse(estimate.known)
            assertEquals("\"college\" isn't a specific place", estimate.source)
            assertEquals(0, server.requestCount)
            assertEquals(0, route.calls)
        }
        assertTrue(MeetingDetails.specificPlaceQuestion("my college").startsWith("Which college?"))
    }

    @Test
    fun theSevenThousandMinuteRouteIsRejected() = runBlocking {
        // The reported bug: a far-away match and a 7,793-minute route moved the reminder days into the past.
        MockWebServer().use { server ->
            server.start()
            val estimate = agent("""[{"lat":"28.6139","lon":"77.2090"}]""", Fixed(RouteResult.Ok(RouteEta(7793, EtaKind.NO_TRAFFIC, "OSRM"))), server)
                .estimate(event("Some College"), 0)
            assertFalse(estimate.known)
            assertTrue(estimate.source, estimate.source.startsWith("the estimate looked wrong: the place found is about 11"))
            assertFalse(Regex("""\d+\.\d{3,}""").containsMatchIn(estimate.source)) // no coordinates in the reason
        }
        MockWebServer().use { server ->
            server.start()
            val ok = agent("""[{"lat":"19.0178","lon":"72.8478"}]""", Fixed(RouteResult.Ok(RouteEta(25, EtaKind.NO_TRAFFIC, "OSRM"))), server)
                .estimate(event("Dadar station"), 0)
            assertTrue(ok.known); assertEquals(25, ok.minutes)
        }
    }

    @Test
    fun implausibleRoutesAndMalformedResponsesGiveNoNumber() {
        assertNull(TravelPlausibility.implausible(mumbai, dadar, 25))
        assertTrue(TravelPlausibility.implausible(mumbai, delhi, 1300)!!.contains("km away"))
        assertTrue(TravelPlausibility.implausible(mumbai, dadar, 11 * 60)!!.isNotBlank())
        // 7 km in 1 minute (seconds read as minutes the other way round) or 0 minutes.
        assertTrue(TravelPlausibility.implausible(mumbai, dadar, 1) != null)
        assertTrue(TravelPlausibility.implausible(mumbai, dadar, 0) != null)
        // 7 km taking 5 hours.
        assertTrue(TravelPlausibility.implausible(mumbai, dadar, 300) != null)
        assertNull(OsrmRouter.parse("not json"))
        assertNull(OsrmRouter.parse("""{"routes":[]}"""))
        assertNull(OsrmRouter.parse("""{"routes":[{"duration":-5}]}"""))
        assertNull(NominatimGeocoder.parse("[]"))
        assertNull(NominatimGeocoder.parse("""[{"lat":"999","lon":"72"}]"""))
        assertNull(NominatimGeocoder.parse("<html>"))
    }

    private val shown = Memory("m1", "my gym is in Dadar", null, "conversation", 1, null, true, "fact", "", 5)
    private val t = 1_800_000_000_000L

    @Test
    fun aYesCountsOnlyForTheProposalShownAndOnlyInTime() {
        val pending = PendingConfirmation.ForgetMemory(shown, t)
        assertEquals(Confirmations.Answer.APPROVE, Confirmations.answer(pending, "yes", t + 1_000))
        assertEquals(Confirmations.Answer.APPROVE, Confirmations.answer(pending, "Haan, delete it", t + 1_000))
        assertEquals(Confirmations.Answer.APPROVE, Confirmations.answer(pending, "ok", t + 1_000))
        assertEquals(Confirmations.Answer.REJECT, Confirmations.answer(pending, "no", t + 1_000))
        assertEquals(Confirmations.Answer.REJECT, Confirmations.answer(pending, "nahi, rehne do", t + 1_000))
        assertEquals(Confirmations.Answer.UNCLEAR, Confirmations.answer(pending, "yes but at 5 pm", t + 1_000))
        assertEquals(Confirmations.Answer.EXPIRED, Confirmations.answer(pending, "yes", t + Confirmations.TTL_MS + 1))
        assertEquals(Confirmations.Answer.NOT_AN_ANSWER, Confirmations.answer(pending, "what's the weather", t + 1_000))
        assertTrue(Confirmations.forgetProposal(shown).contains("\"my gym is in Dadar\""))
        val meeting = PlanStep("meeting.create", mapOf("title" to "Project review", "date" to "2026-10-22", "hour" to "10", "minute" to "0", "location" to "VJTI, Matunga"))
        assertEquals("Add \"Project review\" on Thu 22 Oct at 10:00 AM at VJTI, Matunga to your calendar, with a reminder before it? Say yes to add it, or no.",
            Confirmations.meetingProposal(meeting))
    }

    private class Dao : com.jarvis.data.MemoryDao {
        val rows = kotlinx.coroutines.flow.MutableStateFlow<List<Memory>>(emptyList())
        override fun observeAll() = rows
        override suspend fun all() = rows.value
        override suspend fun findByKey(type: String, key: String) = rows.value.firstOrNull { it.type == type && it.memoryKey == key }
        override suspend fun upsert(memory: Memory) { rows.value = rows.value.filterNot { it.id == memory.id } + memory }
        override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
        override suspend fun deleteAll() { rows.value = emptyList() }
        override suspend fun markUsed(ids: List<String>, usedAt: Long) = Unit
    }

    @Test
    fun findingAMemoryDeletesNothingAndAChangedMemoryIsNotDeleted() = runBlocking {
        val dao = Dao().apply { rows.value = listOf(shown) }
        val repo = MemoryRepository(dao) { t }
        val found = repo.findForget("my gym is in Dadar")!!
        assertEquals(1, dao.rows.value.size)
        // Edited after it was shown: the yes no longer applies.
        dao.rows.value = listOf(shown.copy(contentText = "my gym is in Bandra", updatedAt = 6))
        assertNull(repo.deleteIfUnchanged(found))
        assertEquals(1, dao.rows.value.size)
        dao.rows.value = listOf(shown)
        assertEquals(shown, repo.deleteIfUnchanged(found))
        assertTrue(dao.rows.value.isEmpty())
        // Already gone.
        assertNull(repo.deleteIfUnchanged(found))
    }

    @Test
    fun accountWritesAndDeletionsNeedApprovalInTheRegistry() {
        ToolRegistry.specs.filter { it.sideEffect == SideEffect.ACCOUNT }.forEach { assertTrue(it.id, it.approval != Approval.NONE) }
        assertEquals(Approval.CONFIRM_ON_SCREEN, ToolRegistry.spec("meeting.create")!!.approval)
        assertEquals(Approval.CONFIRM_ON_SCREEN, ToolRegistry.spec("forget_memory")!!.approval)
    }
}
