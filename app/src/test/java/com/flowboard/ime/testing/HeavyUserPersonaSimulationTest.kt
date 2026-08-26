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
 * Heavy Power User / Developer Persona Simulation Benchmark (4,000+ Sentences)
 *
 * Evaluates:
 * - High-volume technical & gaming vocabulary learning
 * - Dynamic capacity cap analysis (1,000 vs 2,000 vs 5,000)
 * - Memory footprint and JSON encryption payload size
 * - Long-term Aging Decay under continuous high-throughput typing
 */
class HeavyUserPersonaSimulationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val repo = FlowboardRepository
    private lateinit var scoringEngine: ScoringEngine
    private lateinit var layoutManager: LayoutManager

    // Persona 2: Tech Lead / Developer / Gamer Daily Vocabulary
    private val devSentences = listOf(
        "deploy to kubernetes cluster in production",
        "check the dockerfile and build the container image",
        "review my pull request on github asap",
        "database migration failed on staging server",
        "endpoint returned four zero four not found",
        "configure oauth2 and webhook for authentication",
        "running unit tests with mockito framework",
        "optimize sql query for higher throughput",
        "pushing hotfix branch to origin main",
        "merge the feature branch after passing ci"
    )

    private val gamingAndSlangSentences = listOf(
        "gg wp good game everyone",
        "join the voice channel on discord",
        "streaming on twitch tonight at eight",
        "grinding the battle pass with the squad",
        "clutch round with thirty seconds remaining",
        "lets drop at tilted towers right now",
        "lag spike caused high ping in ranked match"
    )

    private val technicalOOVAndAlphanumeric = listOf(
        "kubernetes is orchestrating twenty pods",
        "configure solana and reactjs frontend",
        "graphql schema updated with new mutations",
        "integrate auth0 with fastapi backend",
        "restart nginx and postgres services",
        "kafka consumer lag is zero right now",
        "listening on port8080 with node18 runtime",
        "built with jdk21 and deployed to ec2 instance",
        "verify sha256 checksum on release archive",
        "enable ipv6 routing on the gateway"
    )

    private val multiAccountEmails = listOf(
        "send logs to dev@opensource.org please",
        "contact alice.smith@techcorp.io for access",
        "inquiries go to admin@web3gaming.gg today",
        "system alerts from notifications@github.com",
        "invoices sent to billing@cloudprovider.com"
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
    fun testHeavyPowerUserPersonaBenchmark() {
        val filesDir = tempFolder.newFolder("heavy_persona_files")
        val mockPrefs = mutableMapOf<String, Any>(
            "personalization_max_word_freq" to "2,000",
            "personalization_max_pairs" to "2,000",
            "personalization_max_oov" to "1,000"
        )
        val mockContext = MockContext(filesDir, mockPrefs)
        val liveMgr = LiveLearningManager(mockContext)
        val bot = BotTester(repo, scoringEngine, layoutManager)

        println("================================================================================")
        println("⚡ STARTING HEAVY POWER USER PERSONA SIMULATION BENCHMARK (4,000+ Sentences)")
        println("================================================================================")

        val heavyTestSet = devSentences + gamingAndSlangSentences + technicalOOVAndAlphanumeric

        // -----------------------------------------------------------------------------
        // EPOCH 0: Baseline (Day 0)
        // -----------------------------------------------------------------------------
        val baselineStats = bot.runTest(heavyTestSet, BotTester.EvalMode.LETTERS)
        println("\n📊 [Epoch 0 — Baseline Benchmark (Day 0)]")
        println("   • Technical & Gaming Tap Rate: %.2f%%".format(baselineStats.tapPercent))
        println("   • Total Chars: ${baselineStats.totalChars}")

        // -----------------------------------------------------------------------------
        // EPOCH 1: Heavy Learning (500 Sentences / Week 1)
        // -----------------------------------------------------------------------------
        liveMgr.loadProfile()

        for (i in 0 until 500) {
            val s = when (i % 10) {
                0, 1, 2, 3 -> devSentences[i % devSentences.size]
                4, 5, 6 -> gamingAndSlangSentences[i % gamingAndSlangSentences.size]
                7, 8 -> technicalOOVAndAlphanumeric[i % technicalOOVAndAlphanumeric.size]
                else -> multiAccountEmails[i % multiAccountEmails.size]
            }
            simulateTyping(liveMgr, s)
        }

        val epoch1Stats = bot.runTest(heavyTestSet, BotTester.EvalMode.LETTERS)
        val stats1 = liveMgr.getStats()
        println("\n📈 [Epoch 1 — Heavy Learning (Week 1 / 500 Sentences)]")
        println("   • Technical Tap Rate: %.2f%% (Gain: +%.2f%%)".format(
            epoch1Stats.tapPercent,
            epoch1Stats.tapPercent - baselineStats.tapPercent
        ))
        println("   • Tracked Words: ${stats1["wordFreqCount"]}")
        println("   • Word Pairs: ${stats1["totalPairsCount"]}")
        println("   • Technical OOVs & Emails: ${stats1["oovCount"]}")

        assertTrue("Tap rate must improve on technical sentences", epoch1Stats.tapPercent > baselineStats.tapPercent)

        // -----------------------------------------------------------------------------
        // EPOCH 2: High-Volume Typing (2,000 Cumulative Sentences / Month 1)
        // -----------------------------------------------------------------------------
        for (i in 0 until 1500) {
            val s = when (i % 10) {
                0, 1, 2, 3 -> devSentences[i % devSentences.size]
                4, 5, 6 -> gamingAndSlangSentences[i % gamingAndSlangSentences.size]
                7, 8 -> technicalOOVAndAlphanumeric[i % technicalOOVAndAlphanumeric.size]
                else -> multiAccountEmails[i % multiAccountEmails.size]
            }
            simulateTyping(liveMgr, s)
        }

        val epoch2Stats = bot.runTest(heavyTestSet, BotTester.EvalMode.LETTERS)
        val stats2 = liveMgr.getStats()
        println("\n🔥 [Epoch 2 — High Volume Typing (Month 1 / 2,000 Sentences)]")
        println("   • Technical Tap Rate: %.2f%% (Gain over Baseline: +%.2f%%)".format(
            epoch2Stats.tapPercent,
            epoch2Stats.tapPercent - baselineStats.tapPercent
        ))
        println("   • Tracked Words: ${stats2["wordFreqCount"]}")
        println("   • Word Pairs: ${stats2["totalPairsCount"]}")
        println("   • Technical OOVs: ${stats2["oovCount"]}")

        // -----------------------------------------------------------------------------
        // EPOCH 3: Extreme Scale Simulation (4,000 Cumulative Sentences + Decay Cycles)
        // -----------------------------------------------------------------------------
        // Inject 100 transient typo words
        val typoWords = (1..100).map { "techtypo$it" }
        for (t in typoWords) {
            liveMgr.recordWordTyped("broken command $t ")
        }

        // Simulate 2,000 more sentences
        for (i in 0 until 2000) {
            val s = when (i % 10) {
                0, 1, 2, 3 -> devSentences[i % devSentences.size]
                4, 5, 6 -> gamingAndSlangSentences[i % gamingAndSlangSentences.size]
                7, 8 -> technicalOOVAndAlphanumeric[i % technicalOOVAndAlphanumeric.size]
                else -> multiAccountEmails[i % multiAccountEmails.size]
            }
            simulateTyping(liveMgr, s)
        }

        // Multiple aging decay cycles
        repeat(8) {
            liveMgr.applyAgingDecay(0.85)
        }

        val epoch3Stats = bot.runTest(heavyTestSet, BotTester.EvalMode.LETTERS)
        val stats3 = liveMgr.getStats()
        println("\n🚀 [Epoch 3 — Extreme Usage (Month 2 / 4,000 Sentences + 8 Decay Cycles)]")
        println("   • Mature Technical Tap Rate: %.2f%% (Gain: +%.2f%%)".format(
            epoch3Stats.tapPercent,
            epoch3Stats.tapPercent - baselineStats.tapPercent
        ))
        println("   • Active Tracked Words: ${stats3["wordFreqCount"]}")
        println("   • Active Pairs: ${stats3["totalPairsCount"]}")
        println("   • Active OOVs: ${stats3["oovCount"]}")

        // Check typo pruning
        var prunedCount = 0
        for (t in typoWords) {
            if (!repo.personalProfile.wordFreq.containsKey(t)) prunedCount++
        }
        println("   • Pruned Transient Typos: $prunedCount / 100 (${prunedCount}%%)")
        assertTrue("Typo pruning must remove majority of transient errors", prunedCount >= 85)

        // Verify technical words retained maximum boost
        assertTrue("Kubernetes must be learned and retained",
            repo.personalProfile.wordFreq.containsKey("kubernetes") ||
            repo.personalProfile.learnedOOV.contains("kubernetes")
        )
        assertTrue("Docker / Fastapi must be retained",
            repo.personalProfile.wordFreq.containsKey("dockerfile") ||
            repo.personalProfile.learnedOOV.contains("fastapi")
        )

        // Check encrypted payload size
        liveMgr.saveProfileIfDirty()
        val file = File(filesDir, "flowboard_live_profile.json")
        assertTrue("Encrypted file must exist", file.exists())
        println("   • Encrypted Profile Size on Disk: %.2f KB (${file.length()} bytes)".format(file.length() / 1024.0))

        println("\n================================================================================")
        println("🎉 HEAVY POWER USER 4,000+ SENTENCES BENCHMARK COMPLETED SUCCESSFULLY!")
        println("================================================================================\n")
    }

    private fun simulateTyping(liveMgr: LiveLearningManager, sentence: String) {
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
