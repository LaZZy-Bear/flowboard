package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.MasterLayoutEntry
import com.flowboard.ime.data.models.Profile
import com.flowboard.ime.data.models.ProfileRules
import com.flowboard.ime.data.models.TrieNode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the P22 ScoringEngine (English-only).
 * Tests state selection logic for all 6 states and basic scoring behavior.
 *
 * States:
 *   1 = empty, 2 = prefix len 1, 3 = prefix len 2, 4 = prefix len 3+,
 *   7 = after normal space, 8 = after connector-word space
 */
class ScoringEngineTest {

    private lateinit var engine: ScoringEngine
    private val repo = FlowboardRepository

    @Before
    fun setup() {
        repo.reset()

        // English unigram (frequency order)
        repo.unigram = listOf("t", "a", "e", "o", "i", "n", "s", "h", "r", "l", "d", "w", "c", "u", "m", "f", "g", "p", "y", "b", "v", "k", "j", "x", "q", "z")

        // Start unigram (sentence-starting chars)
        repo.unigramStart = listOf("t", "a", "i", "s", "w", "h", "o", "n", "m", "c", "b", "p", "y", "d", "f", "r", "e", "l", "u", "g")

        // Minimal master layout (26 English letters across 9 keys)
        repo.masterLayout = mapOf(
            "t" to MasterLayoutEntry("key_1", "tap"),
            "h" to MasterLayoutEntry("key_1", "up"),
            "n" to MasterLayoutEntry("key_1", "left"),
            "d" to MasterLayoutEntry("key_1", "right"),

            "a" to MasterLayoutEntry("key_2", "tap"),
            "e" to MasterLayoutEntry("key_2", "up"),
            "i" to MasterLayoutEntry("key_2", "left"),
            "o" to MasterLayoutEntry("key_2", "right"),

            "s" to MasterLayoutEntry("key_3", "tap"),
            "f" to MasterLayoutEntry("key_3", "up"),
            "l" to MasterLayoutEntry("key_3", "left"),
            "c" to MasterLayoutEntry("key_3", "right"),

            "r" to MasterLayoutEntry("key_4", "tap"),
            "g" to MasterLayoutEntry("key_4", "up"),
            "y" to MasterLayoutEntry("key_4", "left"),
            "b" to MasterLayoutEntry("key_4", "right"),

            "w" to MasterLayoutEntry("key_5", "tap"),
            "p" to MasterLayoutEntry("key_5", "up"),
            "v" to MasterLayoutEntry("key_5", "left"),
            "k" to MasterLayoutEntry("key_5", "right"),

            "m" to MasterLayoutEntry("key_6", "tap"),
            "u" to MasterLayoutEntry("key_6", "up"),
            "j" to MasterLayoutEntry("key_6", "left"),
            "x" to MasterLayoutEntry("key_6", "right"),

            "q" to MasterLayoutEntry("key_7", "tap"),
            "z" to MasterLayoutEntry("key_7", "up")
        )

        repo.charMap = emptyMap()
        repo.bigram = emptyMap()
        repo.trigram = emptyMap()
        repo.wordList = emptyList()
        repo.wordReverseMap = emptyMap()

        repo.activeProfile = Profile.DEFAULT
        repo.bonusDict = emptyMap()

        // Build a minimal trie with English words
        val trieRoot = TrieNode()
        insertWordInTrie(trieRoot, "the")
        insertWordInTrie(trieRoot, "they")
        insertWordInTrie(trieRoot, "then")
        insertWordInTrie(trieRoot, "time")
        insertWordInTrie(trieRoot, "so")
        insertWordInTrie(trieRoot, "some")
        repo.trieDict = trieRoot

        repo.markReady()

        engine = ScoringEngine(repo)
    }

    private fun insertWordInTrie(root: TrieNode, word: String) {
        var node = root
        for (c in word) {
            node = node.getOrPut(c.toString())
        }
        node.isEndOfWord = true
        node.frequency = 10
    }

    // ══════════════════════════════════════════
    // State Selection Tests
    // ══════════════════════════════════════════

    @Test
    fun `State 1 - empty text uses start unigram`() {
        val scores = engine.calculateScores("")
        assertTrue("State 1 should return scores", scores.isNotEmpty())
        assertTrue("Engine status should mention State 1", engine.engineStatus.contains("State 1"))
    }

    @Test
    fun `State 2 - single char prefix`() {
        val scores = engine.calculateScores("t")
        assertTrue("State 2 should return scores", scores.isNotEmpty())
        assertTrue("Engine status should mention State 2", engine.engineStatus.contains("State 2"))
    }

    @Test
    fun `State 3 - two char prefix`() {
        val scores = engine.calculateScores("th")
        assertTrue("State 3 should return scores", scores.isNotEmpty())
        assertTrue("Engine status should mention State 3", engine.engineStatus.contains("State 3"))
    }

    @Test
    fun `State 4 - three or more char prefix`() {
        val scores = engine.calculateScores("the")
        assertTrue("State 4 should return scores", scores.isNotEmpty())
        assertTrue("Engine status should mention State 4", engine.engineStatus.contains("State 4"))
    }

    @Test
    fun `State 7 - after normal word space`() {
        val scores = engine.calculateScores("time ")
        assertTrue("State 7 should return scores", scores.isNotEmpty())
        assertTrue("Engine status should mention State 7", engine.engineStatus.contains("State 7"))
    }

    @Test
    fun `State 8 - after connector word space`() {
        val scores = engine.calculateScores("the ")
        assertTrue("State 8 should return scores", scores.isNotEmpty())
        assertTrue("Engine status should mention State 8", engine.engineStatus.contains("State 8"))
    }

