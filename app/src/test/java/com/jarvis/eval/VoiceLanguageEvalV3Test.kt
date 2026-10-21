package com.jarvis.eval

import com.jarvis.core.Confirmations
import com.jarvis.core.CreateResult
import com.jarvis.core.JarvisTimer
import com.jarvis.core.JarvisTimerEngine
import com.jarvis.core.LanguageNormalizer
import com.jarvis.core.LocalReplies
import com.jarvis.core.MeetingRequestParser
import com.jarvis.core.PendingConfirmation
import com.jarvis.core.PlanStep
import com.jarvis.core.ReplyLang
import com.jarvis.core.TaskKinds
import com.jarvis.core.TaskReplies
import com.jarvis.core.TaskStates
import com.jarvis.core.TimerReplies
import com.jarvis.data.AgentTask
import com.jarvis.data.Memory
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * eval/intents/voice_language_v3.json: meeting extraction, answers to a waiting confirmation, and the language of
 * JARVIS's own replies (judged by [judge], which does not use the app's language detection). Deterministic; no
 * network. `JARVIS_V3_WRITE=<label>` writes eval/results/voice_language_v3__<label>.jsonl and a report; a normal run
 * checks the committed "final" results when they exist.
 */
class VoiceLanguageEvalV3Test {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, zone)
    private val ms = now.toInstant().toEpochMilli()

    data class Row(val id: String, val part: String, val split: String, val lang: String, val got: String, val ok: Boolean)

    /** Script and marker words only: Devanagari → hindi; Roman with Hinglish function words → hinglish; else en. */
    fun judge(reply: String): String {
        val letters = reply.filter { it.isLetter() }
        val deva = letters.count { it in 'ऀ'..'ॿ' }
        if (letters.isNotEmpty() && deva * 10 >= letters.length * 3) return "hindi"
        val markers = setOf("hai", "hain", "kar", "diya", "diye", "aapka", "aapke", "aapko", "aap", "ke", "ki", "ka", "liye", "nahi",
            "koi", "raha", "rahi", "gaya", "gayi", "mein", "hoga", "kya", "bataiye", "boliye", "abhi", "dunga", "doonga", "karun", "karein", "haan")
        val words = reply.lowercase().split(Regex("""[^a-z]+""")).filter { it.isNotBlank() }
        return if (words.count { it in markers } >= 2) "hinglish" else "en"
    }

    private fun task(title: String, at: Long) =
        AgentTask("t", TaskKinds.REMINDER, title, "{}", at, zone.id, TaskStates.SCHEDULED, 0, 3, 120_000, null, null, "not_required", null, ms, ms)

    /** One reply of [kind] for [text], as the app composes it. */
    private fun render(kind: String, text: String): String {
        val lang = LocalReplies.languageOf(text)
        val tomorrow7 = now.plusDays(1).withHour(19).withMinute(0)
        val timer = JarvisTimer(1, "", 600, ms, JarvisTimer.State.RUNNING)
        return when (kind) {
            "reminder.list" -> TaskReplies.list(listOf(task("gym", tomorrow7.toInstant().toEpochMilli())), emptyList(), now, emptyList(), "all", lang)
            "reminder.created" -> TaskReplies.created(CreateResult.Scheduled(task("gym", tomorrow7.toInstant().toEpochMilli()), exact = true, notificationsOff = false), "gym", tomorrow7, now, lang)
            "reminder.ask_title" -> LocalReplies.askReminderTitle(lang)
            "reminder.cancelled" -> LocalReplies.reminderCancelled("gym", verified = true, lang = lang)
            "timer.started" -> TimerReplies.started(JarvisTimerEngine.Started(timer, duplicate = false, notificationsShown = true), exact = true, zone = zone, lang = lang)
            "timer.cancelled" -> TimerReplies.stopped(timer, wasRinging = false, lang = lang)
            "memory.forget_proposal" -> Confirmations.forgetProposal(Memory("m", "my gym is in Dadar", null, "c", 1, null, true, "fact", "", 1), lang)
            "nothing_pending" -> LocalReplies.nothingPending(lang)
            "meeting.proposal" -> Confirmations.meetingProposal(PlanStep("meeting.create", mapOf("title" to "Project review", "date" to "2026-10-22", "hour" to "10", "minute" to "0", "location" to "VJTI")), lang)
            else -> error("unknown kind $kind")
        }
    }

    private fun score(o: JSONObject): Row {
        val text = o.getString("text")
        val base = Row(o.getString("id"), o.getString("part"), o.getString("split"), o.getString("lang"), "", false)
        return when (base.part) {
            "meeting" -> {
                val d = MeetingRequestParser.draft(LanguageNormalizer.normalize(text), now)
                val e = o.getJSONObject("expected")
                val days = d.date?.let { ChronoUnit.DAYS.between(now.toLocalDate(), it) }
                val got = JSONObject().put("title", d.title ?: JSONObject.NULL).put("hour", d.hour ?: JSONObject.NULL).put("minute", d.minute ?: JSONObject.NULL)
                    .put("days", days ?: JSONObject.NULL).put("location", d.location ?: JSONObject.NULL)
                fun same(k: String): Boolean = if (e.isNull(k)) got.isNull(k) else !got.isNull(k) && got.get(k).toString().equals(e.get(k).toString(), ignoreCase = true)
                base.copy(got = got.toString(), ok = listOf("title", "hour", "minute", "days", "location").all(::same))
            }
            "answer" -> {
                val got = when (Confirmations.answer(PendingConfirmation.ForgetMemory(Memory("m", "x", null, "c", 1, null, true), ms), LanguageNormalizer.normalize(text), ms + 1_000)) {
                    Confirmations.Answer.APPROVE -> "approve"
                    Confirmations.Answer.REJECT -> "reject"
                    Confirmations.Answer.UNCLEAR -> "retry"
                    Confirmations.Answer.NOT_AN_ANSWER -> "new_request"
                    Confirmations.Answer.EXPIRED -> "expired"
                }
                base.copy(got = got, ok = got == o.getString("expected"))
            }
            else -> {
                val reply = render(o.getString("kind"), text)
                val got = judge(reply)
                base.copy(got = "$got: $reply", ok = got == o.getString("expected"))
            }
        }
    }

    private fun table(rows: List<Row>): String {
        val lines = mutableListOf("| Part | Split | en | hinglish | hindi | all |", "|---|---|---|---|---|---|")
        for (part in listOf("meeting", "answer", "reply")) for (split in listOf("dev", "heldout")) {
            val sub = rows.filter { it.part == part && it.split == split }
            fun cell(l: String?) = sub.filter { l == null || it.lang == l }.let { s -> "${s.count { it.ok }}/${s.size}" }
            lines += "| $part | $split | ${cell("en")} | ${cell("hinglish")} | ${cell("hindi")} | ${cell(null)} |"
        }
        return lines.joinToString("\n")
    }

    private fun line(r: Row) = JSONObject().put("id", r.id).put("part", r.part).put("split", r.split).put("lang", r.lang).put("got", r.got).put("ok", r.ok).toString()

    @Test
    fun voiceLanguageV3() {
        val items = JSONObject(File("../eval/intents/voice_language_v3.json").readText()).getJSONArray("items")
        val rows = (0 until items.length()).map { score(items.getJSONObject(it)) }
        val label = System.getenv("JARVIS_V3_WRITE")
        if (!label.isNullOrBlank()) {
            File("../eval/results/voice_language_v3__$label.jsonl").writeText(rows.joinToString("") { line(it) + "\n" })
            val misses = rows.filter { !it.ok }.joinToString("\n") { "| ${it.id} | ${it.split} | ${it.lang} | ${it.got.replace("|", "/")} |" }
            File("../eval/reports/voice_language_v3__$label.md").writeText(listOf(
                "# Voice and language evaluation v3: $label", "",
                "- Dataset: eval/intents/voice_language_v3.json (${rows.size} items). Clock Wed 21 Oct 2026 14:00 IST; no network.",
                "- reply items are judged on the rendered reply by script/marker words, independent of the app's language detection.", "",
                table(rows), "", "## Misses", "| Item | Split | Lang | Got |", "|---|---|---|---|", misses
            ).joinToString("\n").trimEnd() + "\n")
            println(table(rows))
            return
        }
        val committed = File("../eval/results/voice_language_v3__final.jsonl")
        if (committed.exists()) assertEquals("v3 results changed; re-run with JARVIS_V3_WRITE=final if intended",
            committed.readLines().filter(String::isNotBlank), rows.map(::line))
    }
}
