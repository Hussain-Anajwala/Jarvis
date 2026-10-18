package com.jarvis.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the production wake-word path (ONNX Runtime on the phone + WakeWordDetector + WakeWordTrigger) over synthetic
 * 16 kHz clips. The same clips scored 0.998/0.999 (positive) and 0.001/0.000 (negative) in the Python reference.
 */
@RunWith(AndroidJUnit4::class)
class WakeWordModelTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pcm(name: String): ShortArray {
        val bytes = testContext.assets.open("wakeword-test/$name").use { it.readBytes() }
        // Walk RIFF chunks to the "data" chunk (headers are not always 44 bytes).
        val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var dataStart = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = le.getInt(offset + 4)
            if (id == "data") { dataStart = offset + 8; dataSize = minOf(size, bytes.size - dataStart); break }
            offset += 8 + size + (size and 1)
        }
        check(dataStart > 0) { "no data chunk in $name" }
        val data = ByteBuffer.wrap(bytes, dataStart, dataSize).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(data.remaining()).also { data.get(it) }
        val silence = ShortArray(WakeWordDetector.SAMPLE_RATE)
        return silence + samples + silence
    }

    private fun maxScore(models: WakeWordModels, name: String, quietRms: Float = 0f): Pair<Float, Int> {
        val detector = WakeWordDetector(models, quietRms = quietRms)
        val trigger = WakeWordTrigger(clock = { 0L })
        val audio = pcm(name)
        var max = 0f
        var detections = 0
        var i = 0
        while (i + WakeWordDetector.CHUNK <= audio.size) {
            val score = detector.process(audio.copyOfRange(i, i + WakeWordDetector.CHUNK))
            if (score > max) max = score
            if (trigger.onScore(score)) detections++
            i += WakeWordDetector.CHUNK
        }
        return max to detections
    }

    @Test
    fun heyJarvisIsDetectedOnceAndOtherPhrasesAreNot() {
        OnnxWakeWordModels(appContext).use { models ->
            // Both without and with the production quiet-audio gate (the clips are padded with 1 s of silence).
            for (gate in listOf(0f, WakeWordDetector.DEFAULT_QUIET_RMS)) {
                for (positive in listOf("hey_jarvis.wav", "hey_jarvis2.wav")) {
                    val (score, detections) = maxScore(models, positive, gate)
                    assertTrue("$positive (gate $gate) max score $score", score >= 0.5f)
                    assertTrue("$positive (gate $gate) should fire exactly once, fired $detections", detections == 1)
                }
                for (negative in listOf("negative.wav", "negative2.wav")) {
                    val (score, detections) = maxScore(models, negative, gate)
                    assertTrue("$negative (gate $gate) max score $score", score < 0.5f)
                    assertTrue(detections == 0)
                }
            }
        }
    }
}
