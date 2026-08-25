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
        val predictions = engine.getPredictions("", 3)
        assertTrue("Empty text should return empty list", predictions.isEmpty())

        val whitespace = engine.getPredictions("   ", 3)
        assertTrue("Whitespace should return empty list", whitespace.isEmpty())
    }

    @Test
    fun testContextAwarePrefixAutocomplete() {
        // "I h" -> should predict "have" as top suggestion using Bigram context
        val predictionsIH = engine.getPredictions("I h", 3)
        assertTrue("Predictions for 'I h' should not be empty", predictionsIH.isNotEmpty())
        assertEquals("Top prediction for 'I h' should be 'have'", "have", predictionsIH.first())

        // "How are y" -> should predict "you" as top suggestion using Trigram context
        val predictionsHowAre = engine.getPredictions("How are y", 3)
        assertTrue("Predictions for 'How are y' should contain 'you'", predictionsHowAre.contains("you"))
        assertEquals("Top prediction for 'How are y' should be 'you'", "you", predictionsHowAre.first())

        // "thank y" -> should predict "you"
        val predictionsThank = engine.getPredictions("thank y", 3)
        assertEquals("Top prediction for 'thank y' should be 'you'", "you", predictionsThank.first())
    }

    @Test
    fun testCleanStandalonePrefixAutocomplete() {
        // "th" -> should suggest "the", "that", "this"
        val predictionsTh = engine.getPredictions("th", 3)
        assertEquals("Slot 1 for 'th' should be 'the'", "the", predictionsTh[0])
        assertTrue("Predictions for 'th' should contain common words", predictionsTh.contains("that") || predictionsTh.contains("this"))

        // "pl" -> should suggest common words like "please", "place", "plan" (not obscure "plc")
        val predictionsPl = engine.getPredictions("pl", 3)
        assertTrue("Predictions for 'pl' should suggest 'please'", predictionsPl.contains("please"))
        assertTrue("Predictions for 'pl' should not suggest obscure abbreviations like 'plc'", !predictionsPl.contains("plc"))
    }

    @Test
    fun testNextWordPredictionConnectorLimitAndContentBalance() {
        val predictions = engine.getPredictions("How are ", 3)
        assertTrue("Predictions should not be empty", predictions.isNotEmpty())
        assertTrue("Predictions should contain 'you'", predictions.contains("you"))

        val stopConnectors = setOf("the", "a", "an", "and", "or", "but", "to", "in", "of", "by", "for", "on", "at")
        val stopCount = predictions.count { stopConnectors.contains(it.lowercase()) }
        assertTrue("Stop connectors should be at most 1, but got $stopCount", stopCount <= 1)
    }

    @Test
    fun testPrefixAutocompleteWithPersonalOOV() {
        // User typed custom OOV "b4"
        repo.isPersonalizationEnabled = true
        val trieOOV = TrieNode()
        val bNode = trieOOV.getOrPut("b")
        val b4Node = bNode.getOrPut("4")
        b4Node.isEndOfWord = true
        b4Node.frequency = 100
        repo.trieDictOOV = trieOOV

        val predictions = engine.getPredictions("see you b", 3)
        assertTrue("Prefix matching should include personal OOV word 'b4'", predictions.contains("b4"))
        assertEquals("Personal OOV word 'b4' should be top 1", "b4", predictions.first())
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

    @Test
    fun testExactMatchAndPluralHandling() {
        // When user types exact word "thing", it must be #1, not overridden by "things"
        val thingPreds = engine.getPredictions("thing", 3)
        assertTrue("Predictions for 'thing' should not be empty", thingPreds.isNotEmpty())
        assertEquals("Top prediction for 'thing' must be 'thing'", "thing", thingPreds.first())

        // When user types exact word "make", it must be #1, not overridden by "makes"
        val makePreds = engine.getPredictions("make", 3)
        assertEquals("Top prediction for 'make' must be 'make'", "make", makePreds.first())

        // When user types exact word "time", it must be #1
        val timePreds = engine.getPredictions("time", 3)
        assertEquals("Top prediction for 'time' must be 'time'", "time", timePreds.first())

        // When user explicitly types "things" with 's', "things" must be #1
        val thingsPreds = engine.getPredictions("things", 3)
        assertEquals("Top prediction for 'things' must be 'things'", "things", thingsPreds.first())
    }

    @Test
    fun testPluralVsSingularContextGrammar() {
        // "many thin" -> should boost "things"
        val pluralContext = engine.getPredictions("many thin", 3)
        assertTrue("Predictions after 'many thin' should contain 'things'", pluralContext.contains("things"))

        // "a thin" -> should boost "thing"
        val singularContext = engine.getPredictions("a thin", 3)
        assertTrue("Predictions after 'a thin' should contain 'thing'", singularContext.contains("thing"))
    }

    @Test
    fun testGetActivePrefix() {
        // Next-word mode (after space or punctuation): prefix is empty
        assertEquals("", engine.getActivePrefix("Good "))
        assertEquals("", engine.getActivePrefix("How are you "))
        assertEquals("", engine.getActivePrefix("hello."))
        assertEquals("", engine.getActivePrefix("yes!"))
        assertEquals("", engine.getActivePrefix(""))

        // Prefix completion mode (while typing a word): returns the trailing word fragment
        assertEquals("mor", engine.getActivePrefix("Good mor"))
        assertEquals("y", engine.getActivePrefix("How are y"))
        assertEquals("hel", engine.getActivePrefix("hel"))
        assertEquals("feel", engine.getActivePrefix("I'm feel"))
        assertEquals("john@", engine.getActivePrefix("send to john@"))
    }
}
