package com.jarvis.core

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/** What a duration actually means — never blur these in the UI. */
enum class EtaKind {
    /** Current traffic (Google TRAFFIC_AWARE `duration`, Mapbox `driving-traffic` `duration`). */
    LIVE_TRAFFIC,
    /** Road-network time with no traffic data (OSRM). */
    NO_TRAFFIC
}

data class RouteEta(val minutes: Int, val kind: EtaKind, val provider: String, val typicalMinutes: Int? = null)

sealed interface RouteResult {
    data class Ok(val eta: RouteEta) : RouteResult
    data class Failed(val reason: String) : RouteResult
}

interface RouteProvider {
    val name: String
    val configured: Boolean
    suspend fun eta(from: GeoPoint, to: GeoPoint): RouteResult
}

private fun routingClient() = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()

private fun minutesFromSeconds(seconds: Double): Int? =
    seconds.takeIf { !it.isNaN() && it > 0 }?.let { (it / 60.0).toInt().coerceAtLeast(1) }

/**
 * Google Routes API computeRoutes with routingPreference TRAFFIC_AWARE: `duration` includes real-time traffic and
 * `staticDuration` uses historical traffic only. Needs an API key with the Routes API enabled (billed per request,
 * higher rate for traffic-aware routing).
 */
class GoogleRoutesProvider(
    private val apiKey: () -> String?,
    private val client: OkHttpClient = routingClient(),
    private val url: String = "https://routes.googleapis.com/directions/v2:computeRoutes"
) : RouteProvider {
    override val name = "Google Routes"
    override val configured: Boolean get() = !apiKey().isNullOrBlank()

    override suspend fun eta(from: GeoPoint, to: GeoPoint): RouteResult = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf(String::isNotBlank) ?: return@withContext RouteResult.Failed("Google Routes API key not configured")
        fun waypoint(p: GeoPoint) = JSONObject().put("location", JSONObject().put("latLng",
            JSONObject().put("latitude", p.latitude).put("longitude", p.longitude)))
        val body = JSONObject()
            .put("origin", waypoint(from))
            .put("destination", waypoint(to))
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE")
            .toString()
        val request = Request.Builder().url(url)
            .header("X-Goog-Api-Key", key)
            .header("X-Goog-FieldMask", "routes.duration,routes.staticDuration,routes.distanceMeters")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext RouteResult.Failed(httpReason(response.code))
                parse(response.body?.string().orEmpty())?.let { RouteResult.Ok(it) }
                    ?: RouteResult.Failed("Google Routes returned no route")
            }
        } catch (error: IOException) {
            RouteResult.Failed("Google Routes unreachable (${error.javaClass.simpleName})")
        }
    }

    companion object {
        fun httpReason(code: Int) = when (code) {
            400 -> "Google Routes rejected the request (HTTP 400)"
            401, 403 -> "Google Routes key not authorized for the Routes API, or billing is off (HTTP $code)"
            429 -> "Google Routes quota exceeded (HTTP 429)"
            else -> "Google Routes error (HTTP $code)"
        }

        /** Durations are strings like "1234s". */
        private fun seconds(value: String?): Double? = value?.removeSuffix("s")?.toDoubleOrNull()

        fun parse(body: String): RouteEta? = try {
            val route = JSONObject(body).optJSONArray("routes")?.optJSONObject(0)
            val live = minutesFromSeconds(seconds(route?.optString("duration")) ?: Double.NaN)
            live?.let {
                RouteEta(it, EtaKind.LIVE_TRAFFIC, "Google Routes", minutesFromSeconds(seconds(route?.optString("staticDuration")) ?: Double.NaN))
            }
        } catch (_: JSONException) {
            null
        }
    }
}

