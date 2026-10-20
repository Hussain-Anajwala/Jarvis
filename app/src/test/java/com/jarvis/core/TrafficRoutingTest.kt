package com.jarvis.core

import com.jarvis.data.Event
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficRoutingTest {
    private val from = GeoPoint(19.0760, 72.8777)
    private val to = GeoPoint(19.0330, 72.8570)

    @Test
    fun googleRoutesRequestsTrafficAwareRoutingAndReadsDurationAndStaticDuration() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"routes":[{"duration":"1500s","staticDuration":"1080s","distanceMeters":8200}]}"""))
            server.start()
            val provider = GoogleRoutesProvider({ "g-key" }, url = server.url("/directions/v2:computeRoutes").toString())
            val result = provider.eta(from, to) as RouteResult.Ok
            assertEquals(RouteEta(25, EtaKind.LIVE_TRAFFIC, "Google Routes", 18), result.eta)
            val request = server.takeRequest()
            assertEquals("g-key", request.getHeader("X-Goog-Api-Key"))
            assertEquals("routes.duration,routes.staticDuration,routes.distanceMeters", request.getHeader("X-Goog-FieldMask"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("TRAFFIC_AWARE", body.getString("routingPreference"))
            assertEquals("DRIVE", body.getString("travelMode"))
        }
        assertEquals("live traffic via Google Routes, 7 min slower than typical",
            TrafficLabels.source(RouteEta(25, EtaKind.LIVE_TRAFFIC, "Google Routes", 18), null))
    }

    @Test
    fun mapboxDrivingTrafficReadsDurationAndTypical() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"Ok","routes":[{"duration":600.0,"duration_typical":720.0,"distance":5000}]}"""))
            server.start()
            val provider = MapboxTrafficProvider({ "pk.test" }, baseUrl = server.url("/directions/v5/mapbox/driving-traffic/").toString())
            assertEquals(RouteResult.Ok(RouteEta(10, EtaKind.LIVE_TRAFFIC, "Mapbox", 12)), provider.eta(from, to))
            val url = server.takeRequest().requestUrl!!
            assertEquals("/directions/v5/mapbox/driving-traffic/72.8777,19.0760;72.8570,19.0330", url.encodedPath)
            assertEquals("pk.test", url.queryParameter("access_token"))
        }
        assertEquals("live traffic via Mapbox, 2 min faster than typical", TrafficLabels.source(RouteEta(10, EtaKind.LIVE_TRAFFIC, "Mapbox", 12), null))
        assertEquals("live traffic via Mapbox", TrafficLabels.source(RouteEta(10, EtaKind.LIVE_TRAFFIC, "Mapbox"), null))
        assertNull(MapboxTrafficProvider.parse("""{"code":"NoRoute","routes":[]}"""))
    }

    private class FixedProvider(override val name: String, override val configured: Boolean, val result: RouteResult) : RouteProvider {
        var calls = 0
        override suspend fun eta(from: GeoPoint, to: GeoPoint): RouteResult { calls++; return result }
    }

    @Test
    fun routerUsesTrafficWhenConfiguredAndOtherwiseLabelsOsrmAsNoTraffic() = runBlocking {
        val osrm = FixedProvider("OSRM", true, RouteResult.Ok(RouteEta(14, EtaKind.NO_TRAFFIC, "OSRM")))
        val unconfigured = FixedProvider("Google Routes", false, RouteResult.Failed("unused"))
        val none = TrafficRouter(listOf(unconfigured), osrm).eta(from, to)
        assertEquals(0, unconfigured.calls)
        assertEquals("no traffic provider configured", none.trafficProblem)
        val eta = (none.result as RouteResult.Ok).eta
        assertEquals("no traffic data: road-network time via OSRM (no traffic provider configured)", TrafficLabels.source(eta, none.trafficProblem))
        assertEquals("routed", TrafficLabels.freshness(eta.kind))

        val failing = FixedProvider("Google Routes", true, RouteResult.Failed("Google Routes key not authorized for the Routes API, or billing is off (HTTP 403)"))
        val mapbox = FixedProvider("Mapbox", true, RouteResult.Ok(RouteEta(11, EtaKind.LIVE_TRAFFIC, "Mapbox")))
        val answer = TrafficRouter(listOf(failing, mapbox), osrm).eta(from, to)
        assertEquals(RouteResult.Ok(RouteEta(11, EtaKind.LIVE_TRAFFIC, "Mapbox")), answer.result)

        val both = TrafficRouter(listOf(failing), osrm).eta(from, to)
        assertTrue(both.trafficProblem!!.contains("HTTP 403"))
        assertEquals(EtaKind.NO_TRAFFIC, (both.result as RouteResult.Ok).eta.kind)
    }

    @Test
    fun missingKeysAuthAndQuotaErrorsAreReported() = runBlocking {
        assertEquals(RouteResult.Failed("Google Routes API key not configured"), GoogleRoutesProvider({ null }).eta(from, to))
        assertEquals(RouteResult.Failed("Mapbox token not configured"), MapboxTrafficProvider({ "" }).eta(from, to))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody("""{"routes":[]}"""))
            server.start()
            val provider = GoogleRoutesProvider({ "k" }, url = server.url("/r").toString())
            assertEquals(RouteResult.Failed("Google Routes key not authorized for the Routes API, or billing is off (HTTP 403)"), provider.eta(from, to))
            assertEquals(RouteResult.Failed("Google Routes quota exceeded (HTTP 429)"), provider.eta(from, to))
            assertEquals(RouteResult.Failed("Google Routes returned no route"), provider.eta(from, to))
        }
    }

    @Test
    fun travelLabelsNeverCallRoadNetworkTimeLive() {
        assertEquals("no traffic data: road-network time via OSRM", TravelReplies.label(TravelTimeEstimate(9, "routed", "no traffic data: road-network time via OSRM")))
        assertEquals("unknown: location permission off", TravelReplies.label(TravelTimeEstimate(0, "unknown", "location permission off")))
        val event = Event("e", 0, "Meeting", 0, 0, "VIT", true)
        val agent = OsrmTravelContextAgent(
            object : LocationSource { override suspend fun current() = DeviceLocation.Available(from, 0) },
            NominatimGeocoder(baseUrl = "http://127.0.0.1:9/") // unreachable geocoder → unknown, never "live" and never an invented duration
        )
        val estimate = runBlocking { agent.estimate(event, 30) }
        assertEquals("unknown", estimate.freshness)
    }
}
