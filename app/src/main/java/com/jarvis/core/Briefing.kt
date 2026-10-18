package com.jarvis.core

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.data.AppDatabase
import com.jarvis.data.Event
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/** User choices for the daily briefing. Off by default; private sections are opt-in. */
data class BriefingSettings(
    val enabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 30,
    val calendar: Boolean = true,
    val travel: Boolean = true,
    val weather: Boolean = true,
    val tasks: Boolean = true,
    val unreadEmail: Boolean = false,
    val newMessages: Boolean = false
)

object BriefingSettingsStore {
    private const val PREFS = "daily_briefing"

    fun load(context: Context): BriefingSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = BriefingSettings()
        return BriefingSettings(
            p.getBoolean("enabled", d.enabled), p.getInt("hour", d.hour), p.getInt("minute", d.minute),
            p.getBoolean("calendar", d.calendar), p.getBoolean("travel", d.travel), p.getBoolean("weather", d.weather),
            p.getBoolean("tasks", d.tasks), p.getBoolean("unreadEmail", d.unreadEmail), p.getBoolean("newMessages", d.newMessages)
        )
    }

    fun save(context: Context, s: BriefingSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled).putInt("hour", s.hour).putInt("minute", s.minute)
            .putBoolean("calendar", s.calendar).putBoolean("travel", s.travel).putBoolean("weather", s.weather)
            .putBoolean("tasks", s.tasks).putBoolean("unreadEmail", s.unreadEmail).putBoolean("newMessages", s.newMessages)
            .apply()
    }

    fun lastDelivered(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lastDeliveredDate", null)

    fun lastBriefing(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lastBriefing", null)

    fun markDelivered(context: Context, date: LocalDate, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lastDeliveredDate", date.toString()).putString("lastBriefing", text).apply()
    }
}

enum class SectionStatus { INCLUDED, UNAVAILABLE, OFF }

data class BriefingSection(val name: String, val status: SectionStatus, val line: String)

/** Everything a briefing can mention, already fetched; null means that source failed or is unavailable. */
data class BriefingInputs(
    val today: LocalDate,
    val events: List<Event>?,
    val nextMeetingTravel: TravelTimeEstimate?,
    val weather: WeatherNow?,
    val weatherProblem: String?,
    val openTasks: Int?,
    val unreadEmails: Int?,
    val emailProblem: String?,
    val newMessages: Int?,
    val messagesProblem: String?
)

/** Pure text assembly: no model call, so a briefing works offline and never sends personal data to a model. */
object BriefingAssembler {
    fun assemble(settings: BriefingSettings, input: BriefingInputs, zone: ZoneId, locale: Locale = Locale.getDefault()): Pair<String, List<BriefingSection>> {
        val time = DateTimeFormatter.ofPattern("h:mm a", locale)
        val sections = mutableListOf<BriefingSection>()

        if (!settings.calendar) sections += BriefingSection("Calendar", SectionStatus.OFF, "")
        else if (input.events == null) sections += BriefingSection("Calendar", SectionStatus.UNAVAILABLE, "Calendar: unavailable.")
        else {
            val todays = input.events.filter { java.time.Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate() == input.today }
                .sortedBy { it.startTime }
            val line = if (todays.isEmpty()) "Calendar: nothing scheduled today."
            else "Calendar: ${todays.size} today — " + todays.take(4).joinToString("; ") {
                "${java.time.Instant.ofEpochMilli(it.startTime).atZone(zone).format(time)} ${it.title}"
            } + if (todays.size > 4) "; …" else "."
            sections += BriefingSection("Calendar", SectionStatus.INCLUDED, line)
        }

        if (!settings.travel) sections += BriefingSection("Travel", SectionStatus.OFF, "")
        else input.nextMeetingTravel?.let {
            sections += BriefingSection("Travel", if (it.freshness == "traffic" || it.freshness == "routed") SectionStatus.INCLUDED else SectionStatus.UNAVAILABLE,
                "Travel to your next meeting: ${it.minutes} min (${TravelReplies.label(it)}).")
        } ?: run { sections += BriefingSection("Travel", SectionStatus.INCLUDED, "Travel: no meeting with a place today.") }

        if (!settings.weather) sections += BriefingSection("Weather", SectionStatus.OFF, "")
        else if (input.weather != null) sections += BriefingSection("Weather", SectionStatus.INCLUDED, "Weather: " + OpenMeteoClient.reply(input.weather).removePrefix("Near you now: "))
        else sections += BriefingSection("Weather", SectionStatus.UNAVAILABLE, "Weather: unavailable (${input.weatherProblem ?: "no data"}).")

        if (!settings.tasks) sections += BriefingSection("Tasks", SectionStatus.OFF, "")
        else if (input.openTasks == null) sections += BriefingSection("Tasks", SectionStatus.UNAVAILABLE, "Tasks: unavailable.")
        else sections += BriefingSection("Tasks", SectionStatus.INCLUDED, if (input.openTasks == 0) "Tasks: none open." else "Tasks: ${input.openTasks} open.")

        if (!settings.unreadEmail) sections += BriefingSection("Email", SectionStatus.OFF, "")
        else if (input.unreadEmails != null) sections += BriefingSection("Email", SectionStatus.INCLUDED, "Email: ${input.unreadEmails} unread in Gmail.")
        else sections += BriefingSection("Email", SectionStatus.UNAVAILABLE, "Email: unavailable (${input.emailProblem ?: "Gmail not connected"}).")

        if (!settings.newMessages) sections += BriefingSection("Messages", SectionStatus.OFF, "")
        else if (input.newMessages != null) sections += BriefingSection("Messages", SectionStatus.INCLUDED, "Messages: ${input.newMessages} new since notification access was on.")
        else sections += BriefingSection("Messages", SectionStatus.UNAVAILABLE, "Messages: unavailable (${input.messagesProblem ?: "notification access off"}).")

        val lines = sections.filter { it.status != SectionStatus.OFF }.map { it.line }
        val header = "Good morning. Briefing for ${input.today.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))}:"
        val text = if (lines.isEmpty()) "$header all briefing sections are turned off." else header + "\n" + lines.joinToString("\n")
        return text to sections
    }
}

object BriefingSchedule {
    /** Next run strictly after [now] at the chosen local time. */
    fun nextRun(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        val today = now.with(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** At most one scheduled briefing per day: skip when today's was already delivered. */
    fun shouldDeliver(today: LocalDate, lastDelivered: String?): Boolean = lastDelivered != today.toString()
}

/** Gathers briefing inputs; each source fails independently and is reported, not hidden. */
class BriefingService(
    private val context: Context,
    private val db: AppDatabase,
    private val location: LocationSource,
    private val travel: TravelContextAgent,
    private val weather: OpenMeteoClient = OpenMeteoClient(),
    private val unreadEmails: suspend () -> GmailResult<Int>?
) {
    suspend fun build(settings: BriefingSettings, zone: ZoneId = ZoneId.systemDefault()): Pair<String, List<BriefingSection>> {
        val today = LocalDate.now(zone)
        val events = runCatching { activeEvents() }.getOrNull()
        val nextMeeting = events?.filter { it.startTime > System.currentTimeMillis() && !it.locationText.isNullOrBlank() }
            ?.filter { java.time.Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate() == today }
            ?.minByOrNull { it.startTime }
        val travelEstimate = if (settings.travel && nextMeeting != null) runCatching { travel.estimate(nextMeeting, 30) }.getOrNull() else null
        var weatherNow: WeatherNow? = null
        var weatherProblem: String? = null
        if (settings.weather) {
            when (val here = location.current()) {
                is DeviceLocation.Available -> weatherNow = try { weather.current(here.point) } catch (_: java.io.IOException) { null }
                    .also { if (it == null) weatherProblem = "Open-Meteo unreachable" }
                else -> weatherProblem = here.reason()
            }
        }
        val tasks = runCatching { db.taskDao().observeAll().first().count { it.status == "open" } }.getOrNull()
        var unread: Int? = null
        var emailProblem: String? = null
        if (settings.unreadEmail) {
            when (val result = runCatching { unreadEmails() }.getOrNull()) {
                is GmailResult.Success -> unread = result.value
                is GmailResult.Failure -> emailProblem = result.detail
                null -> emailProblem = "Gmail not connected"
            }
        }
        var messages: Int? = null
        var messagesProblem: String? = null
        if (settings.newMessages) {
            if (JarvisNotificationListener.isAccessEnabled(context)) {
                messages = db.notificationRecordDao().unreadSince(System.currentTimeMillis() - NotificationCapturePolicy.RETENTION_MS).size
            } else messagesProblem = "notification access off"
        }
        return BriefingAssembler.assemble(
            settings,
            BriefingInputs(today, events, travelEstimate, weatherNow, weatherProblem, tasks, unread, emailProblem, messages, messagesProblem),
            zone
        )
    }

    private suspend fun activeEvents(): List<Event> {
        val activePlans = db.planDao().activePlans().map { it.id }.toSet()
        return activePlans.flatMap { db.planItemDao().forPlan(it) }
            .filter { it.itemType == "calendar_event" && it.status == "active" }
            .mapNotNull { item -> item.refId?.let { db.eventDao().findById(it) } }
    }
}

object BriefingScheduler {
    private const val WORK_NAME = "jarvis-daily-briefing"

    /** Replaces any pending briefing so changing the time never leaves two scheduled. */
    fun reschedule(context: Context, settings: BriefingSettings = BriefingSettingsStore.load(context)) {
        val manager = WorkManager.getInstance(context)
        if (!settings.enabled) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = ZonedDateTime.now()
        val delay = Duration.between(now, BriefingSchedule.nextRun(now, settings.hour, settings.minute)).toMillis()
        manager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DailyBriefingWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        )
    }
}

class DailyBriefingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = BriefingSettingsStore.load(context)
        if (!settings.enabled) return Result.success()
        val today = LocalDate.now()
        if (BriefingSchedule.shouldDeliver(today, BriefingSettingsStore.lastDelivered(context))) {
            val db = AppDatabase.create(context)
            val location = AndroidLocationSource(context)
            val service = BriefingService(context, db, location, OsrmTravelContextAgent(location), unreadEmails = {
                // Background: only a token Google grants silently; never shows UI from the worker.
                GmailClient(accessToken = { BackgroundGmailToken.silent(context) }).unreadCount()
            })
            val (text, _) = service.build(settings)
            NotificationPublisher.post(context, NotificationTier.NORMAL, "Daily briefing", text)
            BriefingSettingsStore.markDelivered(context, today, text)
            db.traceDao().insert(
                com.jarvis.data.DecisionTraceEntry(
                    java.util.UUID.randomUUID().toString(), null, "Daily briefing delivered (assembled on device, no model call)",
                    "low", "daily_briefing", "delivered", System.currentTimeMillis(), "background-system"
                )
            )
        }
        BriefingScheduler.reschedule(context, settings)
        return Result.success()
    }
}

/** Read-only Gmail token without any UI: returns null when Google would need to show consent. */
object BackgroundGmailToken {
    suspend fun silent(context: Context): String? = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val request = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
            .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope(GMAIL_READONLY_SCOPE)))
            .build()
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(context.applicationContext)
            .authorize(request)
            .addOnSuccessListener { result ->
                val token = if (result.hasResolution()) null else result.accessToken
                if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(token))
            }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(null)) }
    }
}
