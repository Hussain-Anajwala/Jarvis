package com.jarvis.core

import com.jarvis.data.Event
import com.jarvis.data.Memory
import com.jarvis.data.MemoryDao
import com.jarvis.data.Plan
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAndContextTest {
    private class FakeMemoryDao : MemoryDao {
        val rows = MutableStateFlow<List<Memory>>(emptyList())
        override fun observeAll(): Flow<List<Memory>> = rows
        override suspend fun all() = rows.value.sortedByDescending { it.updatedAt }
        override suspend fun findByKey(type: String, key: String) = rows.value.firstOrNull { it.type == type && it.memoryKey == key }
        override suspend fun upsert(memory: Memory) { rows.value = rows.value.filterNot { it.id == memory.id } + memory }
        override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
        override suspend fun deleteAll() { rows.value = emptyList() }
        override suspend fun markUsed(ids: List<String>, usedAt: Long) {
            rows.value = rows.value.map { if (it.id in ids) it.copy(lastUsedAt = usedAt) else it }
        }
    }

    @Test
    fun classifierTypesNamesSignOffsFavoritesRelationshipsAndFacts() {
        assertEquals(ClassifiedMemory("preference", "user_name", "Hussain"), MemoryClassifier.classify("my name is Hussain"))
        assertEquals(ClassifiedMemory("preference", "email_sign_off", "Best regards"), MemoryClassifier.classify("sign my emails with \"Best regards\""))
        assertEquals(ClassifiedMemory("favorite", "favorite_restaurant", "Leopold Cafe"), MemoryClassifier.classify("my favourite restaurant is Leopold Cafe"))
        assertEquals(ClassifiedMemory("relationship", "Prof. Rao", "Prof. Rao is my project guide"), MemoryClassifier.classify("that Prof. Rao is my project guide."))
        assertEquals("preference", MemoryClassifier.classify("I prefer morning meetings")!!.type)
        assertEquals(ClassifiedMemory("fact", "", "the viva is in room 204"), MemoryClassifier.classify("the viva is in room 204"))
        assertNull(MemoryClassifier.classify("ok"))
    }

    private fun memory(id: String, text: String, key: String = "", updated: Long = 1) =
        Memory(id, text, null, "test", 1, null, true, if (key.isBlank()) "fact" else "relationship", key, updated)

    @Test
    fun retrievalReturnsOnlyRelevantMemoriesUpToK() {
        val memories = listOf(
            memory("1", "Prof. Rao is my project guide", "Prof. Rao"),
            memory("2", "I prefer morning meetings"),
            memory("3", "My sister lives in Pune"),
            memory("4", "The viva is in room 204")
        )
        assertEquals(listOf("1"), MemoryRetriever.relevant("write an email to Prof Rao asking for an extension", memories).map { it.id })
        assertEquals(listOf("2"), MemoryRetriever.relevant("schedule meetings tomorrow", memories).map { it.id })
        assertTrue(MemoryRetriever.relevant("set a timer for 5 minutes", memories).isEmpty())
        assertTrue(MemoryRetriever.relevant("the and of to", memories).isEmpty())
        assertTrue(MemoryRetriever.relevant("rao pune viva meetings", memories, k = 3).size == 3)
        assertEquals(listOf("[email] is my contact"), MemoryRetriever.promptLines(listOf(memory("5", "a@b.com is my contact"))))
        val song = Memory("6", "Kesariya", null, "t", 1, null, true, "favorite", "favorite_song", 1)
        assertEquals("favorite song: Kesariya", MemoryReplies.describe(song))
        assertTrue(MemoryReplies.list(listOf(song), null).contains("• favorite song: Kesariya"))
    }

    @Test
    fun repositoryUpsertsKeyedEntriesEditsForgetsAndBacksFavoriteSong() = runBlocking {
        val dao = FakeMemoryDao()
        var now = 100L
        val repo = MemoryRepository(dao) { now }
        repo.set(PersonalFactsStore.FAVORITE_SONG, "Kesariya")
        now = 200
        repo.set(PersonalFactsStore.FAVORITE_SONG, "Tum Hi Ho")
        assertEquals(1, dao.rows.value.size)
        assertEquals("Tum Hi Ho", repo.get(PersonalFactsStore.FAVORITE_SONG))
        val guide = repo.remember(MemoryClassifier.classify("Prof. Rao is my project guide")!!, "conversation")
        repo.remember(MemoryClassifier.classify("the viva is in room 204")!!, "conversation")
        assertTrue(repo.edit(guide.id, "Prof. Rao is my project guide and HOD"))
        assertFalse(repo.edit(guide.id, "   "))
        assertEquals(listOf(guide.id), repo.relevantFor("email Prof Rao").map { it.id })
        assertEquals(200L, dao.rows.value.first { it.id == guide.id }.lastUsedAt)
        assertEquals("the viva is in room 204", repo.forget("viva room")?.contentText)
        assertNull(repo.forget("nothing like this"))
        repo.remove(PersonalFactsStore.FAVORITE_SONG)
        assertNull(repo.get(PersonalFactsStore.FAVORITE_SONG))
        repo.clearAll()
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun memoryWeatherAndReminderPhrasesParseLocally() {
        fun step(text: String) = (PhoneCommandParser.parse(text) as PhoneCommandParseResult.Parsed).step
        assertEquals(PlanStep("remember_fact", mapOf("text" to "Prof. Rao is my project guide")), step("Remember that Prof. Rao is my project guide."))
        assertEquals(PlanStep("remember_fact", mapOf("text" to "my name is Hussain")), step("my name is Hussain"))
        assertEquals(PlanStep("forget_memory", mapOf("query" to "the viva room")), step("forget the viva room"))
        assertTrue(PhoneCommandParser.parse("forget it") is PhoneCommandParseResult.Clarification)
        assertEquals(PlanStep("list_memories", emptyMap()), step("what do you remember about me?"))
        assertEquals(PlanStep("list_memories", mapOf("query" to "Prof. Rao")), step("what do you know about Prof. Rao"))
        assertEquals(PlanStep("list_memories", emptyMap()), step("show my memories"))
        assertEquals(PhoneCommandParseResult.NotPhoneCommand, PhoneCommandParser.parse("remember to buy milk"))
        assertEquals(PlanStep("set_favorite_song", mapOf("song" to "Kesariya")), step("remember that my favorite song is Kesariya"))
        for (text in listOf("what's the weather", "weather today", "will it rain today?", "aaj mausam kaisa hai")) {
            assertEquals(text, PlanStep("get_weather", emptyMap()), step(text))
        }
    }

    @Test
    fun promptIncludesOnlyGivenMemoriesAndGroqSendsThem() = runBlocking {
        val now = ZonedDateTime.of(2026, 10, 18, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        assertFalse(JarvisSystemPrompt.build(now).contains("remember"))
        assertTrue(JarvisSystemPrompt.build(now, listOf("Prof. Rao is my project guide")).contains("Prof. Rao is my project guide"))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"openai/gpt-oss-120b","active":true}]}"""))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
            server.start()
            GroqReasoningProvider("k", "openai/gpt-oss-120b", apiBaseUrl = server.url("/openai/v1").toString(), warningLogger = { _, _ -> })
                .reason(ReasoningRequest("email Prof Rao", listOf("compose_email"), memoryContext = listOf("Prof. Rao is my project guide")))
            server.takeRequest()
            assertTrue(server.takeRequest().body.readUtf8().contains("Prof. Rao is my project guide"))
        }
    }

    private class FakeLocation(var value: DeviceLocation) : LocationSource {
        override suspend fun current() = value
    }

    private val event = Event("e", 0, "Meeting at college", 0, 0, "VIT Mumbai", true)

    @Test
    fun travelIsLiveOnlyFromRealLocationAndExplainsEveryFallback() = runBlocking {
        MockWebServer().use { server ->
            var osrmStatus = 200
            var geocodeBody = """[{"lat":"19.0330","lon":"72.8570"}]"""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/search") -> MockResponse().setBody(geocodeBody)
                    osrmStatus != 200 -> MockResponse().setResponseCode(osrmStatus)
                    else -> MockResponse().setBody("""{"routes":[{"duration":1260.0}]}""")
                }
            }
            server.start()
            val location = FakeLocation(DeviceLocation.Available(GeoPoint(19.0760, 72.8777), 1_000))
            val agent = OsrmTravelContextAgent(
                location,
                NominatimGeocoder(baseUrl = server.url("/search").toString()),
                TrafficRouter(emptyList(), OsrmRouteProvider(OsrmRouter(baseUrl = server.url("/route/v1/driving/").toString())))
            )
            assertEquals(TravelTimeEstimate(21, "routed", "no traffic data: road-network time via OSRM (no traffic provider configured)"), agent.estimate(event, 30))
            val geocode = server.takeRequest()
            assertEquals(NominatimGeocoder.USER_AGENT, geocode.getHeader("User-Agent"))
            assertTrue(geocode.requestUrl!!.queryParameter("viewbox")!!.isNotBlank())
            assertEquals("/route/v1/driving/72.8777,19.0760;72.8570,19.0330", server.takeRequest().requestUrl!!.encodedPath)

            osrmStatus = 503
            assertEquals(TravelTimeEstimate(30, "estimated", "OSRM HTTP 503"), agent.estimate(event, 30))
            geocodeBody = "[]"
            assertEquals("place not found: VIT Mumbai", agent.estimate(event, 30).source)
            location.value = DeviceLocation.PermissionDenied
            assertEquals(TravelTimeEstimate(30, "estimated", "location permission off"), agent.estimate(event, 30))
            location.value = DeviceLocation.Disabled
            assertEquals("estimated: location services off", TravelReplies.label(agent.estimate(event, 30)))
        }
        assertEquals("estimated: no recent device location", TravelReplies.label(OsrmTravelContextAgent().estimate(event, 30)))
    }

    @Test
    fun geocodeRouteAndWeatherParsingRejectMalformedData() {
        assertEquals(GeoPoint(19.0, 72.8), NominatimGeocoder.parse("""[{"lat":"19.0","lon":"72.8"}]"""))
        assertNull(NominatimGeocoder.parse("""[{"lat":"999","lon":"72.8"}]"""))
        assertNull(NominatimGeocoder.parse("<html>"))
        assertNull(OsrmRouter.parse("""{"routes":[]}"""))
        assertEquals(1, OsrmRouter.parse("""{"routes":[{"duration":5}]}"""))
        val weather = OpenMeteoClient.parse(
            """{"current":{"temperature_2m":29.4,"weather_code":2},"daily":{"temperature_2m_min":[25.1],"temperature_2m_max":[32.6],"precipitation_probability_max":[40]}}"""
        )!!
        assertEquals("Near you now: 29°C, partly cloudy. Today 25°C–33°C, 40% chance of rain. (Open-Meteo, live)", OpenMeteoClient.reply(weather))
        assertNull(OpenMeteoClient.parse("""{"current":{}}"""))
        assertNull(OpenMeteoClient.parse("nope"))
    }

    @Test
    fun habitDetectorNeedsThreeRealWeeksOnTheSameWeekday() {
        val zone = ZoneId.systemDefault()
        val monday = ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, zone)
        fun plan(id: String, weeksAgo: Long, ref: String? = null, goal: String = "Gym session") =
            Plan(id, goal, "completed", monday.minusWeeks(weeksAgo).toInstant().toEpochMilli(), 0, ref)
        val now = monday.plusDays(1).toInstant().toEpochMilli()
        assertTrue(HabitDetector.suggestions(listOf(plan("a", 0), plan("b", 1)), now).isEmpty())
        val found = HabitDetector.suggestions(listOf(plan("a", 0), plan("b", 1), plan("c", 2)), now).single()
        assertEquals("Every Monday: Gym session (seen in 3 weeks)", found.description)
        assertTrue(HabitDetector.suggestions(listOf(plan("a", 0, "habit-seed"), plan("b", 1, "habit-seed"), plan("c", 2, "habit-seed")), now).isEmpty())
        assertTrue(HabitDetector.suggestions(listOf(plan("a", 0), plan("b", 1), plan("c", 20)), now).isEmpty())
    }
}
