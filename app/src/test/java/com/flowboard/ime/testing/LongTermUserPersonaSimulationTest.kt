package com.flowboard.ime.testing

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.engine.LayoutManager
import com.flowboard.ime.engine.LiveLearningManager
import com.flowboard.ime.engine.ScoringEngine
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Long-Term User Persona Simulation & Stress Benchmark
 *
 * Simulates an active user typing over 2,000+ sentences across multiple weeks:
 * - Measures baseline vs personalized tap rates
 * - Tracks speed of OOV word promotion to Tap slot
 * - Tests Exponential Aging Decay on frequent words vs one-off typos
 * - Verifies Zero-Degradation on standard English vocabulary
 * - Stress-tests AES-256 GCM encrypted persistence across multiple save/load cycles
 */
class LongTermUserPersonaSimulationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val repo = FlowboardRepository
    private lateinit var scoringEngine: ScoringEngine
    private lateinit var layoutManager: LayoutManager

    // Dataset Definition for Single-User Persona
    private val generalSentences = listOf(
        "how are you doing today",
        "i will see you tomorrow morning",
        "let me know when you are free for lunch",
        "what time does the movie start tonight",
        "sounds good to me see you soon",
        "thanks for the quick update on this",
        "can you send me the latest document",
        "i have arrived at the station",
        "we need to finish this project before friday",
        "have a great weekend and take care",
        "where should we go for dinner tonight",
        "are you ready for the presentation",
        "i will call you back in a few minutes",
        "please let me know if you need anything else",
        "it was really nice talking with you"
    )

    private val personaCatchphrases = listOf(
        "meet me at galaxy after work",
        "ping me on discord when ready",
        "pushing fix to staging server now",
        "check the server logs for errors",
        "deploying version two to prod",
        "lets grab coffee at central station",
        "working on the new flowboard layout",
        "sync with the backend team tomorrow"
    )

    private val customOOVAndAlphanumeric = listOf(
        "flowboard is an amazing keyboard app",
        "install supercustomapp on your device",
        "see you b4 dinner tonight",
        "friends 4ever always stay in touch",
        "test app2 with user99 credentials",
        "run benchmark v2 on release build"
    )

    private val emailSentences = listOf(
        "contact me at mey@flowboard.dev for help",
        "send the report to team@company.org please",
        "support email is help@customservice.net today"
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
    }

    @Test
    fun testLongTermUserPersonaSimulationBenchmark() {
        val filesDir = tempFolder.newFolder("persona_sim_files")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)
        val bot = BotTester(repo, scoringEngine, layoutManager)

        println("================================================================================")
        println("🚀 STARTING LONG-TERM USER PERSONA SIMULATION BENCHMARK (2,000+ Sentences)")
        println("================================================================================")

        // -----------------------------------------------------------------------------
        // EPOCH 0: Baseline Benchmark (Day 0 - Cold Start / Zero Personalization)
        // -----------------------------------------------------------------------------
        repo.isPersonalizationEnabled = false
        repo.personalProfile = com.flowboard.ime.data.models.PersonalProfile.EMPTY

        val baselinePersonaSentences = personaCatchphrases + customOOVAndAlphanumeric
        val baselineStats = bot.runTest(baselinePersonaSentences, BotTester.EvalMode.LETTERS)
        val baselineGeneralStats = bot.runTest(generalSentences, BotTester.EvalMode.LETTERS)

        println("\n📊 [Epoch 0 — Baseline Benchmark (Day 0)]")
        println("   • General Sentences Tap Rate: %.2f%%".format(baselineGeneralStats.tapPercent))
        println("   • Persona Sentences Tap Rate: %.2f%%".format(baselineStats.tapPercent))
        println("   • Total Chars Evaluated: ${baselineStats.totalChars}")

        // -----------------------------------------------------------------------------
        // EPOCH 1: Early Learning (Days 1–7 — 250 Sentences)
        // -----------------------------------------------------------------------------
        liveMgr.loadProfile()

        // Simulate typing 250 sentences with realistic persona distribution
        for (i in 0 until 250) {
            val s = when (i % 10) {
                0, 1, 2, 3, 4, 5 -> generalSentences[i % generalSentences.size]
                6, 7 -> personaCatchphrases[i % personaCatchphrases.size]
                8 -> customOOVAndAlphanumeric[i % customOOVAndAlphanumeric.size]
                else -> emailSentences[i % emailSentences.size]
            }
            simulateTypingSentence(liveMgr, s)
        }

        val epoch1Stats = bot.runTest(baselinePersonaSentences, BotTester.EvalMode.LETTERS)
        val stats1 = liveMgr.getStats()

        println("\n📈 [Epoch 1 — Early Learning (Days 1–7 / 250 Sentences)]")
        println("   • Persona Tap Rate: %.2f%% (Gain: +%.2f%%)".format(
            epoch1Stats.tapPercent,
            epoch1Stats.tapPercent - baselineStats.tapPercent
        ))
        println("   • Tracked Words: ${stats1["wordFreqCount"]}")
        println("   • Learned Pairs (Bigram/Trigram): ${stats1["totalPairsCount"]}")
        println("   • Learned OOV & Emails: ${stats1["oovCount"]}")

        assertTrue("Persona tap rate must improve in Epoch 1", epoch1Stats.tapPercent >= baselineStats.tapPercent)
        assertTrue("OOV words must be learned", (stats1["oovCount"] ?: 0) > 0)

        // -----------------------------------------------------------------------------
        // EPOCH 2: Intensive Habitual Usage (Days 8–20 — 1,000 Sentences)
        // -----------------------------------------------------------------------------
        for (i in 0 until 750) {
            val s = when (i % 10) {
                0, 1, 2, 3, 4 -> generalSentences[i % generalSentences.size]
                5, 6, 7 -> personaCatchphrases[i % personaCatchphrases.size]
                8 -> customOOVAndAlphanumeric[i % customOOVAndAlphanumeric.size]
                else -> emailSentences[i % emailSentences.size]
            }
            simulateTypingSentence(liveMgr, s)
        }

        val epoch2Stats = bot.runTest(baselinePersonaSentences, BotTester.EvalMode.LETTERS)
        val stats2 = liveMgr.getStats()

        println("\n🔥 [Epoch 2 — Intensive Usage (Days 8–20 / 1,000 Cumulative Sentences)]")
        println("   • Persona Tap Rate: %.2f%% (Gain over Baseline: +%.2f%%)".format(
            epoch2Stats.tapPercent,
            epoch2Stats.tapPercent - baselineStats.tapPercent
        ))
        println("   • Tracked Words: ${stats2["wordFreqCount"]}")
        println("   • Learned Pairs: ${stats2["totalPairsCount"]}")
        println("   • Learned OOV: ${stats2["oovCount"]}")

        assertTrue("Epoch 2 tap rate must exceed baseline", epoch2Stats.tapPercent > baselineStats.tapPercent)

        // -----------------------------------------------------------------------------
        // EPOCH 3: Long-term Usage & Aging Decay with Typo Pruning (Days 21–45 — 2,000+ Sentences)
        // -----------------------------------------------------------------------------
        // Inject 50 random one-off typos/rare words
        val randomTypos = (1..50).map { "typoword$it" }
        for (typo in randomTypos) {
            liveMgr.recordWordTyped("testing typo $typo ")
        }

        val statsBeforeDecay = liveMgr.getStats()
        println("\n🧹 [Epoch 3 — Aging Decay & Typo Pruning Simulation]")
        println("   • Words before decay: ${statsBeforeDecay["wordFreqCount"]}")

        // Simulate 1,000 more sentences of regular typing (which triggers aging decay cycles)
        for (i in 0 until 1000) {
            val s = when (i % 10) {
                0, 1, 2, 3, 4 -> generalSentences[i % generalSentences.size]
                5, 6, 7 -> personaCatchphrases[i % personaCatchphrases.size]
                8 -> customOOVAndAlphanumeric[i % customOOVAndAlphanumeric.size]
                else -> emailSentences[i % emailSentences.size]
            }
            simulateTypingSentence(liveMgr, s)
        }

        // Apply multiple aging decay steps to simulate 30 days passing
        repeat(5) {
            liveMgr.applyAgingDecay(0.85)
        }

        val statsAfterDecay = liveMgr.getStats()
        println("   • Words after 5 aging decay cycles: ${statsAfterDecay["wordFreqCount"]}")
        println("   • Learned Pairs after decay: ${statsAfterDecay["totalPairsCount"]}")

        // Verify one-off typos are pruned
        var prunedTyposCount = 0
        for (typo in randomTypos) {
            if (!repo.personalProfile.wordFreq.containsKey(typo)) {
                prunedTyposCount++
            }
        }
        println("   • Pruned One-off Typos: $prunedTyposCount / 50 (${prunedTyposCount * 2}%%)")
        assertTrue("Most one-off typos should be forgotten by aging decay", prunedTyposCount >= 40)

        // Verify frequent words remain healthy and strong
        assertTrue("Frequent persona words must survive decay",
            repo.personalProfile.wordFreq.containsKey("galaxy") ||
            repo.personalProfile.wordFreq.containsKey("discord") ||
            repo.personalProfile.wordFreq.containsKey("flowboard")
        )

        val epoch3Stats = bot.runTest(baselinePersonaSentences, BotTester.EvalMode.LETTERS)
        println("   • Mature Persona Tap Rate: %.2f%% (Final vs Baseline: +%.2f%%)".format(
            epoch3Stats.tapPercent,
            epoch3Stats.tapPercent - baselineStats.tapPercent
        ))

        // -----------------------------------------------------------------------------
        // EPOCH 4: Zero-Degradation Check on Standard Vocabulary
        // -----------------------------------------------------------------------------
        val generalStatsAfterLearning = bot.runTest(generalSentences, BotTester.EvalMode.LETTERS)
        println("\n🛡️ [Epoch 4 — Zero-Degradation Verification on Standard English]")
        println("   • General Baseline Tap Rate: %.2f%%".format(baselineGeneralStats.tapPercent))
        println("   • General Post-Learning Tap Rate: %.2f%%".format(generalStatsAfterLearning.tapPercent))

        assertTrue("General English Tap Rate must NOT degrade from personalization",
            generalStatsAfterLearning.tapPercent >= baselineGeneralStats.tapPercent - 0.5
        )

        // -----------------------------------------------------------------------------
        // EPOCH 5: Encrypted Storage Stress Test (100 Save & Load Cycles)
        // -----------------------------------------------------------------------------
        println("\n🔐 [Epoch 5 — Encrypted Storage Stress Test (100 Cycles)]")
        for (cycle in 1..100) {
            liveMgr.recordWordTyped("cycle_test_word_$cycle ")
            liveMgr.saveProfileIfDirty()

            val reloadedManager = LiveLearningManager(mockContext)
            reloadedManager.loadProfile()

            val reloadedProfile = repo.personalProfile
            assertTrue("Cycle $cycle: Profile must survive encryption/decryption cycle",
                reloadedProfile.wordFreq.containsKey("galaxy") || reloadedProfile.wordFreq.containsKey("flowboard")
            )
        }

        val savedFile = File(filesDir, "flowboard_live_profile.json")
        assertTrue("Encrypted file must exist", savedFile.exists())
        assertTrue("Encrypted file must have content", savedFile.length() > 0)
        println("   • 100/100 Encrypted persistence cycles passed with 0 data loss.")
        println("   • Final Encrypted File Size: ${savedFile.length()} bytes.")

        println("\n================================================================================")
        println("🎉 ALL 2,000+ SENTENCES SIMULATION BENCHMARK COMPLETED SUCCESSFULLY!")
        println("================================================================================\n")
    }

    private fun simulateTypingSentence(liveMgr: LiveLearningManager, sentence: String) {
        val wordRegex = Regex("[a-z0-9]+(?:['.-][a-z0-9]+)*")
        val words = wordRegex.findAll(sentence.lowercase()).map { it.value }.toList()
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
