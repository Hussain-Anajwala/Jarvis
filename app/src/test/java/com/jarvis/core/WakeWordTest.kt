package com.jarvis.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    /** Records what the detector feeds each model so the streaming layout can be checked exactly. */
    private class RecordingModels(private val scores: (Int) -> Float = { 0.9f }) : WakeWordModels {
        val melInputs = mutableListOf<FloatArray>()
        val embeddingWindows = mutableListOf<Array<FloatArray>>()
        val scoreInputs = mutableListOf<Array<FloatArray>>()
        override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
            melInputs += samples
            // ~one 32-bin frame per 160 samples, value = first sample so frames are traceable
            return Array(maxOf(1, (samples.size - 400) / 160 + 1)) { FloatArray(32) { samples.first() } }
        }
        override fun embedding(window: Array<FloatArray>): FloatArray {
            embeddingWindows += window
            return FloatArray(96) { window.last()[0] }
        }
        override fun score(features: Array<FloatArray>): Float {
            scoreInputs += features
            return scores(scoreInputs.size)
        }
    }

    private fun chunk(value: Short) = ShortArray(WakeWordDetector.CHUNK) { value }

    @Test
    fun streamingLayoutMatchesTheOpenWakeWordReference() {
        val models = RecordingModels()
        val detector = WakeWordDetector(models)
        models.melInputs.clear(); models.embeddingWindows.clear()
        detector.process(chunk(1))
        assertEquals(1280, models.melInputs.last().size) // first chunk: no context yet
        detector.process(chunk(2))
        val second = models.melInputs.last()
        assertEquals(1280 + 480, second.size) // chunk + 480 samples of context
        assertEquals(1f, second.first())     // context = tail of the previous chunk
        assertEquals(2f, second.last())
        detector.process(chunk(3))
        assertEquals(1760, models.melInputs.last().size)
        assertEquals(2f, models.melInputs.last().first())
        // Each step embeds exactly one 76-frame window and scores the last 16 embeddings.
        assertEquals(3, models.embeddingWindows.size)
        assertTrue(models.embeddingWindows.all { it.size == 76 && it[0].size == 32 })
        assertEquals(16, models.scoreInputs.last().size)
        // mel values are transformed x/10 + 2 before embedding
        assertEquals(2f / 10f + 2f, models.embeddingWindows.last().last()[0], 1e-6f) // first sample of that window is context from chunk 2
    }

    @Test
    fun firstFramesAreIgnoredWhileBuffersFill() {
        val detector = WakeWordDetector(RecordingModels { 0.99f })
        val scores = (1..6).map { detector.process(chunk(0)) }
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 0.99f, 0.99f), scores.toFloatArray(), 0f)
    }

    @Test
    fun triggerFiresOncePerPhraseAndRespectsSuppression() {
        var now = 0L
        val trigger = WakeWordTrigger(threshold = 0.5f, cooldownMs = 2_000) { now }
        assertFalse(trigger.onScore(0.49f))
        assertTrue(trigger.onScore(0.9f))
        now += 80
        assertFalse("consecutive high frames of one utterance fire once", trigger.onScore(0.95f))
        now += 2_000
        assertTrue(trigger.onScore(0.8f))
        // After JARVIS speaks/listens the listener ignores echoes for a moment.
        now += 5_000
        trigger.suppressFor(1_500)
        assertFalse(trigger.onScore(0.99f))
        now += 1_600
        assertTrue(trigger.onScore(0.99f))
    }

    /** Scores depend on every embedding the classifier sees, so any missing or stale embedding changes the score. */
    private class ContentModels : WakeWordModels {
        var embeddings = 0
        override fun melspectrogram(samples: FloatArray) =
            Array(8) { f -> FloatArray(32) { samples[minOf(samples.size - 1, f * 160)] } }
        override fun embedding(window: Array<FloatArray>): FloatArray {
            embeddings++
            return FloatArray(96) { window[it % window.size][0] + window.first()[1] }
        }
        override fun score(features: Array<FloatArray>) =
            features.withIndex().sumOf { (i, f) -> (i + 1) * f[5].toDouble() }.toFloat()
    }

    @Test
    fun quietGateSkipsModelsButScoresMatchOnceSoundReturns() {
        val plain = WakeWordDetector(ContentModels())
        val gatedModels = ContentModels()
        val gated = WakeWordDetector(gatedModels, quietRms = 100f)
        val audio = List(40) { chunk(3) } + List(20) { chunk((500 + it * 37).toShort()) } + List(40) { chunk(-4) } + List(10) { chunk(900) }
        val before = gatedModels.embeddings
        val plainScores = audio.map(plain::process)
        val gatedScores = audio.map(gated::process)
        // Quiet stretches score 0 and skip the costly models…
        assertTrue(gated.skippedSteps > 30)
        assertTrue(gatedModels.embeddings - before < audio.size)
        // …and every non-skipped step (sound plus hangover) scores exactly like the ungated detector.
        audio.indices.filter { gatedScores[it] != 0f }.forEach { assertEquals("step $it", plainScores[it], gatedScores[it], 1e-3f) }
        (40 until 60 + WakeWordDetector.HANGOVER).forEach { assertEquals("step $it", plainScores[it], gatedScores[it], 1e-3f) }
        (100 until 110).forEach { assertEquals("step $it", plainScores[it], gatedScores[it], 1e-3f) }
    }

    @Test
    fun micArbiterAndListenRequestsAreOneShot() {
        MicArbiter.setSpeaking(true)
        assertTrue(MicArbiter.micBusy)
        MicArbiter.setSpeaking(false)
        MicArbiter.setRecognizing(true)
        assertTrue(MicArbiter.micBusy)
        MicArbiter.setRecognizing(false)
        assertFalse(MicArbiter.micBusy)

        assertFalse(WakeWordEvents.consumeListenRequest())
        WakeWordEvents.requestListen()
        assertTrue(WakeWordEvents.consumeListenRequest())
        assertFalse("a request is consumed once (no replay on recomposition)", WakeWordEvents.consumeListenRequest())

        // A captured request that comes back through a notification tap also runs once.
        assertNull(WakeWordEvents.consumeCommand())
        WakeWordEvents.postCommand("what is the weather")
        assertEquals("what is the weather", WakeWordEvents.consumeCommand())
        assertNull("no replay after a rotation or recomposition", WakeWordEvents.consumeCommand())
    }

    @Test
    fun detectionOnlyCountsAndNeverStartsCaptureByItself() {
        val before = WakeWordEvents.detections
        WakeWordEvents.detected()
        assertEquals(before + 1, WakeWordEvents.detections)
        assertFalse("capture is the coordinator's job, not a UI flag", WakeWordEvents.consumeListenRequest())
    }
}
