package com.jarvis.core

import android.content.Context

/** Where JarvisCore sends a validated tool call. Pure so routing and risk gating are unit-tested. */
sealed interface ToolRoute {
    data class Reject(val reason: String) : ToolRoute
    data object ConfirmCancel : ToolRoute
    data class DraftCommunication(val recipient: String, val body: String) : ToolRoute
    data class AlarmHandoff(val hour: String, val minute: String, val label: String) : ToolRoute
    data class PhoneTool(val step: PlanStep) : ToolRoute
    data class FavoriteSong(val step: PlanStep) : ToolRoute
    data class MeetingCreate(val step: PlanStep) : ToolRoute
    data class Agent(val step: PlanStep) : ToolRoute
    data class Messages(val step: PlanStep) : ToolRoute
}

object ToolRouter {
    val PHONE_TOOL_NAMES = setOf(
        "set_timer", "play_youtube", "play_music", "media_control",
        "set_volume", "flashlight", "call_contact", "navigate"
    )
    val FAVORITE_SONG_TOOLS = setOf(
        "set_favorite_song", "play_favorite_song", "get_favorite_song", "clear_favorite_song"
    )

    /** Medium/high-risk tools that have a confirmation UI; any other gated tool is refused. */
    private val CONFIRMATION_FLOWS = setOf("calendar.cancel", "communication.draft", "call_contact", "summarize_new_messages")
    val MESSAGE_TOOLS = setOf("count_new_messages", "list_new_messages", "summarize_new_messages")

    /**
     * Recognised phone commands are answered by the local parser and never sent to a cloud
     * provider; everything else goes through the provider chain.
     */
    fun usesLocalPhoneParser(utterance: String): Boolean =
        PhoneCommandParser.parse(utterance) != PhoneCommandParseResult.NotPhoneCommand

    fun route(rawStep: PlanStep, availableTools: Collection<String>): ToolRoute {
        val step = ReasoningToolSchema.normalize(rawStep)
        if (step.toolName !in availableTools) return ToolRoute.Reject("Tool is not available.")
        ReasoningToolSchema.validate(step)?.let { return ToolRoute.Reject(it) }
        if (ToolRiskGate.requiresConfirmation(step.toolName) && step.toolName !in CONFIRMATION_FLOWS) {
            return ToolRoute.Reject("Confirmation flow is unavailable.")
        }
        val values = step.parameters
        return when (step.toolName) {
            "calendar.cancel" -> ToolRoute.ConfirmCancel
            "communication.draft" -> ToolRoute.DraftCommunication(values.getValue("recipient"), values.getValue("body"))
            "set_alarm" -> ToolRoute.AlarmHandoff(values.getValue("hour"), values.getValue("minute"), values.getValue("label"))
            in PHONE_TOOL_NAMES -> ToolRoute.PhoneTool(step)
            in FAVORITE_SONG_TOOLS -> ToolRoute.FavoriteSong(step)
            in MESSAGE_TOOLS -> ToolRoute.Messages(step)
            "meeting.create" -> ToolRoute.MeetingCreate(step)
            else -> ToolRoute.Agent(step)
        }
    }
}

/** Simple persistent key-value personal facts (M2). M5 moves these into the memory store. */
interface PersonalFactsStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)

    companion object {
        const val FAVORITE_SONG = "favorite_song"
    }
}

class SharedPreferencesPersonalFactsStore(context: Context) : PersonalFactsStore {
    private val preferences = context.getSharedPreferences("personal_facts", Context.MODE_PRIVATE)
    override fun get(key: String): String? = preferences.getString(key, null)?.takeIf(String::isNotBlank)
    override fun set(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}

class InMemoryPersonalFactsStore : PersonalFactsStore {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun set(key: String, value: String) {
        values[key] = value
    }
    override fun remove(key: String) {
        values.remove(key)
    }
}

/** Outcome of a favorite-song tool: a reply, or a music search to hand off. */
sealed interface FavoriteSongOutcome {
    data class Reply(val message: String) : FavoriteSongOutcome
    data class PlayMusic(val step: PlanStep) : FavoriteSongOutcome
}

object FavoriteSongTools {
    const val MAX_LENGTH = 200

    fun handle(step: PlanStep, store: PersonalFactsStore): FavoriteSongOutcome {
        val key = PersonalFactsStore.FAVORITE_SONG
        return when (step.toolName) {
            "set_favorite_song" -> {
                val song = step.parameters["song"].orEmpty().trim()
                if (song.isBlank() || song.length > MAX_LENGTH) {
                    FavoriteSongOutcome.Reply("Please tell me the song name in under $MAX_LENGTH characters.")
                } else {
                    store.set(key, song)
                    FavoriteSongOutcome.Reply("Saved “$song” as your favorite song on this device.")
                }
            }
            "play_favorite_song" -> store.get(key)
                ?.let { FavoriteSongOutcome.PlayMusic(PlanStep("play_music", mapOf("query" to it))) }
                ?: FavoriteSongOutcome.Reply(NO_FAVORITE)
            "get_favorite_song" -> FavoriteSongOutcome.Reply(
                store.get(key)?.let { "Your saved favorite song is “$it”." } ?: NO_FAVORITE
            )
            "clear_favorite_song" -> {
                store.remove(key)
                FavoriteSongOutcome.Reply("I forgot your favorite song.")
            }
            else -> FavoriteSongOutcome.Reply("That preference tool is not available.")
        }
    }

    private const val NO_FAVORITE =
        "I don't know your favorite song yet. Say “my favorite song is …” to save it on this device."
}
