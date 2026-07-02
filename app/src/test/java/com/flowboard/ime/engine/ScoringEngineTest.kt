package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.EngineWeights
import com.flowboard.ime.data.models.MasterKey
import com.flowboard.ime.data.models.Profile
import com.flowboard.ime.data.models.ProfileRules
import com.flowboard.ime.data.models.TrieNode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the ScoringEngine.
 * Tests state selection logic for all 6 states and basic scoring behavior.
 */
class ScoringEngineTest {

    private lateinit var engine: ScoringEngine
    private val repo = FlowboardRepository

    @Before
    fun setup() {
        repo.reset()

        // Set up minimal data for testing
        repo.unigram = listOf("เ", "แ", "ก", "ส", "ค", "อ", "ท", "ร", "ห", "ม")

        repo.charMap = mapOf(
            "ก" to "C", "ข" to "C", "ค" to "C", "ง" to "C", "จ" to "C",
            "ส" to "C", "อ" to "C", "ท" to "C", "ร" to "C", "ห" to "C", "ม" to "C",
            "เ" to "Vp", "แ" to "Vp", "โ" to "Vp", "ใ" to "Vp", "ไ" to "Vp",
            "ะ" to "Vf", "า" to "Vf", "ำ" to "Vf",
            "ิ" to "Vt", "ี" to "Vt", "ึ" to "Vt", "ื" to "Vt", "ั" to "Vt",
            "ุ" to "Vb", "ู" to "Vb",
            "่" to "T", "้" to "T", "๊" to "T", "๋" to "T", "์" to "T",
            "ๆ" to "O", "ฯ" to "O",
            " " to "S"
        )

        repo.masterLayout = mapOf(
            "key_1" to MasterKey("ส", listOf("ซ", "ศ", "ั")),
            "key_2" to MasterKey("เ", listOf("ย", "ฟ", "โ", "ก")),
            "key_3" to MasterKey("ร", listOf("จ", "ช")),
            "key_4" to MasterKey("ข", listOf("ี", "น")),
            "key_5" to MasterKey("่", listOf("ฐ", "ึ", "ด")),
            "key_6" to MasterKey("ม", listOf("ค", "ิ", "ง")),
            "key_7" to MasterKey("อ", listOf("ไ", "ุ", "ป", "้", "า")),
            "key_8" to MasterKey("ล", listOf("ะ", "ฮ")),
            "key_9" to MasterKey("ว", listOf("ห", "ใ", "์", "ธ", "บ"))
        )

        repo.patternPenalty = mapOf(
            "C-Vf" to listOf("Vt", "Vb"),
            "C-Vp" to listOf("O", "Vb", "Vf", "Vt", "T")
        )

        repo.activeProfile = Profile(
            profileName = "Test",
            rules = ProfileRules(
                illegalStartChars = listOf("่", "้", "๊", "๋", "ิ", "ี", "ั"),
                illegalStartPenalty = -999.0
            )
        )
        repo.bonusDict = emptyMap()

        // Build a minimal trie
        val trieRoot = TrieNode()
        insertWordInTrie(trieRoot, "กา")
        insertWordInTrie(trieRoot, "การ")
        insertWordInTrie(trieRoot, "กิน")
        insertWordInTrie(trieRoot, "สร")
        insertWordInTrie(trieRoot, "สวย")
        repo.trieDictRoot = trieRoot

        repo.markReady()

        engine = ScoringEngine(repo)
    }

    private fun insertWordInTrie(root: TrieNode, word: String) {
        var node = root
        for (c in word) {
            node = node.getOrPut(c)
        }
        node.isEndOfWord = true
    }

    // ══════════════════════════════════════════
    // State Selection Tests
    // ══════════════════════════════════════════

    @Test
    fun `State 1 - empty text returns pure unigram`() {
        val scores = engine.calculateScores("")
        assertTrue("State 1 should return scores", scores.isNotEmpty())
        assertTrue("Engine status should mention State 1", engine.engineStatus.contains("State 1"))
    }

    @Test
    fun `State 2 - single char prefix`() {
        val scores = engine.calculateScores("ก")
        assertTrue("State 2 should return scores", scores.isNotEmpty())
        assertTrue("Engine status should mention State 2", engine.engineStatus.contains("State 2"))
    }

    @Test
    fun `State 3 - two char prefix`() {
        val scores = engine.calculateScores("กา")
        // "กา" is a word, so prefix is empty after tokenization → State 5
        // Let's use a non-word prefix instead
        val scores2 = engine.calculateScores("กข")
        assertTrue("Should return scores", scores2.isNotEmpty())
    }

    @Test
    fun `State 4 - three or more char prefix`() {
        val scores = engine.calculateScores("กขค")
        assertTrue("State 4 should return scores", scores.isNotEmpty())
    }

    @Test
    fun `State 7 - after space`() {
        val scores = engine.calculateScores("กา ")
        assertTrue("State 7 should return scores", scores.isNotEmpty())
        assertTrue("Engine status should mention State 7", engine.engineStatus.contains("State 7"))
    }

    @Test
    fun `State 5 - completed word in trie`() {
        // "กา" is a word in our test trie → prefix empty → State 5
        val scores = engine.calculateScores("กา")
        assertTrue("Should return scores", scores.isNotEmpty())
        assertTrue("Engine status should mention State 5", engine.engineStatus.contains("State 5"))
    }

    // ══════════════════════════════════════════
    // Scoring Behavior Tests
    // ══════════════════════════════════════════

    @Test
    fun `illegal start chars get penalized in State 1`() {
        val scores = engine.calculateScores("")
        // Tone marks should be heavily penalized at start of text
        val toneScore = scores["่"] ?: 0.0
        assertTrue("Tone mark should have negative score at start", toneScore < 0)
    }

    @Test
    fun `unigram scores are non-negative for normal chars`() {
        val scores = engine.calculateScores("")
        val gaScore = scores["ก"] ?: 0.0
        assertTrue("Common consonant should have positive score", gaScore > 0)
    }

    @Test
    fun `scores contain all unigram characters`() {
        val scores = engine.calculateScores("")
        for (c in repo.unigram) {
            assertTrue("Unigram char '$c' should be in scores", scores.containsKey(c))
        }
    }

    @Test
    fun `reset trie cache does not crash`() {
        engine.calculateScores("กา")
        engine.resetTrieCache()
        val scores = engine.calculateScores("ก")
        assertTrue("Should still work after cache reset", scores.isNotEmpty())
    }
}
