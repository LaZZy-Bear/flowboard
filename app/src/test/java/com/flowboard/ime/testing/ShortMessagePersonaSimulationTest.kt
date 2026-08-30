package com.flowboard.ime.testing

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.engine.LayoutManager
import com.flowboard.ime.engine.LiveLearningManager
import com.flowboard.ime.engine.ScoringEngine
import com.flowboard.ime.engine.WordPredictionEngine
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persona 3: Rapid Short-Message / Fast-Replier Simulation Benchmark
 *
 * Evaluates:
 * - 1 to 3-word ultra-short messages, abbreviations, and rapid replies
 * - First-character immediate boosting for ultra-short slang & OOV ("omw", "brb", "idk", "tbh", "wfh", "l8r")
 * - Next-word single-tap prediction completion for common 2-word pairs ("got it", "all good", "on my way")
 * - Rapid memory cycling and decay behavior on short text
 */
class ShortMessagePersonaSimulationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val repo = FlowboardRepository
    private lateinit var scoringEngine: ScoringEngine
    private lateinit var layoutManager: LayoutManager
    private lateinit var predictionEngine: WordPredictionEngine

    // Persona 3: Rapid Short Replies (1-3 words)
    private val ultraShortReplies = listOf(
        "ok",
        "k",
        "np",
        "thx",
        "ty",
        "lol",
        "yep",
        "nope",
        "sure",
        "done",
        "cya",
        "tldr",
        "pls"
    )

    private val shortConversationalPairs = listOf(
        "got it",
        "on my way",
        "all good",
        "see ya",
        "sounds good",
        "no problem",
        "call me",
        "let me know",
        "im free",
        "send it",
        "wait for me",
        "where are you",
        "what time",
        "see you soon"
    )

    private val shortSlangAndAlphanumericOOV = listOf(
        "omw right now",
        "brb in five",
        "idk what happened",
        "tbh not sure",
        "wfh today",
        "afk for lunch",
        "see you l8r",
        "talk 2moro"
    )

    @Before
    fun setup() {
        TestDataFactory.loadRepo(repo)
        repo.isPersonalizationEnabled = false
        repo.personalProfile = com.flowboard.ime.data.models.PersonalProfile.EMPTY
        repo.personalizationPairsEnabled = true
        repo.personalizationFreqEnabled = true
        repo.personalizationAlphanumericEnabled = true
        repo.personalizationBoostMultiplier = 1.0
        repo.personalizationOOVMultiplier = 1.3
        repo.personalizationFirstTypeBonus = 30.0
        repo.personalizationUncertaintyGap = 15.0

        scoringEngine = ScoringEngine(repo)
        layoutManager = LayoutManager(repo)
        predictionEngine = WordPredictionEngine(repo)
    }

    @Test
    fun testShortMessageFastReplierPersonaBenchmark() {
        val filesDir = tempFolder.newFolder("short_persona_files")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)
        val bot = BotTester(repo, scoringEngine, layoutManager)

        println("================================================================================")
        println("⚡ STARTING SHORT-MESSAGE FAST REPLIER PERSONA BENCHMARK (3,000+ Quick Chats)")
        println("================================================================================")

        val testDataset = ultraShortReplies + shortConversationalPairs + shortSlangAndAlphanumericOOV

        // -----------------------------------------------------------------------------
        // EPOCH 0: Baseline Benchmark (Day 0)
        // -----------------------------------------------------------------------------
        val baselineStats = bot.runTest(testDataset, BotTester.EvalMode.LETTERS)
        println("\n📊 [Epoch 0 — Baseline Benchmark (Day 0)]")
        println("   • Short Messages Baseline Tap Rate: %.2f%%".format(baselineStats.tapPercent))
        println("   • Total Characters in Test Set: ${baselineStats.totalChars}")

        // -----------------------------------------------------------------------------
        // EPOCH 1: Fast Learning (500 Quick Messages / Week 1)
        // -----------------------------------------------------------------------------
        liveMgr.loadProfile()

        for (i in 0 until 500) {
            val msg = when (i % 10) {
                0, 1, 2, 3 -> ultraShortReplies[i % ultraShortReplies.size]
                4, 5, 6, 7 -> shortConversationalPairs[i % shortConversationalPairs.size]
                else -> shortSlangAndAlphanumericOOV[i % shortSlangAndAlphanumericOOV.size]
            }
            simulateTypingShort(liveMgr, msg)
        }

        val epoch1Stats = bot.runTest(testDataset, BotTester.EvalMode.LETTERS)
        val stats1 = liveMgr.getStats()
        println("\n📈 [Epoch 1 — Fast Learning (Week 1 / 500 Quick Messages)]")
        println("   • Short Message Tap Rate: %.2f%% (Gain: +%.2f%%)".format(
            epoch1Stats.tapPercent,
            epoch1Stats.tapPercent - baselineStats.tapPercent
        ))
        println("   • Tracked Short Words: ${stats1["wordFreqCount"]}")
        println("   • Learned Short Pairs: ${stats1["totalPairsCount"]}")
        println("   • Learned Abbreviations & Slang: ${stats1["oovCount"]}")

        assertTrue("Tap rate must improve for short replies", epoch1Stats.tapPercent > baselineStats.tapPercent)

        // -----------------------------------------------------------------------------
        // EPOCH 2: Next-Word Prediction Accuracy for Short Phrases
        // -----------------------------------------------------------------------------
        println("\n🎯 [Epoch 2 — Next-Word Auto-Suggestion Quality for 2-Word Fast Replies]")
        val samplePairs = listOf(
            "got" to "it",
            "all" to "good",
            "no" to "problem",
            "see" to "ya",
            "on" to "my",
            "sounds" to "good",
            "brb" to "in",
            "omw" to "right"
        )

        var correctSuggestions = 0
        for ((firstWord, expectedNext) in samplePairs) {
            val suggestions = predictionEngine.getPredictions("$firstWord ")
            val hit = suggestions.contains(expectedNext)
            if (hit) correctSuggestions++
            println("   • Input: \"$firstWord \" -> Predictions: $suggestions (Expected: \"$expectedNext\" -> ${if (hit) "✅ HIT" else "❌ MISS"})")
        }
        val suggestionAccuracy = (correctSuggestions.toDouble() / samplePairs.size) * 100.0
        println("   • Fast-Reply Next-Word Hit Rate: %.2f%% ($correctSuggestions / ${samplePairs.size})".format(suggestionAccuracy))
        assertTrue("Prediction engine should suggest next word for frequent pairs", correctSuggestions >= 5)

        // -----------------------------------------------------------------------------
        // EPOCH 3: Heavy Continuous Short Chatting (3,000 Cumulative Messages)
        // -----------------------------------------------------------------------------
        for (i in 0 until 2500) {
            val msg = when (i % 10) {
                0, 1, 2, 3 -> ultraShortReplies[i % ultraShortReplies.size]
                4, 5, 6, 7 -> shortConversationalPairs[i % shortConversationalPairs.size]
                else -> shortSlangAndAlphanumericOOV[i % shortSlangAndAlphanumericOOV.size]
            }
            simulateTypingShort(liveMgr, msg)
        }

        // Apply Aging Decay cycles
        repeat(5) {
            liveMgr.applyAgingDecay(0.85)
        }

        val epoch3Stats = bot.runTest(testDataset, BotTester.EvalMode.LETTERS)
        val stats3 = liveMgr.getStats()
        println("\n🚀 [Epoch 3 — Mature Fast-Replier Persona (3,000 Messages + Decay)]")
        println("   • Mature Tap Rate: %.2f%% (Final Gain over Baseline: +%.2f%%)".format(
            epoch3Stats.tapPercent,
            epoch3Stats.tapPercent - baselineStats.tapPercent
        ))
        println("   • Active Short Words: ${stats3["wordFreqCount"]}")
        println("   • Active Short Pairs: ${stats3["totalPairsCount"]}")
        println("   • Active Abbreviations/OOV: ${stats3["oovCount"]}")

        // Check that ultra-short words are firmly retained
        assertTrue("Ultra-short slang must survive",
            repo.personalProfile.wordFreq.containsKey("ok") ||
            repo.personalProfile.wordFreq.containsKey("np") ||
            repo.personalProfile.wordFreq.containsKey("thx") ||
            repo.personalProfile.wordFreq.containsKey("omw")
        )

        // Encrypted storage check
        liveMgr.saveProfileIfDirty()
        val file = File(filesDir, "flowboard_live_profile.json")
        assertTrue("Encrypted file must exist", file.exists())
        println("   • Encrypted Profile Size: %.2f KB (${file.length()} bytes)".format(file.length() / 1024.0))

        println("\n================================================================================")
        println("🎉 SHORT-MESSAGE PERSONA SIMULATION BENCHMARK COMPLETED SUCCESSFULLY!")
        println("================================================================================\n")
    }

    private fun simulateTypingShort(liveMgr: LiveLearningManager, message: String) {
        val wordRegex = Regex("[a-z0-9]+(?:['.-][a-z0-9]+)*")
        val words = wordRegex.findAll(message.lowercase()).map { it.value }.toList()
        val buffer = StringBuilder()

        for (w in words) {
            buffer.append(w).append(" ")
            liveMgr.recordWordTyped(buffer.toString())
            scoringEngine.resetTrieCache()
        }
        liveMgr.saveProfileIfDirty()
    }

    private class MockContext(
        private val baseDir: File,
        private val prefsData: MutableMap<String, Any> = mutableMapOf()
    ) : android.content.ContextWrapper(null) {
        override fun getFilesDir(): File = baseDir
        override fun getPackageName(): String = "com.flowboard.ime"
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
            return MockSharedPreferences(prefsData)
        }
    }

    private class MockSharedPreferences(private val map: MutableMap<String, Any>) : android.content.SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = MockEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class MockEditor(private val map: MutableMap<String, Any>) : android.content.SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { if (key != null && value != null) map[key] = value; return this }
        override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
        override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
        override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
        override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
        override fun remove(key: String?): android.content.SharedPreferences.Editor { map.remove(key); return this }
        override fun clear(): android.content.SharedPreferences.Editor { map.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
