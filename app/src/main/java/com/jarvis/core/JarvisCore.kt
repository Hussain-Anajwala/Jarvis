package com.jarvis.core

import com.jarvis.BuildConfig
import com.jarvis.agents.CalendarAgent
import com.jarvis.agents.ReminderAgent
import com.jarvis.agents.TaskAgent
import com.jarvis.data.AppDatabase
import com.jarvis.data.DecisionTraceEntry
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.flow.first

class JarvisCore(
    private val db: AppDatabase,
    private val provider: ReasoningProvider,
    private val calendarProvider: CalendarProviderGateway? = null,
    private val calendar: CalendarAgent = CalendarAgent(db, calendarProvider),
    private val reminders: ReminderAgent = ReminderAgent(db),
    private val tasks: TaskAgent = TaskAgent(db),
    private val plans: PlanEngine = PlanEngine(db),
    private val travel: TravelContextAgent = OsrmTravelContextAgent(),
    private val memory: MemoryRepository? = null,
    private val personalFacts: PersonalFactsStore = memory ?: InMemoryPersonalFactsStore(),
    private val notificationAccess: () -> Boolean = { false },
    private val locationSource: LocationSource = NoLocationSource,
    private val weather: OpenMeteoClient = OpenMeteoClient(),
    /** The user's privacy choices (Settings → Privacy); read for every request. */
    private val privacy: () -> PrivacySettings = { PrivacySettings() }
) {
    /**
     * Every model call goes through this guard: the privacy policy runs before the provider chain (and therefore before
     * its retries and fallbacks) and records its decision without the protected value.
     */
    private val guarded = PrivacyGuardProvider(provider, privacy) { decision ->
        if (decision is PrivacyDecision.LocalOnly || !decision.audit.endsWith("nothing to remove")) {
            recordPhoneToolOutcome("privacy", decision.audit)
        }
    }
    companion object {
        private const val TAG = "JarvisCancellation"
        const val SUMMARY_LIMIT = 20
        val AVAILABLE_TOOLS = listOf(
            "meeting.create",
            "calendar.cancel",
            "reminder.create",
            "task.create",
            "communication.draft",
            "set_alarm"
        ) + ToolRouter.PHONE_TOOL_NAMES + ToolRouter.FAVORITE_SONG_TOOLS + ToolRouter.MESSAGE_TOOLS + ToolRouter.EMAIL_TOOLS + ToolRouter.MEMORY_TOOLS + ToolRouter.CONTEXT_TOOLS + ToolRouter.BRIEFING_TOOL
    }
    private val conversation = mutableListOf<ReasoningTurn>()
    private var lastResolvedModelName = "offline/offline-rule"
    /**
     * True when the last answer was built from private content (message notifications, memories, or a reply that
     * used memory context). Such answers are spoken only with the on-device voice, never a cloud voice.
     */
    var lastReplyPrivate: Boolean = false
        private set
    var lastEngineLabel: String = if (
        BuildConfig.GROQ_API_KEY.isNotBlank() ||
        BuildConfig.GEMINI_API_KEY.isNotBlank() ||
        BuildConfig.GEMINI_API_KEY_2.isNotBlank()
    ) "Reasoning providers configured" else "Offline parser (no provider keys configured)"
        private set

    /** A meeting waiting for its place: the answer to [MeetingDetails.PLACE_QUESTION] completes it. */
    private var pendingMeeting: PlanStep? = null
    /** The adaptive router's decision for the latest request (shown in Activity and used by the eval). */
    var lastRoute: RouteDecision? = null
        private set

    suspend fun handle(utterance: String): String {
        pendingMeeting?.let { waiting ->
            pendingMeeting = null
            if (MeetingDetails.cancels(utterance)) {
                conversation.add(ReasoningTurn("user", SensitiveData.forStorage(utterance)))
                return "Okay, I didn't create the meeting."
            }
            val place = when {
                MeetingDetails.looksLikeRequest(utterance) -> null
                MeetingDetails.saysNoPlace(utterance) -> ""
                else -> MeetingDetails.placeFromAnswer(utterance)
            }
            if (place != null) {
                conversation.add(ReasoningTurn("user", SensitiveData.forStorage(utterance)))
                return createMeeting(waiting.copy(parameters = waiting.parameters + ("location" to place)))
            }
            // Not an answer to the place question: handle it as a new request.
        }
        // A password, PIN, card or ID number is never sent to a cloud model, and never saved.
        val secret = SensitiveData.isSecret(utterance)
        if (secret && Regex("""\b(remember|save|store|note|keep)\b""", RegexOption.IGNORE_CASE).containsMatchIn(utterance)) {
            recordPhoneToolOutcome("remember_fact", "refused locally: looked like a credential or ID number (not sent to any model)")
            return SensitiveData.REFUSAL
        }
        MemoryQueryPolicy.localQuestion(utterance)?.let { topic ->
            conversation.add(ReasoningTurn("user", SensitiveData.forStorage(utterance)))
            return handleMemory(PlanStep("list_memories", mapOf("query" to topic)), utterance)
        }
        val availableTools = AVAILABLE_TOOLS
        val history = conversation.takeLast(10)
        // The offline parse is free and instant; the adaptive router decides whether the cloud is needed at all.
        val offline = OfflineRuleProvider().reason(ReasoningRequest(utterance, availableTools, history))
        val decision = AdaptiveRouter.decide(
            utterance, offline.steps.firstOrNull(), availableTools,
            cloudAvailable = provider !is OfflineRuleProvider && privacy().cloudAllowed,
            answeringQuestion = history.lastOrNull()?.let { it.role == "model" && it.text.trimEnd().endsWith("?") } == true
        )
        lastRoute = decision
        val local = decision.target != RouteTarget.CLOUD
        // Only cloud requests get memory context, and only the few memories that share words with the request.
        val relevantMemories = if (local) emptyList() else memory?.relevantFor(utterance).orEmpty()
        val response = if (local) {
            offline
        } else {
            guarded.reason(ReasoningRequest(utterance, availableTools, history, MemoryRetriever.promptLines(relevantMemories)))
        }
        if (decision.rule.startsWith("privacy")) {
            val kinds = SensitiveData.findings(utterance).sorted().joinToString()
            recordPhoneToolOutcome("privacy", "local only: request contains a $kinds (value not recorded)")
            if (offline.steps.isEmpty()) {
                conversation.add(ReasoningTurn("user", SensitiveData.forStorage(utterance)))
                return "That looked like a password, PIN, code, card or ID number, so it stayed on your phone and wasn't sent to any AI service."
            }
        }
        // Kept on the phone by the privacy policy and the offline parser found nothing to do: say why, plainly.
        if (response.privacyNote != null && response.steps.isEmpty()) {
            conversation.add(ReasoningTurn("user", SensitiveData.forStorage(utterance)))
            return response.privacyNote
        }
        recordPhoneToolOutcome("reasoning", "route ${decision.target.name.lowercase()}: ${decision.rule}")
        if (relevantMemories.isNotEmpty()) {
            recordPhoneToolOutcome(
                "list_memories",
                "${relevantMemories.size} relevant memory item(s) sent with the request to ${response.engineName}"
            )
        }
        updateEngineLabel(response)
        response.attempts.forEach { attempt ->
            val statusText = attempt.httpStatus?.let { "HTTP $it" } ?: "network error"
            val attemptTime = System.currentTimeMillis()
            val model = "${attempt.providerName}/${attempt.modelName}"
            insertTrace(
                DecisionTraceEntry(
                    id = UUID.randomUUID().toString(),
                    planId = null,
                    actionSummary = "$model attempt $statusText${attempt.detail?.let { ": $it" }.orEmpty()}",
                    riskLevel = "low",
                    toolName = "reasoning.request",
                    outcome = attempt.httpStatus?.let { "http_$it" }
                        ?: if (attempt.detail.orEmpty().contains("budget", ignoreCase = true)) "budget_skipped"
                        else "network_error",
                    createdAt = attemptTime,
                    modelName = model
                )
            )
        }
        conversation.add(ReasoningTurn("user", SensitiveData.forStorage(utterance)))
        conversation.add(ReasoningTurn("model", response.reply))
        while (conversation.size > 20) conversation.removeAt(0)
        val step = response.steps.firstOrNull()
        val traceOutcome = response.failureReason?.let { "fallback: $it" }
            ?: if (step == null) "answered" else "planned"
        insertTrace(
            DecisionTraceEntry(
                id = UUID.randomUUID().toString(),
                planId = null,
                actionSummary = response.failureReason?.let {
                    "${response.engineName}/${response.modelName ?: "unknown"} unavailable: $it; used offline parser"
                } ?: "Reasoning response from $lastResolvedModelName",
                riskLevel = step?.let { ReasoningToolSchema.definition(it.toolName)?.riskLevel } ?: "low",
                toolName = step?.toolName ?: "reasoning",
                outcome = traceOutcome,
                createdAt = System.currentTimeMillis(),
                modelName = lastResolvedModelName
            )
        )
        // Only answers that quote other people's words (messages, email) are private for the cloud voice. An answer that
        // merely used a saved memory is not: flagging those made the chosen voice switch unpredictably between answers.
        lastReplyPrivate = false
        if (step == null) return response.reply
        val route = ToolRouter.route(step, availableTools)
        if (step.toolName == "list_new_messages") lastReplyPrivate = true
        if (route is ToolRoute.Reject) {
            val error = "I couldn't safely process that request. Please try again with the missing details."
            Log.w(TAG, "Rejected reasoning tool call ${step.toolName}: ${route.reason}")
            insertTrace(
                DecisionTraceEntry(
                    UUID.randomUUID().toString(),
                    null,
                    "Rejected invalid tool call from ${response.engineName}",
                    "low",
                    step.toolName,
                    "invalid",
                    System.currentTimeMillis(),
                    lastResolvedModelName
                )
            )
            // A cloud call with missing or placeholder arguments (e.g. hour "?") gets the local clarifying question.
            if (response.engineName != "offline") {
                val local = offline
                if (local.steps.isEmpty()) return local.reply
            }
            return error
        }
        // A model may answer "turn the alarm off" with set_alarm; cancel, dismiss, or snooze wording never creates one.
        if ((route is ToolRoute.AlarmHandoff || (route is ToolRoute.PhoneTool && route.step.toolName == "set_timer")) &&
            ClockIntentGuard.blocksCreation(utterance)
        ) {
            recordPhoneToolOutcome(step.toolName, "blocked: request was to stop, not create")
            return ClockIntentGuard.REPLY
        }
        when (route) {
            ToolRoute.ConfirmCancel -> return "CONFIRM_CANCEL"
            is ToolRoute.DraftCommunication -> return "DRAFT_COMMUNICATION:${route.recipient}\u0000${route.body}"
            is ToolRoute.AlarmHandoff -> return "ALARM_HANDOFF:${route.hour}:${route.minute}\u0000${route.label}"
            is ToolRoute.PhoneTool -> return phoneToolRequest(route.step)
            is ToolRoute.FavoriteSong -> return handleFavoriteSong(route.step)
            is ToolRoute.Messages -> return handleMessages(route.step)
            is ToolRoute.Memory -> return handleMemory(route.step, utterance)
            is ToolRoute.Weather -> return handleWeather()
            ToolRoute.Briefing -> return "RUN_BRIEFING"
            is ToolRoute.Email -> return "EMAIL_TOOL:" + PhoneToolRequestCodec.encode(route.step)
            is ToolRoute.MeetingCreate, is ToolRoute.Agent, is ToolRoute.Reject -> Unit
        }
        if (route is ToolRoute.MeetingCreate) {
            val location = step.parameters["location"].orEmpty()
            val said = listOf(utterance) + conversation.filter { it.role == "user" }.takeLast(3).map { it.text } + MemoryRetriever.promptLines(relevantMemories)
            return when {
                MeetingDetails.grounded(location, said) -> createMeeting(step)
                MeetingDetails.saysNoPlace(utterance) -> createMeeting(step.copy(parameters = step.parameters + ("location" to "")))
                else -> {
                    // The venue was not something the user said: ask instead of using it.
                    pendingMeeting = step.copy(parameters = step.parameters - "location")
                    MeetingDetails.PLACE_QUESTION
                }
            }
        }

        val agent = when {
            step.toolName.startsWith("reminder.") -> reminders
            step.toolName.startsWith("task.") -> tasks
            else -> calendar
        }
        return runAgentStep(agent, step, response)
    }

    private suspend fun createMeeting(step: PlanStep): String {
        run {
            val eventResult = calendar.execute(ToolCall(step.toolName, "low", step.parameters, null))
            if (eventResult.status != "success") return "I could not create the meeting: ${eventResult.error}"
            val reminderId = UUID.randomUUID().toString()
            val event = db.eventDao().findById(eventResult.data)
                ?: return "I could not create the meeting event."
            val estimate = travel.estimate(event, 0)
            val calendarStatus = eventResult.details["calendarStatus"] ?: "local-only:calendar provider unavailable"
            plans.createMeetingPlan(
                step.parameters["title"]?.takeIf(String::isNotBlank) ?: MeetingDetails.DEFAULT_TITLE,
                event.id,
                reminderId,
                estimate,
                calendarStatus,
                lastResolvedModelName
            )
            val calendarMessage = when {
                calendarStatus.startsWith("synced:") -> " Added to ${calendarStatus.removePrefix("synced:")} ."
                calendarStatus == "local-only:no calendar account available" -> " Saved locally only — no calendar account available."
                else -> " Saved locally only — calendar ${calendarStatus.removePrefix("local-only:")} ."
            }
            val meetingTime = java.text.SimpleDateFormat("EEE, MMM d 'at' h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(event.startTime))
            val where = event.locationText?.takeIf(String::isNotBlank)?.let { " at $it" } ?: " (no location)"
            return "${event.title} created for $meetingTime$where. ${TravelReplies.sentence(estimate)}$calendarMessage"
        }
    }

    private suspend fun runAgentStep(agent: JarvisAgent, step: PlanStep, response: ReasoningResponse): String {
        val result = agent.execute(ToolCall(step.toolName, "low", step.parameters, null))
        insertTrace(
            DecisionTraceEntry(
                UUID.randomUUID().toString(),
                null,
                "${response.reply} [engine=${response.engineName}]",
                "low",
                step.toolName,
                result.status,
                System.currentTimeMillis(),
                lastResolvedModelName
            )
        )
        return if (result.status == "success") {
            result.userMessage ?: response.reply
        } else {
            result.userMessage ?: "I could not complete that action: ${result.error}"
        }
    }

    private fun updateEngineLabel(response: ReasoningResponse) {
        lastResolvedModelName = "${response.engineName}/${response.modelName ?: "offline-rule"}"
        lastEngineLabel = when {
            response.failureReason != null -> {
                val statuses = response.attempts
                    .filter { it.providerName != "offline" }
                    .takeLast(3)
                    .joinToString("; ") { attempt ->
                        val status = attempt.httpStatus?.let { "HTTP $it" } ?: "skipped"
                        "${attempt.providerName}/${attempt.modelName} $status"
                    }
                "Offline fallback${statuses.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()}: ${response.failureReason}"
            }
            response.engineName == "offline" -> "Offline parser"
            else -> {
                val status = response.attempts.lastOrNull {
                    it.providerName == response.engineName && it.modelName == response.modelName
                }?.httpStatus?.let { "; HTTP $it" }.orEmpty()
                "${response.engineName} (${response.modelName ?: "model unavailable"}$status)"
            }
        }
    }

    private fun phoneToolRequest(step: PlanStep): String =
        "PHONE_TOOL:" + PhoneToolRequestCodec.encode(step)

    /** Counts and lists come only from the local notification store; no model sees them. */
    private suspend fun handleMessages(step: PlanStep): String {
        if (!notificationAccess()) {
            recordPhoneToolOutcome(step.toolName, "notification access off")
            return NewMessageReplies.ACCESS_OFF
        }
        val dao = db.notificationRecordDao()
        val records = dao.unreadSince(System.currentTimeMillis() - NotificationCapturePolicy.RETENTION_MS)
        val filter = MessageFilter.from(step)
        recordPhoneToolOutcome(step.toolName, "answered locally")
        return when (step.toolName) {
            "count_new_messages" -> NewMessageReplies.count(records, filter)
            "list_new_messages" -> {
                val (reply, shownIds) = NewMessageReplies.list(records, filter)
                if (shownIds.isNotEmpty()) dao.markRead(shownIds)
                reply
            }
            else -> {
                val matching = records.count(filter::matches)
                if (matching == 0) NewMessageReplies.count(records, filter)
                else "CONFIRM_SUMMARY:" + org.json.JSONObject()
                    .put("app", filter.app.orEmpty())
                    .put("contact", filter.contact.orEmpty())
                    .put("count", minOf(matching, SUMMARY_LIMIT))
                    .toString()
            }
        }
    }

    /**
     * Runs only after the user confirms the disclosure dialog: sends up to [SUMMARY_LIMIT] sender names and
     * snippets (phone-number senders are already masked) to the provider chain, without tools.
     */
    suspend fun summarizeMessages(filter: MessageFilter): String {
        val dao = db.notificationRecordDao()
        val records = dao.unreadSince(System.currentTimeMillis() - NotificationCapturePolicy.RETENTION_MS)
            .filter(filter::matches)
            .sortedByDescending { it.postedAt }
            .take(SUMMARY_LIMIT)
        if (records.isEmpty()) return "No new messages${filter.describe()} to summarize."
        val response = guarded.reason(
            ReasoningRequest(NewMessageReplies.summaryPrompt(records), emptyList(), purpose = RequestPurpose.MESSAGE_SUMMARY, userConsented = true)
        )
        response.privacyNote?.let { return it }
        updateEngineLabel(response)
        if (response.engineName == "offline") {
            recordPhoneToolOutcome("summarize_new_messages", "no cloud model available; nothing sent")
            return "No cloud model was available, so nothing was summarized. Ask \"show my new messages\" to read them on this device."
        }
        dao.markRead(records.map { it.id })
        val model = "${response.engineName}/${response.modelName ?: "unknown"}"
        recordPhoneToolOutcome("summarize_new_messages", "sent ${records.size} message snippet(s) to $model after confirmation")
        return "Summary of ${records.size} message(s) by $model:\n${response.reply}"
    }

    private suspend fun handleWeather(): String {
        val here = locationSource.current()
        if (here !is DeviceLocation.Available) {
            recordPhoneToolOutcome("get_weather", here.reason())
            return when (here) {
                DeviceLocation.PermissionDenied -> "Location permission is off, so I can't get local weather. Turn on Location in Permissions."
                DeviceLocation.Disabled -> "Location services are off on this phone, so I can't get local weather."
                else -> "I couldn't get a recent location, so I can't get local weather right now."
            }
        }
        val now = try {
            weather.current(here.point)
        } catch (_: java.io.IOException) {
            null
        }
        recordPhoneToolOutcome("get_weather", if (now != null) "Open-Meteo answered" else "weather unavailable")
        return now?.let(OpenMeteoClient::reply) ?: "Weather is unavailable right now (Open-Meteo could not be reached)."
    }

    private suspend fun handleMemory(step: PlanStep, utterance: String): String {
        val repository = memory ?: return "Memory is not available in this build."
        return when (step.toolName) {
            "remember_fact" -> {
                if (SensitiveData.isSecret(step.parameters.getValue("text")) || SensitiveData.isSecret(utterance)) {
                    recordPhoneToolOutcome("remember_fact", "refused: looked like a credential or ID number")
                    return SensitiveData.REFUSAL
                }
                val classified = MemoryClassifier.classify(step.parameters.getValue("text"))
                    ?: return "What should I remember?"
                val saved = repository.remember(classified, "conversation")
                recordPhoneToolOutcome("remember_fact", "saved a ${saved.type} memory")
                MemoryReplies.remembered(saved)
            }
            "forget_memory" -> {
                val query = step.parameters.getValue("query")
                val removed = repository.forget(query)
                recordPhoneToolOutcome("forget_memory", if (removed != null) "deleted one memory" else "no single match")
                if (removed != null) "Forgot: \"${removed.contentText}\"."
                else "I couldn't find exactly one memory matching \"$query\". Open the Memory tab to choose."
            }
            else -> {
                val all = repository.all()
                // "Tell me about me" is not a request to read out everything: ask, list a topic, or review all.
                when (val decision = MemoryQueryPolicy.decide(utterance, step.parameters["query"])) {
                    MemoryQuery.Clarify -> {
                        recordPhoneToolOutcome("list_memories", "asked which memories (ambiguous request)")
                        MemoryQueryPolicy.clarification(all)
                    }
                    MemoryQuery.ReviewAll -> {
                        val shown = all.filterNot { SensitiveData.isSecret(it.contentText) }
                        recordPhoneToolOutcome("list_memories", "listed ${shown.size} memory item(s) locally on request")
                        MemoryReplies.list(shown, null)
                    }
                    is MemoryQuery.Topic -> {
                        val shown = MemoryRetriever.relevant(decision.query, all, k = 10)
                        recordPhoneToolOutcome("list_memories", "listed ${shown.size} matching memory item(s) locally")
                        MemoryReplies.list(shown, decision.query)
                    }
                }
            }
        }
    }

    /** Runs only after the user confirms the email-summary disclosure. Addresses are never included. */
    suspend fun summarizeEmails(emails: List<EmailSummary>): String {
        if (emails.isEmpty()) return "No emails have arrived in your inbox today."
        val batch = emails.take(SUMMARY_LIMIT)
        val response = guarded.reason(
            ReasoningRequest(EmailReplies.summaryPrompt(batch), emptyList(), purpose = RequestPurpose.EMAIL_SUMMARY, userConsented = true)
        )
        response.privacyNote?.let { return it }
        updateEngineLabel(response)
        if (response.engineName == "offline") {
            recordPhoneToolOutcome("summarize_emails", "no cloud model available; nothing sent")
            return "No cloud model was available, so nothing was summarized. Ask \"show today's emails\" to read them here."
        }
        val model = "${response.engineName}/${response.modelName ?: "unknown"}"
        recordPhoneToolOutcome("summarize_emails", "sent ${batch.size} email snippet(s) to $model after confirmation")
        return "Summary of ${batch.size} email(s) by $model:\n${response.reply}"
    }

    /**
     * Drafts an email from the recipient's name and the user's request (no address, no contact data).
     * Falls back to a plain template when no cloud model answers or the reply is not a usable draft.
     */
    suspend fun draftEmail(recipientName: String, request: String, tone: String?): Pair<EmailDraft, String> {
        val senderName = personalFacts.get(PersonalFactsStore.USER_NAME)
        val signOff = personalFacts.get(PersonalFactsStore.EMAIL_SIGN_OFF)
        val response = guarded.reason(
            ReasoningRequest(EmailComposer.prompt(recipientName, request, tone, senderName, signOff), emptyList(), purpose = RequestPurpose.EMAIL_DRAFT)
        )
        updateEngineLabel(response)
        val drafted = if (response.engineName == "offline") null else EmailComposer.parse(response.reply)
        val source = if (drafted != null) "${response.engineName}/${response.modelName ?: "unknown"}" else "template"
        recordPhoneToolOutcome("compose_email", "draft prepared by $source")
        return (drafted ?: EmailComposer.template(recipientName, request, senderName, signOff)) to source
    }

    /**
     * Writes the final email once [EmailDialogue] has every essential fact. A cloud model may word it, but its draft is
     * used only if it kept the user's dates and left no placeholders; otherwise (and offline) [EmailWriter] writes it
     * from the facts alone.
     */
    suspend fun composeEmail(request: EmailRequest, greetingName: String?): Pair<EmailDraft, String> {
        val senderName = personalFacts.get(PersonalFactsStore.USER_NAME)
        val signOff = personalFacts.get(PersonalFactsStore.EMAIL_SIGN_OFF)
        val response = guarded.reason(ReasoningRequest(EmailWriter.factsPrompt(request, greetingName, senderName, signOff), emptyList(), purpose = RequestPurpose.EMAIL_DRAFT))
        updateEngineLabel(response)
        val drafted = if (response.engineName == "offline") null else EmailComposer.parse(response.reply)?.takeIf { EmailWriter.acceptable(it, request) }
        val source = if (drafted != null) "${response.engineName}/${response.modelName ?: "unknown"}" else "template"
        recordPhoneToolOutcome("compose_email", "draft prepared by $source")
        val fallback = if (request.kind == EmailRequest.Kind.LEAVE) EmailWriter.leave(request, greetingName, senderName, signOff)
        else EmailWriter.general(request, greetingName, senderName, signOff)
        return (drafted ?: fallback) to source
    }

    private suspend fun handleFavoriteSong(step: PlanStep): String {
        val outcome = FavoriteSongTools.handle(step, personalFacts)
        // The song title is a personal preference; the trace records only the action.
        recordPhoneToolOutcome(
            step.toolName,
            if (outcome is FavoriteSongOutcome.PlayMusic) "resolved locally" else "answered locally"
        )
        return when (outcome) {
            is FavoriteSongOutcome.Reply -> outcome.message
            is FavoriteSongOutcome.PlayMusic -> phoneToolRequest(outcome.step)
        }
    }

    suspend fun recordCommunicationHandoff(body: String) {
        insertTrace(
            DecisionTraceEntry(
                UUID.randomUUID().toString(),
                null,
                // The draft text is the user's own message: record that it happened, never what it said.
                "Drafted a message and handed it off (${body.length} characters)",
                "medium",
                "communication.handoff",
                "confirmed",
                System.currentTimeMillis(),
                lastResolvedModelName
            )
        )
    }

    /** Activity/trace records never keep a secret value: it is replaced before the row is written. */
    private suspend fun insertTrace(entry: DecisionTraceEntry) = db.traceDao().insert(
        entry.copy(actionSummary = SensitiveData.forStorage(entry.actionSummary), outcome = SensitiveData.forStorage(entry.outcome))
    )

    suspend fun recordPhoneToolOutcome(toolName: String, outcome: String) {
        val definition = ReasoningToolSchema.definition(toolName)
        insertTrace(
            DecisionTraceEntry(
                UUID.randomUUID().toString(),
                null,
                "$toolName phone action $outcome",
                definition?.riskLevel ?: "low",
                toolName,
                outcome,
                System.currentTimeMillis(),
                lastResolvedModelName
            )
        )
    }

    suspend fun recordAlarmHandoff(hour: Int, minute: Int) {
        val time = String.format("%02d:%02d", hour, minute)
        insertTrace(
            DecisionTraceEntry(
                UUID.randomUUID().toString(),
                null,
                "Handed off alarm request to Clock app for $time",
                "low",
                "alarm.handoff",
                "executed",
                System.currentTimeMillis(),
                lastResolvedModelName
            )
        )
    }

    suspend fun confirmCancel(planId: String): String {
        Log.d(TAG, "Executing cancellation for plan=$planId")
        val plan = db.planDao().observeAll().first().firstOrNull { it.id == planId && it.status == "active" }
        if (plan == null) return "That plan is no longer active."
        val eventItem = db.planItemDao().forPlan(planId).firstOrNull { it.itemType == "calendar_event" }
        val event = eventItem?.refId?.let { db.eventDao().findById(it) }
        val calendarCancellationStatus = when {
            event == null || event.androidCalendarEventId <= 0L -> "saved locally only — no calendar account available"
            calendarProvider?.deleteEvent(event.androidCalendarEventId) == true -> "Calendar Provider event removed"
            else -> "Calendar Provider event could not be removed; check calendar permission"
        }
        plans.cancelPlan(planId, calendarCancellationStatus, lastResolvedModelName)
        val calendarMessage = when {
            event == null || event.androidCalendarEventId <= 0L ->
                " The meeting was saved locally only — no calendar account available."
            calendarCancellationStatus == "Calendar Provider event removed" -> " The Calendar event was removed too."
            else -> " The Calendar event could not be removed; check calendar permission."
        }
        return "Meeting cancelled. The linked departure reminder was cancelled too.$calendarMessage"
    }
}
