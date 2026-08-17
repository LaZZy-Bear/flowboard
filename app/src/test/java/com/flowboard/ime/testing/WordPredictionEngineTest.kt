package com.flowboard.ime.testing

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.PersonalProfile
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
    fun testDefaultStartersAtBeginning() {
        val predictions = engine.getPredictions("", 3)
        assertEquals(listOf("I", "The", "You"), predictions)
    }

    @Test
    fun testGeneralNextWordPredictionAfterSpace() {
        // "How are " -> should predict "you" via Trigram / Bigram
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
    fun testPersonalizedBigramNextWord() {
        // Inject personal bigram: "meet" -> "dinner" (count = 10)
        repo.isPersonalizationEnabled = true
        repo.personalizationPairsEnabled = true
        repo.personalProfile = PersonalProfile(
            trigram = emptyMap(),
            bigram = mapOf("meet" to mapOf("dinner" to 10)),
            wordFreq = emptyMap(),
            learnedOOV = emptyList()
        )

        val predictions = engine.getPredictions("meet ", 3)
        assertTrue("Predictions should suggest personalized word 'dinner'", predictions.contains("dinner"))
        assertEquals("Personalized word 'dinner' should be ranked top 1", "dinner", predictions.first())
    }

    @Test
    fun testPersonalizedTrigramNextWord() {
        // Inject personal trigram: "see_you" -> "b4" (count = 15)
        repo.isPersonalizationEnabled = true
        repo.personalizationPairsEnabled = true
        repo.personalProfile = PersonalProfile(
            trigram = mapOf("see_you" to mapOf("b4" to 15)),
            bigram = emptyMap(),
            wordFreq = emptyMap(),
            learnedOOV = listOf("b4")
        )

        val predictions = engine.getPredictions("see you ", 3)
        assertTrue("Predictions should suggest personalized trigram word 'b4'", predictions.contains("b4"))
        assertEquals("Personalized trigram word 'b4' should be top 1", "b4", predictions.first())
    }

    @Test
    fun testPrefixAutocompleteWithPersonalOOV() {
        // User is typing prefix "b" after "see you "
        repo.isPersonalizationEnabled = true
        repo.personalizationPairsEnabled = true
        repo.personalProfile = PersonalProfile(
            trigram = mapOf("see_you" to mapOf("b4" to 15)),
            bigram = emptyMap(),
            wordFreq = emptyMap(),
            learnedOOV = listOf("b4")
        )

        val predictions = engine.getPredictions("see you b", 3)
        assertTrue("Prefix matching should include personal word 'b4'", predictions.contains("b4"))
        assertEquals("Personal trigram matching prefix should be top 1", "b4", predictions.first())
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
    fun testPersonalizationDisabledToggle() {
        repo.personalProfile = PersonalProfile(
            trigram = mapOf("meet_at" to mapOf("starbucks" to 50)),
            bigram = mapOf("meet" to mapOf("starbucks" to 50)),
            wordFreq = emptyMap(),
            learnedOOV = listOf("starbucks")
        )

        // With personalization disabled
        repo.isPersonalizationEnabled = false
        val predictions = engine.getPredictions("meet ", 3)
        assertTrue("When personalization is disabled, personal words should not be injected", !predictions.contains("starbucks"))
    }
}