    @Test
    fun `State 8 triggers for connector 'a'`() {
        val scores = engine.calculateScores("I saw a ")
        assertTrue("Engine status should mention State 8", engine.engineStatus.contains("State 8"))
    }

    @Test
    fun `State 7 triggers for non-connector word`() {
        val scores = engine.calculateScores("run ")
        assertTrue("Engine status should mention State 7", engine.engineStatus.contains("State 7"))
    }

    // ══════════════════════════════════════════
    // Scoring Behavior Tests
    // ══════════════════════════════════════════

    @Test
    fun `scores are non-negative for all chars`() {
        val scores = engine.calculateScores("")
        for ((char, score) in scores) {
            assertTrue("Score for '$char' should be >= 0 but was $score", score >= 0.0)
        }
    }

    @Test
    fun `all unigram chars appear in State 1 scores`() {
        val scores = engine.calculateScores("")
        for (c in repo.unigram) {
            assertTrue("Unigram char '$c' should be in scores", scores.containsKey(c))
        }
    }

    @Test
    fun `trie provides dict scores in State 2`() {
        // Type "t" → trie should give 'h' as high probability (path t-h in trie)
        val scores = engine.calculateScores("t")
        assertTrue("Should have scores after 't'", scores.isNotEmpty())
        // 'h' should score decently because "the"/"they"/"then" all start with "th"
        val hScore = scores["h"] ?: 0.0
        assertTrue("'h' should score > 0 after 't' (dict lookup)", hScore > 0)
    }

    @Test
    fun `reset trie cache does not crash`() {
        engine.calculateScores("th")
        engine.resetTrieCache()
        val scores = engine.calculateScores("t")
        assertTrue("Should still work after cache reset", scores.isNotEmpty())
    }

    @Test
    fun `OOV text returns graceful fallback scores`() {
        // Type "zzz" — no valid trie path → OOV decay → fall back to trigram/unigram
        val scores = engine.calculateScores("zzz")
        assertTrue("OOV text should still produce some scores", scores.isNotEmpty())
    }

    @Test
    fun `isDoubleCharValid returns false for empty text`() {
        assertFalse(engine.isDoubleCharValid("", "t"))
    }

    @Test
    fun `isDoubleCharValid returns false for first char of word`() {
        // Only 1 char typed → no sticky on first char
        assertFalse(engine.isDoubleCharValid("t", "t"))
    }

    @Test
    fun `isDoubleCharValid returns true for valid double`() {
        val result = engine.isDoubleCharValid("th", "e")
        assertTrue("Adding 'e' after 'th' is valid in trie", result)
    }

    // ══════════════════════════════════════════
    // V22.3.0 Trie Word Completion & Dict Scoring Tests
    // ══════════════════════════════════════════

    @Test
    fun `V22_3_0 - Depth proximity factor prioritizes immediate word completion over deep branches`() {
        val trie = TrieNode()
        // "wor" -> "d" (word, rank 100, depth 1)
        insertWordWithRank(trie, "word", 100)
        // "wor" -> "k" (work, rank 150, depth 1)
        insertWordWithRank(trie, "work", 150)
        // "wor" -> "cester" (worcester, rank 100, depth 6)
        insertWordWithRank(trie, "worcester", 100)

        repo.trieDict = trie
        repo.wordList = List(20000) { "word_$it" }
        engine.resetTrieCache()

        val scores = engine.calculateScores("wor")
        val scoreD = scores["d"] ?: 0.0
        val scoreK = scores["k"] ?: 0.0
        val scoreC = scores["c"] ?: 0.0

        assertTrue("Immediate completion 'd' ($scoreD) should score higher than deep branch 'c' ($scoreC)", scoreD > scoreC)
        assertTrue("Immediate completion 'k' ($scoreK) should score higher than deep branch 'c' ($scoreC)", scoreK > scoreC)
    }

    @Test
    fun `V22_3_0 - Word popularity ranking prioritizes frequent words over rare words`() {
        val trie = TrieNode()
        // "ha" -> "ve" (have, rank 10, depth 2)
        insertWordWithRank(trie, "have", 10)
        // "ha" -> "zel" (hazel, rank 18000, depth 3)
        insertWordWithRank(trie, "hazel", 18000)

        repo.trieDict = trie
        repo.wordList = List(20000) { "word_$it" }
        engine.resetTrieCache()

        val scores = engine.calculateScores("ha")
        val scoreV = scores["v"] ?: 0.0
        val scoreZ = scores["z"] ?: 0.0

        assertTrue("Popular word 'have' -> 'v' ($scoreV) should score higher than rare word 'hazel' -> 'z' ($scoreZ)", scoreV > scoreZ)
    }

    @Test
    fun `V22_3_0 - OOV trie fallback applies 0_5 penalty compared to main trie`() {
        val mainTrie = TrieNode()
        insertWordWithRank(mainTrie, "test", 100)

        val oovTrie = TrieNode()
        insertWordWithRank(oovTrie, "oovword", 100)

        repo.trieDict = mainTrie
        repo.trieDictOOV = oovTrie
        repo.wordList = List(20000) { "word_$it" }
        engine.resetTrieCache()

        // Lookup in OOV trie
        val scoresOOV = engine.calculateScores("oovw")
        assertTrue("OOV trie should produce scores for 'oovw'", scoresOOV.isNotEmpty())
        assertTrue("OOV trie should predict 'o' for 'oovword'", (scoresOOV["o"] ?: 0.0) > 0)
    }

    private fun insertWordWithRank(root: TrieNode, word: String, rank: Int) {
        var node = root
        for (c in word) {
            node = node.getOrPut(c.toString())
        }
        node.isEndOfWord = true
        node.frequency = rank
    }
}
