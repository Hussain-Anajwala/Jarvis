package com.jarvis.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.jarvis.data.Event
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class TravelTimeEstimate(
    val minutes: Int,
    val freshness: String,
    val source: String
)

interface TravelContextAgent {
    suspend fun estimate(event: Event, fallbackMinutes: Int): TravelTimeEstimate
}

data class GeoPoint(val latitude: Double, val longitude: Double) {
    /** Coordinates leave the device only for routing, geocoding bias, and weather; 4 decimals ≈ 11 m. */
    fun lat4() = String.format(Locale.US, "%.4f", latitude)
    fun lon4() = String.format(Locale.US, "%.4f", longitude)
}

sealed interface DeviceLocation {
    data class Available(val point: GeoPoint, val ageMs: Long) : DeviceLocation
    data object PermissionDenied : DeviceLocation
    data object Disabled : DeviceLocation
    data object Unavailable : DeviceLocation

    fun reason(): String = when (this) {
        is Available -> "device location"
        PermissionDenied -> "location permission off"
        Disabled -> "location services off"
        Unavailable -> "no recent device location"
    }
}

interface LocationSource {
    suspend fun current(): DeviceLocation
}

object NoLocationSource : LocationSource {
    override suspend fun current(): DeviceLocation = DeviceLocation.Unavailable
}

/** One fresh fix via LocationManagerCompat (no Play services dependency), else a recent last-known fix. */
class AndroidLocationSource(context: Context, private val timeoutMs: Long = 10_000) : LocationSource {
    private val appContext = context.applicationContext

    override suspend fun current(): DeviceLocation {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return DeviceLocation.PermissionDenied
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return DeviceLocation.Unavailable
        if (!LocationManagerCompat.isLocationEnabled(manager)) return DeviceLocation.Disabled
        return try {
            val provider = when {
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> LocationManager.PASSIVE_PROVIDER
            }
            val fresh = withTimeoutOrNull(timeoutMs) { currentFix(manager, provider) }
            val fix = fresh ?: lastKnown(manager)
            fix?.let { DeviceLocation.Available(GeoPoint(it.latitude, it.longitude), ageMs(it)) } ?: DeviceLocation.Unavailable
        } catch (_: SecurityException) {
            DeviceLocation.PermissionDenied
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private suspend fun currentFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, Executors.newSingleThreadExecutor()) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }

    @Suppress("MissingPermission")
    private fun lastKnown(manager: LocationManager): Location? =
        manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
            .filter { ageMs(it) <= MAX_LAST_KNOWN_AGE_MS }
            .maxByOrNull { it.time }

    private fun ageMs(location: Location): Long =
        if (Build.VERSION.SDK_INT >= 17) (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        else System.currentTimeMillis() - location.time

    companion object {
        const val MAX_LAST_KNOWN_AGE_MS = 30L * 60 * 1000
    }
}

private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .callTimeout(6, TimeUnit.SECONDS)
    .build()

/** OpenStreetMap Nominatim search (usage policy: identifying User-Agent, ≤ 1 request/second). */
class NominatimGeocoder(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://nominatim.openstreetmap.org/search"
) {
    suspend fun geocode(query: String, near: GeoPoint?): GeoPoint? = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(200))
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "1")
            .apply {
                // Prefer results near the user ("college" should mean a nearby college) without excluding others.
                near?.let {
                    addQueryParameter("viewbox", "${it.longitude - 0.5},${it.latitude + 0.5},${it.longitude + 0.5},${it.latitude - 0.5}")
                    addQueryParameter("bounded", "0")
                }
            }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept-Language", "en").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Nominatim HTTP ${response.code}")
            parse(response.body?.string().orEmpty())
        }
    }

    companion object {
        const val USER_AGENT = "JARVIS-Android-student-project/0.1 (final-year project; low volume)"

        fun parse(body: String): GeoPoint? = try {
            val first = JSONArray(body).optJSONObject(0) ?: return null
            val lat = first.optString("lat").toDoubleOrNull()
            val lon = first.optString("lon").toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
        } catch (_: JSONException) {
            null
        }
    }
}

class OsrmRouter(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://router.project-osrm.org/route/v1/driving/"
) {
    /** Driving duration in minutes, or null when OSRM has no route. Throws IOException on network/HTTP failure. */
    suspend fun minutes(from: GeoPoint, to: GeoPoint): Int? = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/${from.lon4()},${from.lat4()};${to.lon4()},${to.lat4()}?overview=false"
        client.newCall(Request.Builder().url(url).header("User-Agent", NominatimGeocoder.USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("OSRM HTTP ${response.code}")
            parse(response.body?.string().orEmpty())
        }
    }

    companion object {
        fun parse(body: String): Int? = try {
            val seconds = JSONObject(body).optJSONArray("routes")?.optJSONObject(0)?.optDouble("duration", -1.0) ?: -1.0
            if (seconds <= 0 || seconds.isNaN()) null else (seconds / 60.0).toInt().coerceAtLeast(1)
        } catch (_: JSONException) {
            null
        }
    }
}