/** Mapbox Directions `driving-traffic`: current + historic traffic in covered regions; `duration_typical` when given. */
class MapboxTrafficProvider(
    private val accessToken: () -> String?,
    private val client: OkHttpClient = routingClient(),
    private val baseUrl: String = "https://api.mapbox.com/directions/v5/mapbox/driving-traffic/"
) : RouteProvider {
    override val name = "Mapbox"
    override val configured: Boolean get() = !accessToken().isNullOrBlank()

    override suspend fun eta(from: GeoPoint, to: GeoPoint): RouteResult = withContext(Dispatchers.IO) {
        val token = accessToken()?.takeIf(String::isNotBlank) ?: return@withContext RouteResult.Failed("Mapbox token not configured")
        val url = "${baseUrl.trimEnd('/')}/${from.lon4()},${from.lat4()};${to.lon4()},${to.lat4()}?overview=false&access_token=$token"
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext RouteResult.Failed(
                        when (response.code) {
                            401, 403 -> "Mapbox token rejected (HTTP ${response.code})"
                            422 -> "Mapbox could not route between these points (HTTP 422)"
                            429 -> "Mapbox rate limit (HTTP 429)"
                            else -> "Mapbox error (HTTP ${response.code})"
                        }
                    )
                }
                parse(response.body?.string().orEmpty())?.let { RouteResult.Ok(it) }
                    ?: RouteResult.Failed("Mapbox returned no route")
            }
        } catch (error: IOException) {
            RouteResult.Failed("Mapbox unreachable (${error.javaClass.simpleName})")
        }
    }

    companion object {
        fun parse(body: String): RouteEta? = try {
            val json = JSONObject(body)
            if (json.optString("code") != "Ok") null
            else json.optJSONArray("routes")?.optJSONObject(0)?.let { route ->
                minutesFromSeconds(route.optDouble("duration", Double.NaN))?.let {
                    RouteEta(it, EtaKind.LIVE_TRAFFIC, "Mapbox", minutesFromSeconds(route.optDouble("duration_typical", Double.NaN)))
                }
            }
        } catch (_: JSONException) {
            null
        }
    }
}

/** Public OSRM: road-network time only. Labelled as such — it is not traffic. */
class OsrmRouteProvider(private val router: OsrmRouter = OsrmRouter()) : RouteProvider {
    override val name = "OSRM"
    override val configured = true
    override suspend fun eta(from: GeoPoint, to: GeoPoint): RouteResult = try {
        router.minutes(from, to)?.let { RouteResult.Ok(RouteEta(it, EtaKind.NO_TRAFFIC, "OSRM")) }
            ?: RouteResult.Failed("no route found")
    } catch (error: IOException) {
        RouteResult.Failed(error.message ?: "network unavailable")
    }
}

/** Tries configured traffic providers first, then OSRM; remembers why traffic was not used. */
class TrafficRouter(private val trafficProviders: List<RouteProvider>, private val fallback: RouteProvider = OsrmRouteProvider()) {
    data class Answer(val result: RouteResult, val trafficProblem: String?)

    suspend fun eta(from: GeoPoint, to: GeoPoint): Answer {
        val configured = trafficProviders.filter { it.configured }
        val problems = mutableListOf<String>()
        for (provider in configured) {
            when (val result = provider.eta(from, to)) {
                is RouteResult.Ok -> return Answer(result, null)
                is RouteResult.Failed -> problems += result.reason
            }
        }
        val why = if (configured.isEmpty()) "no traffic provider configured" else problems.joinToString("; ")
        return Answer(fallback.eta(from, to), why)
    }
}

object TrafficLabels {
    /** freshness values stored with plans: "traffic", "routed", or "estimated". */
    fun freshness(kind: EtaKind) = if (kind == EtaKind.LIVE_TRAFFIC) "traffic" else "routed"

    fun source(eta: RouteEta, trafficProblem: String?): String = when (eta.kind) {
        EtaKind.LIVE_TRAFFIC -> {
            val delta = eta.typicalMinutes?.let { eta.minutes - it }
            val compare = when {
                delta == null -> ""
                delta >= 2 -> String.format(Locale.US, ", %d min slower than typical", delta)
                delta <= -2 -> String.format(Locale.US, ", %d min faster than typical", -delta)
                else -> ", about typical"
            }
            "live traffic via ${eta.provider}$compare"
        }
        EtaKind.NO_TRAFFIC -> "no traffic data: road-network time via ${eta.provider}" +
            (trafficProblem?.let { " ($it)" } ?: "")
    }
}
