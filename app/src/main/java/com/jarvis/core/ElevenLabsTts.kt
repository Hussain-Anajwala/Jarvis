package com.jarvis.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Where streamed 16-bit mono PCM goes (an AudioTrack on the phone, a buffer in tests). */
interface PcmSink {
    fun start(sampleRateHz: Int)
    fun write(bytes: ByteArray, offset: Int, length: Int)
    /** Blocks until everything written has been played. */
    fun finish()
    fun stop()
}

/**
 * ElevenLabs text-to-speech over the official streaming REST endpoint
 * (POST /v1/text-to-speech/{voice_id}/stream, header xi-api-key). Audio is requested as raw PCM so playback starts
 * while the rest is still downloading. Only the assistant's answer text is sent — never history or user input.
 */
class ElevenLabsTts(
    private val apiKey: () -> String?,
    private val sinkFactory: () -> PcmSink,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://api.elevenlabs.io/v1/text-to-speech/",
    /** Receives short diagnostics (HTTP status, byte counts, timings). Never the key, the text, or the audio. */
    private val log: (String) -> Unit = {}
) : SpeechEngine {
    override val label = "ElevenLabs"
    override val available: Boolean get() = !apiKey().isNullOrBlank()

    @Volatile private var call: Call? = null
    @Volatile private var sink: PcmSink? = null

    override suspend fun speak(text: String, settings: SpeechSettings): SpeechResult = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf(String::isNotBlank) ?: return@withContext SpeechResult.Failed("no ElevenLabs API key configured")
        val voice = ElevenLabsVoices.resolve(settings.voiceId.trim()).id
        val body = JSONObject()
            .put("text", text.take(MAX_CHARS))
            .put("model_id", MODEL_ID)
            .put("voice_settings", JSONObject().put("speed", settings.rate.coerceIn(0.7f, 1.2f).toDouble()))
            .toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/$voice/stream?output_format=$OUTPUT_FORMAT")
            .header("xi-api-key", key)
            .header("Accept", "audio/pcm")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val startedAt = System.nanoTime()
        val newCall = client.newCall(request)
        call = newCall
        // Stop/cancel closes the socket, which ends the blocking read below.
        coroutineContext.job.invokeOnCompletion { newCall.cancel() }
        try {
            newCall.execute().use { response ->
                log("ElevenLabs HTTP ${response.code} after ${(System.nanoTime() - startedAt) / 1_000_000} ms")
                if (!response.isSuccessful) {
                    val code = response.code
                    return@withContext SpeechResult.Failed(describe(code, response.body?.string().orEmpty()), retryable = code == 429 || code >= 500)
                }
                val source = response.body?.byteStream() ?: return@withContext SpeechResult.Failed("empty audio response")
                // Receive the whole answer before playing it: if the connection drops half-way, nothing has been
                // heard yet and the phone voice can read the complete answer instead of finishing it in another voice.
                val audio = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    audio.write(buffer, 0, read)
                    if (audio.size() > MAX_AUDIO_BYTES) return@withContext SpeechResult.Failed("audio too long")
                }
                val bytes = audio.toByteArray()
                val usable = bytes.size and 1.inv() // whole 16-bit samples only
                if (usable == 0) return@withContext SpeechResult.Failed("no audio returned", retryable = true)
                log("ElevenLabs received $usable bytes = %.1f s of audio in ${(System.nanoTime() - startedAt) / 1_000_000} ms".format(usable / 2.0 / SAMPLE_RATE_HZ))
                coroutineContext.ensureActive()
                val out = sinkFactory().also { sink = it }
                out.start(SAMPLE_RATE_HZ)
                var offset = 0
                while (offset < usable) {
                    coroutineContext.ensureActive()
                    val length = minOf(8192, usable - offset)
                    out.write(bytes, offset, length)
                    offset += length
                }
                out.finish()
                log("ElevenLabs playback finished after ${(System.nanoTime() - startedAt) / 1_000_000} ms")
                SpeechResult.Completed
            }
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            SpeechResult.Failed("network error (${error.javaClass.simpleName})", retryable = true)
        } finally {
            call = null
        }
    }

    override fun stop() {
        call?.cancel()
        sink?.stop()
        sink = null
    }

    companion object {
        /** "George" — the voice used in ElevenLabs' official quickstart. Changeable in Settings. */
        const val DEFAULT_VOICE_ID = "JBFqnCBsd6RMkjVDRZzb"
        /** Low-latency model listed in ElevenLabs' text-to-speech overview (~75 ms). */
        const val MODEL_ID = "eleven_flash_v2_5"
        const val OUTPUT_FORMAT = "pcm_22050"
        const val SAMPLE_RATE_HZ = 22_050
        const val MAX_CHARS = 1_000
        /** About 4.5 minutes of 22 kHz mono PCM; far longer than any spoken answer. */
        const val MAX_AUDIO_BYTES = 12_000_000

        fun describe(code: Int, body: String): String {
            val detail = body.lowercase()
            return when {
                code == 401 && ("quota" in detail || "credits" in detail) -> "ElevenLabs quota or credits exhausted (HTTP 401)"
                code == 401 -> "ElevenLabs rejected the API key (HTTP 401)"
                code == 402 -> "ElevenLabs plan does not allow this request (HTTP 402)"
                code == 404 -> "voice ID not found (HTTP 404)"
                code == 422 -> "ElevenLabs rejected the request (HTTP 422)"
                code == 429 -> "ElevenLabs rate limit or concurrency limit (HTTP 429)"
                code >= 500 -> "ElevenLabs server error (HTTP $code)"
                else -> "ElevenLabs error (HTTP $code)"
            }
        }
    }
}