/**
 * Travel time from the device's real location to the event's place. "live" only when OSRM routed from an actual
 * device fix to a geocoded destination; otherwise "estimated" with the reason (permission off, place not found …).
 */
class OsrmTravelContextAgent(
    private val location: LocationSource = NoLocationSource,
    private val geocoder: NominatimGeocoder = NominatimGeocoder(),
    private val router: OsrmRouter = OsrmRouter()
) : TravelContextAgent {
    override suspend fun estimate(event: Event, fallbackMinutes: Int): TravelTimeEstimate {
        val place = event.locationText?.trim().orEmpty()
        if (place.isBlank()) return estimated(fallbackMinutes, "no meeting place")
        val origin = when (val here = location.current()) {
            is DeviceLocation.Available -> here.point
            else -> return estimated(fallbackMinutes, here.reason())
        }
        return try {
            val destination = geocoder.geocode(place, origin) ?: return estimated(fallbackMinutes, "place not found: $place")
            val minutes = router.minutes(origin, destination) ?: return estimated(fallbackMinutes, "no route found")
            TravelTimeEstimate(minutes, "live", "OSRM from device location")
        } catch (error: IOException) {
            estimated(fallbackMinutes, error.message ?: "network unavailable")
        } catch (error: RuntimeException) {
            estimated(fallbackMinutes, error.javaClass.simpleName)
        }
    }

    private fun estimated(minutes: Int, reason: String) = TravelTimeEstimate(minutes, "estimated", reason)

    companion object {
        fun forDevice(context: Context) = OsrmTravelContextAgent(AndroidLocationSource(context))
    }
}

data class WeatherNow(val temperatureC: Double, val description: String, val todayMinC: Double?, val todayMaxC: Double?, val rainChance: Int?)

/** Open-Meteo forecast (no key). Coordinates are rounded to ~1 km before leaving the device. */
class OpenMeteoClient(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://api.open-meteo.com/v1/forecast"
) {
    suspend fun current(point: GeoPoint): WeatherNow? = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", String.format(Locale.US, "%.2f", point.latitude))
            .addQueryParameter("longitude", String.format(Locale.US, "%.2f", point.longitude))
            .addQueryParameter("current", "temperature_2m,weather_code")
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "1")
            .build()
        client.newCall(Request.Builder().url(url).header("User-Agent", NominatimGeocoder.USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Open-Meteo HTTP ${response.code}")
            parse(response.body?.string().orEmpty())
        }
    }

    companion object {
        fun parse(body: String): WeatherNow? = try {
            val json = JSONObject(body)
            val current = json.optJSONObject("current")
            val temp = current?.optDouble("temperature_2m", Double.NaN) ?: Double.NaN
            val daily = json.optJSONObject("daily")
            fun firstDouble(name: String) = daily?.optJSONArray(name)?.optDouble(0, Double.NaN)?.takeUnless { it.isNaN() }
            if (current == null || temp.isNaN()) null else WeatherNow(
                temperatureC = temp,
                description = describe(current.optInt("weather_code", -1)),
                todayMinC = firstDouble("temperature_2m_min"),
                todayMaxC = firstDouble("temperature_2m_max"),
                rainChance = firstDouble("precipitation_probability_max")?.toInt()
            )
        } catch (_: JSONException) {
            null
        }

        /** WMO weather interpretation codes as used by Open-Meteo. */
        fun describe(code: Int): String = when (code) {
            0 -> "clear sky"
            1, 2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "fog"
            in 51..57 -> "drizzle"
            in 61..67, in 80..82 -> "rain"
            in 71..77, 85, 86 -> "snow"
            in 95..99 -> "thunderstorm"
            else -> "conditions unknown"
        }

        fun reply(weather: WeatherNow): String {
            fun t(v: Double) = "${Math.round(v)}°C"
            val today = if (weather.todayMinC != null && weather.todayMaxC != null) " Today ${t(weather.todayMinC)}–${t(weather.todayMaxC)}" else ""
            val rain = weather.rainChance?.let { ", $it% chance of rain" }.orEmpty()
            return "Near you now: ${t(weather.temperatureC)}, ${weather.description}.$today$rain. (Open-Meteo, live)"
        }
    }
}

object TravelReplies {
    /** "live", or "estimated: <reason>" so a fallback is never presented as routing from the user's location. */
    fun label(estimate: TravelTimeEstimate): String =
        if (estimate.freshness == "live") "live" else "estimated: ${estimate.source}"
}
