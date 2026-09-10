package com.jarvis.core

import com.jarvis.data.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TravelTimeEstimate(
    val minutes: Int,
    val freshness: String,
    val source: String
)

interface TravelContextAgent {
    suspend fun estimate(event: Event, fallbackMinutes: Int): TravelTimeEstimate
}

class OsrmTravelContextAgent(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
) : TravelContextAgent {
    override suspend fun estimate(event: Event, fallbackMinutes: Int): TravelTimeEstimate =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://router.project-osrm.org/route/v1/driving/-73.9857,40.7484;-73.9680,40.7851?overview=false")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext estimated(fallbackMinutes, "OSRM HTTP ${response.code}")
                    }
                    val route = JSONObject(response.body?.string().orEmpty())
                        .optJSONArray("routes")
                        ?.optJSONObject(0)
                    val durationSeconds = route?.optDouble("duration", -1.0) ?: -1.0
                    if (durationSeconds <= 0) {
                        estimated(fallbackMinutes, "OSRM returned no route")
                    } else {
                        TravelTimeEstimate(
                            (durationSeconds / 60.0).toInt().coerceAtLeast(1),
                            "live",
                            "OSRM"
                        )
                    }
                }
            } catch (error: Exception) {
                estimated(fallbackMinutes, error.javaClass.simpleName)
            }
        }

    private fun estimated(minutes: Int, reason: String) =
        TravelTimeEstimate(minutes, "estimated", reason)
}
