package com.jarvis.core

import com.jarvis.data.Goal
import com.jarvis.data.Memory
import com.jarvis.data.MemoryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 8A: relevance, conflicts, edits/deletions, legacy rows, secrets, and isolation of unrelated context. */
class ContextualMemoryTest {
    private class Dao : MemoryDao {
        val rows = MutableStateFlow<List<Memory>>(emptyList())
        override fun observeAll(): Flow<List<Memory>> = rows
        override suspend fun all() = rows.value.sortedByDescending { it.updatedAt }
        override suspend fun findByKey(type: String, key: String) = rows.value.firstOrNull { it.type == type && it.memoryKey == key }
        override suspend fun upsert(memory: Memory) { rows.value = rows.value.filterNot { it.id == memory.id } + memory }
        override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
        override suspend fun deleteAll() { rows.value = emptyList() }
        override suspend fun markUsed(ids: List<String>, usedAt: Long) { rows.value = rows.value.map { if (it.id in ids) it.copy(lastUsedAt = usedAt) else it } }
    }

    private fun m(id: String, text: String, type: String = "fact", key: String = "", at: Long = 1) = Memory(id, text, null, "test", at, null, true, type, key, at)

    @Test
    fun aRareSharedWordOutweighsACommonOneAndWeakMatchesAreDropped() {
        val memories = listOf(m("a", "My sister lives in Pune"), m("b", "Zara is my sister", "relationship", "Zara"), m("c", "My gym is in Pune too"))
        val found = MemoryRetriever.lookup("who is my sister", memories) as MemoryRetriever.Lookup.Found
        assertEquals("a 'who' question prefers the person", "b", found.memories.first().id)
        assertFalse("'Pune' alone doesn't pull in the gym", found.memories.any { it.id == "c" })
        assertEquals(MemoryRetriever.Lookup.Nothing, MemoryRetriever.lookup("book a cab to the airport", memories))
    }

    @Test
    fun conflictingValuesForOneKeyAskInsteadOfPicking() {
        val memories = listOf(m("new", "Biryani", "favorite", "favorite_food", 10), m("old", "Pasta", "favorite", "favorite_food", 5))
        val answer = MemoryRetriever.lookup("what's my favourite food", memories)
        assertTrue(answer is MemoryRetriever.Lookup.Conflict)
        val reply = MemoryReplies.conflict((answer as MemoryRetriever.Lookup.Conflict).memories)
        assertTrue(reply.contains("Biryani") && reply.contains("Pasta") && reply.contains("Which one is right?"))
    }

    @Test
    fun editsAndDeletionsChangeTheNextRetrievalAndLegacyRowsStillWork() = runBlocking {
        val dao = Dao()
        val repo = MemoryRepository(dao)
        dao.upsert(m("legacy", "Prof. Mehta teaches compilers", "fact", "", 0)) // migrated row: no key, updatedAt 0
        dao.upsert(m("gym", "My gym is near Dadar station"))
        assertEquals(listOf("legacy"), repo.relevantFor("who teaches compilers").map { it.id })
        assertTrue(repo.edit("gym", "My gym is near Bandra station"))
        assertEquals(emptyList<String>(), repo.relevantFor("is my gym near Dadar").filter { "Dadar" in it.contentText }.map { it.id })
        assertTrue(repo.relevantFor("where is my gym").single().contentText.contains("Bandra"))
        dao.delete("gym")
        assertTrue(repo.relevantFor("where is my gym").isEmpty())
    }

    @Test
    fun secretsAreNeverCandidatesAndAreNotStoredByEdits() = runBlocking {
        val dao = Dao()
        val repo = MemoryRepository(dao)
        dao.upsert(m("s", "wifi password is coffee123")) // stored by an old version
        dao.upsert(m("w", "The wifi is called HomeNet"))
        assertFalse(repo.relevantFor("what is my wifi password").any { it.id == "s" })
        assertTrue(repo.edit("w", "The wifi password is hunter2x9"))
        assertFalse(dao.all().any { "hunter2x9" in it.contentText })
    }

    @Test
    fun onlyARelatedActiveGoalIsAddedToCloudContext() {
        val goals = listOf(
            Goal("g1", "Prepare for exam", "active", null, null, 1, 1),
            Goal("g2", "Prepare for driving test", "paused", null, null, 1, 1),
            Goal("g3", "Prepare for marathon", "active", null, null, 1, 1)
        )
        assertEquals(listOf("Active plan: Prepare for exam"), ContextAssembler.goalLines("how many days until my exam", goals))
        assertTrue(ContextAssembler.goalLines("what's the weather", goals).isEmpty())
        assertTrue("a paused goal is not context", ContextAssembler.goalLines("when is my driving test", goals).isEmpty())
        assertTrue("'plan' alone matches nothing", ContextAssembler.goalLines("show my plan", goals).isEmpty())
    }
}
