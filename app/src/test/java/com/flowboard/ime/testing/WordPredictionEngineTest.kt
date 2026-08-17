package com.flowboard.ime.testing

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.TrieNode
import com.flowboard.ime.engine.WordPredictionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WordPredictionEngineTest {

    private val repo = FlowboardRepository
    private lateinit var engine: WordPredictionEngine

    @Before
    fun setUp() {
        TestDataFactory.loadRepo(repo)
        engine = WordPredictionEngine(repo)
    }

    @Test
    fun testEmptyTextReturnsNoPredictions() {
        // Point 1: When nothing is typed, do NOT suggest words!
        val predictions = engine.getPredictions("", 3)
        assertTrue("Empty text should return empty list", predictions.isEmpty())

        val whitespace = engine.getPredictions("   ", 3)
        assertTrue("Whitespace should return empty list", whitespace.isEmpty())
    }

    @Test
    fun testGeneralNextWordPredictionAfterSpace() {
        // "How are " -> should predict next words sorted by word list rank (e.g. "you", "we")
        val predictions = engine.getPredictions("How are ", 3)
        assertTrue("Predictions should not be empty", predictions.isNotEmpty())
        assertTrue("Predictions should contain 'you'", predictions.contains("you"))
    }

    @Test
    fun testGeneralBigramPrediction() {
        // "thank " -> should predict "you"
        val predictions = engine.getPredictions("thank ", 3)
        assertTrue("Predictions should not be empty", predictions.isNotEmpty())
        assertTrue("Predictions should suggest 'you'", predictions.contains("you"))
    }

    @Test
    fun testPrefixAutocompleteRankFormula() {
        // Typing prefix "th" -> should rank common/short words like "the", "that", "this" at top
        val predictions = engine.getPredictions("th", 3)
        assertTrue("Predictions should contain 'the'", predictions.contains("the"))
        assertEquals("Top word for 'th' should be 'the'", "the", predictions.first())
    }

    @Test
    fun testPrefixAutocompleteWithPersonalOOV() {
        // User typed "see you " and then started typing custom OOV "b4"
        repo.isPersonalizationEnabled = true
        val trieOOV = TrieNode()
        val bNode = trieOOV.getOrPut("b")
        val b4Node = bNode.getOrPut("4")
        b4Node.isEndOfWord = true
        b4Node.frequency = 100
        repo.trieDictOOV = trieOOV

        val predictions = engine.getPredictions("see you b", 3)
        assertTrue("Prefix matching should include personal OOV word 'b4'", predictions.contains("b4"))
    }

    @Test
    fun testCasingPropagation() {
        val lower = engine.getPredictions("hel", 3)
        assertTrue("Lower prefix produces lowercase", lower.all { it.all { ch -> ch.isLowerCase() || ch == '\'' } })

        val capitalized = engine.getPredictions("Hel", 3)
        assertTrue("Capitalized prefix produces capitalized word", capitalized.first()[0].isUpperCase())

        val upper = engine.getPredictions("HEL", 3)
        assertTrue("All-caps prefix produces all-caps word", upper.first().all { it.isUpperCase() || it == '\'' })
    }
}
