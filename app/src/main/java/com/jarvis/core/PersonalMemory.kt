package com.jarvis.core

import com.jarvis.data.Memory
import com.jarvis.data.MemoryDao
import java.util.UUID

data class ClassifiedMemory(val type: String, val key: String, val value: String)

/** Turns "remember …" text into a typed memory. Deterministic and local; nothing is sent anywhere. */
object MemoryClassifier {
    private val NAME = Regex("""^(?:my\s+name\s+is|call\s+me|i\s+am\s+called)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val SIGN_OFF = Regex(
        """^(?:(?:sign|end)\s+(?:off\s+)?my\s+e-?mails?\s+(?:with|as)|my\s+(?:e-?mail\s+)?sign-?off\s+is)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val FAVORITE = Regex("""^my\s+favou?rite\s+([a-z ]{2,30}?)\s+is\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val RELATIONSHIP = Regex("""^(.{2,60}?)\s+is\s+my\s+(.{2,60})$""", RegexOption.IGNORE_CASE)
    private val PREFERENCE = Regex("""^i\s+(?:prefer|like|love|usually|always|never)\s+(.+)$""", RegexOption.IGNORE_CASE)

    fun classify(raw: String): ClassifiedMemory? {
        val text = raw.trim().trimEnd('.', '!').removePrefix("that ").trim()
        if (text.length < 3) return null
        NAME.matchEntire(text)?.let { return ClassifiedMemory("preference", PersonalFactsStore.USER_NAME, clean(it.groupValues[1])) }
        SIGN_OFF.matchEntire(text)?.let { return ClassifiedMemory("preference", PersonalFactsStore.EMAIL_SIGN_OFF, clean(it.groupValues[1])) }
        FAVORITE.matchEntire(text)?.let {
            val what = it.groupValues[1].trim().lowercase().replace(Regex("\\s+"), "_")
            return ClassifiedMemory("favorite", "favorite_$what", clean(it.groupValues[2]))
        }
        RELATIONSHIP.matchEntire(text)?.let {
            val who = it.groupValues[1].trim()
            if (!who.equals("this", true) && !who.equals("it", true) && !who.equals("that", true)) {
                return ClassifiedMemory("relationship", who, text)
            }
        }
        if (PREFERENCE.matches(text)) return ClassifiedMemory("preference", "", text)
        return ClassifiedMemory("fact", "", text)
    }

    private fun clean(value: String) = value.trim().trim('"', '“', '”', '\'').take(MemoryRepository.MAX_VALUE)
}

/**
 * Lexical relevance: word overlap between the request and each memory (key + value), with a boost when the
 * memory's key (a person's name or a favorite) is mentioned. Chosen over embeddings because the store holds
 * tens of short user-written entries; see DECISION-LOG for the trade-off.
 */
object MemoryRetriever {
    private val STOP = setOf(
        "a", "an", "the", "is", "are", "am", "to", "of", "and", "or", "my", "me", "i", "you", "your", "for", "in", "on",
        "at", "with", "it", "this", "that", "be", "do", "does", "please", "can", "could", "would", "will", "what", "about",
        "tell", "set", "make", "remind", "from", "today", "tomorrow"
    )

    /** Common paraphrases map to one word, so "my mentor" finds "Dr Rao is my guide". */
    private val SYNONYMS = mapOf(
        "guide" to "mentor", "advisor" to "mentor", "adviser" to "mentor", "supervisor" to "mentor",
        "mother" to "mom", "mum" to "mom", "maa" to "mom", "mummy" to "mom", "ammi" to "mom",
        "father" to "dad", "papa" to "dad", "abba" to "dad", "abbu" to "dad",
        "bday" to "birthday", "born" to "birthday", "university" to "college", "campus" to "college",
        "favourite" to "favorite", "fav" to "favorite", "track" to "song", "music" to "song",
        "office" to "work", "job" to "work", "workplace" to "work", "wife" to "spouse", "husband" to "spouse"
    )

    fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOP }.map(::stem)
            .map { SYNONYMS[it] ?: it }.toSet()

    private fun stem(word: String) = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    fun relevant(utterance: String, memories: List<Memory>, k: Int = 3): List<Memory> {
        val query = tokens(utterance)
        if (query.isEmpty()) return emptyList()
        // A secret saved by an older version is never retrieved, listed to a model, or sent anywhere.
        return memories.filterNot { SensitiveData.isSecret(it.contentText) }.map { memory ->
            val keyTokens = tokens(memory.memoryKey.replace('_', ' '))
            val valueTokens = tokens(memory.contentText)
            val keyHits = keyTokens.count { it in query && it != "favorite" }
            val valueHits = valueTokens.count { it in query }
            memory to (keyHits * 2 + valueHits)
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }.thenByDescending { it.first.updatedAt })
            .take(k)
            .map { it.first }
    }

    /** The result of looking something up in memory. */
    sealed interface Lookup {
        data class Found(val memories: List<Memory>) : Lookup
        /** Saved memories disagree (e.g. two values for one key): ask which is right instead of picking one. */
        data class Conflict(val memories: List<Memory>) : Lookup
        data object Nothing : Lookup
    }

    // BASELINE (behaviour as of 73daebe): the top 3 by word overlap; replaced in the next commit.
    fun lookup(utterance: String, memories: List<Memory>): Lookup =
        relevant(utterance, memories, k = 3).let { if (it.isEmpty()) Lookup.Nothing else Lookup.Found(it) }

    /** What a cloud model sees: the memory text only, never ids or sources. */
    fun promptLines(memories: List<Memory>): List<String> = memories.map { PromptPrivacy.mask(it.contentText.take(200)) }
}

/** Room-backed personal memory; also the store behind favorite song, user name, and email sign-off. */
class MemoryRepository(private val dao: MemoryDao, private val clock: () -> Long = System::currentTimeMillis) : PersonalFactsStore {
    companion object {
        const val MAX_VALUE = 300
        private val KEY_TYPES = mapOf(
            PersonalFactsStore.FAVORITE_SONG to "favorite",
            PersonalFactsStore.USER_NAME to "preference",
            PersonalFactsStore.EMAIL_SIGN_OFF to "preference"
        )
    }

    suspend fun all(): List<Memory> = dao.all()

    suspend fun remember(classified: ClassifiedMemory, source: String): Memory {
        val now = clock()
        val existing = classified.key.takeIf(String::isNotBlank)?.let { dao.findByKey(classified.type, it) }
        // Callers refuse secrets first; this keeps a secret value out of storage even if one slips through.
        val value = SafeStorage.text(classified.value)
        val memory = existing?.copy(contentText = value, updatedAt = now, sourceRef = source)
            ?: Memory(UUID.randomUUID().toString(), value.take(MAX_VALUE), null, source, now, null, true,
                classified.type, classified.key, now)
        dao.upsert(memory)
        return memory
    }

    suspend fun edit(id: String, value: String): Boolean {
        val memory = dao.all().firstOrNull { it.id == id } ?: return false
        val trimmed = SafeStorage.text(value.trim()).take(MAX_VALUE)
        if (trimmed.isBlank()) return false
        dao.upsert(memory.copy(contentText = trimmed, updatedAt = clock(), sourceRef = "edited by user"))
        return true
    }

    suspend fun delete(id: String) = dao.delete(id)
    suspend fun clearAll() = dao.deleteAll()

    /** Deletes the single best match; returns it, or null when nothing (or several equally) matched. */
    suspend fun forget(query: String): Memory? {
        val all = dao.all()
        val exact = all.filter { it.memoryKey.equals(query.trim(), true) || it.contentText.equals(query.trim(), true) }
        val match = exact.singleOrNull() ?: MemoryRetriever.relevant(query, all, k = 2).let { top ->
            if (top.size == 1) top.single() else null
        } ?: return null
        dao.delete(match.id)
        return match
    }

    suspend fun relevantFor(utterance: String, k: Int = 3): List<Memory> {
        val chosen = MemoryRetriever.relevant(utterance, dao.all(), k)
        if (chosen.isNotEmpty()) dao.markUsed(chosen.map { it.id }, clock())
        return chosen
    }

    override suspend fun get(key: String): String? =
        dao.findByKey(KEY_TYPES[key] ?: "fact", key)?.contentText?.takeIf(String::isNotBlank)

    override suspend fun set(key: String, value: String) {
        remember(ClassifiedMemory(KEY_TYPES[key] ?: "fact", key, value), "conversation")
    }

    override suspend fun remove(key: String) {
        dao.findByKey(KEY_TYPES[key] ?: "fact", key)?.let { dao.delete(it.id) }
    }
}

object MemoryReplies {
    /** Favorites and preferences store a bare value ("Kesariya"), so their key is shown as a label. */
    fun describe(memory: Memory): String =
        if (memory.type in setOf("favorite", "preference") && memory.memoryKey.isNotBlank()) {
            "${memory.memoryKey.replace('_', ' ')}: ${memory.contentText}"
        } else {
            memory.contentText
        }

    fun remembered(memory: Memory): String = when (memory.memoryKey) {
        PersonalFactsStore.USER_NAME -> "I'll remember your name is ${memory.contentText}. It's stored only on this phone (Memory tab)."
        PersonalFactsStore.EMAIL_SIGN_OFF -> "I'll sign drafted emails with \"${memory.contentText}\"."
        else -> "Remembered: \"${memory.contentText}\". You can edit or delete it in the Memory tab."
    }

    fun list(memories: List<Memory>, query: String?): String {
        if (memories.isEmpty()) return if (query.isNullOrBlank()) "I haven't saved any memories yet. Say \"remember that …\"." else "I don't have anything saved about $query."
        val header = if (query.isNullOrBlank()) "Here's what I remember:" else "About $query I remember:"
        return header + "\n" + memories.take(10).joinToString("\n") { "• ${describe(it)}" } +
            if (memories.size > 10) "\n…and ${memories.size - 10} more in the Memory tab." else ""
    }
}
