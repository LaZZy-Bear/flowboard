package com.flowboard.ime.testing

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.engine.LayoutManager
import com.flowboard.ime.engine.LiveLearningManager
import com.flowboard.ime.engine.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonalizationLiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val repo = FlowboardRepository
    private lateinit var scoringEngine: ScoringEngine
    private lateinit var layoutManager: LayoutManager

    @Before
    fun setup() {
        TestDataFactory.loadRepo(repo)
        scoringEngine = ScoringEngine(repo)
        layoutManager = LayoutManager(repo)
    }

    @Test
    fun testLiveLearningAndPersonalizationEndToEnd() {
        val filesDir = tempFolder.newFolder("files")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)

        // 1. Initial State
        liveMgr.loadProfile()

        // 2. User types a custom phrase containing an OOV word and a strong pair: "meet me at galaxy"
        liveMgr.recordWordTyped("meet me at galaxy")
        liveMgr.recordWordTyped("meet me at galaxy")
        liveMgr.recordWordTyped("meet me at galaxy")

        // 3. Verify in-memory state in Repository
        assertTrue("Personalization should be enabled in repo", repo.isPersonalizationEnabled)
        assertTrue("Personal profile should not be empty", !repo.personalProfile.isEmpty)
        assertTrue("galaxy should be learned as OOV word or frequent word",
            repo.personalProfile.wordFreq.containsKey("galaxy") || repo.personalProfile.learnedOOV.contains("galaxy"))

        // 4. Test Prediction Boost for "meet me at " -> Should boost 'g' for "galaxy"
        val scoresBeforeTypingG = scoringEngine.calculateScores("meet me at ")
        assertTrue("Score for 'g' should be present and boosted", (scoresBeforeTypingG["g"] ?: 0.0) > 0.0)

        // 5. Test OOV Word: type "supercustomapp"
        val customOOV = "supercustomapp"
        liveMgr.recordWordTyped("hello $customOOV ")
        liveMgr.recordWordTyped("hello $customOOV ")

        assertTrue("OOV word should be in learnedOOV list", repo.personalProfile.learnedOOV.contains(customOOV))

        // Check if trieDictOOV contains the word
        var node = repo.trieDictOOV
        for (ch in customOOV) {
            node = node?.get(ch.toString())
        }
        assertNotNull("Trie path for OOV word must exist in trieDictOOV", node)
        assertTrue("Node must be end of word", node?.isEndOfWord == true)

        // 6. Test Persistence: Save to disk
        liveMgr.saveProfileIfDirty()

        val savedFile = java.io.File(filesDir, "flowboard_live_profile.json")
        assertTrue("Profile file must exist on disk after saveProfileIfDirty", savedFile.exists())
        assertTrue("Profile file must have non-zero size", savedFile.length() > 0)

        // 7. Test Reloading: Fresh manager loading from disk
        val freshLiveMgr = LiveLearningManager(mockContext)
        freshLiveMgr.loadProfile()

        assertTrue("Reloaded profile must contain wordFreq 'galaxy'", repo.personalProfile.wordFreq.containsKey("galaxy"))
        assertTrue("Reloaded profile must contain OOV '$customOOV'", repo.personalProfile.learnedOOV.contains(customOOV))
    }

    @Test
    fun testAlphanumericOOVWordsLearningAndPrediction() {
        val filesDir = tempFolder.newFolder("files2")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)

        liveMgr.loadProfile()

        // Simulate typing sequence word-by-word (as happens when user presses spacebar)
        // Sequence 1: "see you b4 dinner"
        liveMgr.recordWordTyped("see")
        liveMgr.recordWordTyped("see you")
        liveMgr.recordWordTyped("see you b4")
        liveMgr.recordWordTyped("see you b4 dinner")

        // Repeat sequence to build strong pairs
        liveMgr.recordWordTyped("see you b4")
        liveMgr.recordWordTyped("see you b4 dinner")

        // Sequence 2: "friends 4ever always"
        liveMgr.recordWordTyped("friends")
        liveMgr.recordWordTyped("friends 4ever")
        liveMgr.recordWordTyped("friends 4ever always")

        // 1. Verify words containing numbers are recorded in learnedOOV / wordFreq / bigram / trigram
        assertTrue("b4 should be recorded in wordFreq", repo.personalProfile.wordFreq.containsKey("b4"))
        assertTrue("4ever should be recorded in wordFreq", repo.personalProfile.wordFreq.containsKey("4ever"))
        assertTrue("b4 should be in learnedOOV", repo.personalProfile.learnedOOV.contains("b4"))
        assertTrue("4ever should be in learnedOOV", repo.personalProfile.learnedOOV.contains("4ever"))

        // 2. Verify b4 is present in trieDictOOV
        val b4Node = repo.trieDictOOV?.get("b")?.get("4")
        assertNotNull("b4 must exist in trieDictOOV", b4Node)
        assertTrue("b4 must be marked as end of word", b4Node?.isEndOfWord == true)

        // 3. Verify 4ever is present in trieDictOOV
        val node4ever = repo.trieDictOOV?.get("4")?.get("e")?.get("v")?.get("e")?.get("r")
        assertNotNull("4ever must exist in trieDictOOV", node4ever)
        assertTrue("4ever must be marked as end of word", node4ever?.isEndOfWord == true)

        // 4. Verify that digits themselves are NOT scored/boosted on the letter keyboard layout
        val scoresAfterFriends = scoringEngine.calculateScores("friends ")
        assertFalse("Digits like '4' should not be present in letter scores", scoresAfterFriends.containsKey("4"))

        // 5. Verify that the word typed AFTER the alphanumeric word gets boosted!
        val scoresAfterB4 = scoringEngine.calculateScores("see you b4 ")
        assertTrue("Score for 'd' (from 'dinner') after 'b4' should be boosted", (scoresAfterB4["d"] ?: 0.0) > 0.0)

        // 6. After typing "friends 4", prefix is "4", the next letter of "4ever" is 'e' -> 'e' should be boosted!
        val scoresAfter4 = scoringEngine.calculateScores("friends 4")
        assertTrue("Score for 'e' after typing '4' for '4ever' should be positive", (scoresAfter4["e"] ?: 0.0) > 0.0)
    }

    @Test
    fun testAdvancedSettingsMultiplierAndToggles() {
        val filesDir = tempFolder.newFolder("files3")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)

        liveMgr.loadProfile()
        liveMgr.recordWordTyped("hello")
        liveMgr.recordWordTyped("hello world")
        liveMgr.recordWordTyped("hello world")
        liveMgr.recordWordTyped("hello world")

        // 1. Base Score with 1.0x multiplier
        repo.personalizationBoostMultiplier = 1.0
        repo.personalizationPairsEnabled = true
        val scoreNormal = scoringEngine.calculateScores("hello ")["w"] ?: 0.0

        // 2. Score with 2.0x multiplier
        repo.personalizationBoostMultiplier = 2.0
        val scoreAggressive = scoringEngine.calculateScores("hello ")["w"] ?: 0.0
        assertTrue("2.0x multiplier score should be strictly greater than 1.0x score", scoreAggressive > scoreNormal)

        // 3. Disable Pairs Toggle
        repo.personalizationPairsEnabled = false
        val scoreDisabled = scoringEngine.calculateScores("hello ")["w"] ?: 0.0
        assertTrue("Disabling pairs should reduce score to base level", scoreDisabled < scoreNormal)

        // Reset
        repo.personalizationBoostMultiplier = 1.0
        repo.personalizationPairsEnabled = true
    }

    @Test
    fun testClearProfileAndStats() {
        val filesDir = tempFolder.newFolder("files4")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)

        liveMgr.loadProfile()
        liveMgr.recordWordTyped("apple")
        liveMgr.recordWordTyped("apple pie")
        liveMgr.recordWordTyped("customoovword")

        val stats = liveMgr.getStats()
        assertTrue("Stats should report wordFreqCount > 0", (stats["wordFreqCount"] ?: 0) > 0)
        assertTrue("Stats should report oovCount > 0", (stats["oovCount"] ?: 0) > 0)
        assertTrue("Stats should report totalPairsCount > 0", (stats["totalPairsCount"] ?: 0) > 0)

        // Clear Profile
        liveMgr.clearProfile()
        val clearedStats = liveMgr.getStats()
        assertEquals(0, clearedStats["wordFreqCount"])
        assertEquals(0, clearedStats["oovCount"])
        assertEquals(0, clearedStats["bigramCount"])
        assertEquals(0, clearedStats["totalPairsCount"])
        assertTrue("Repository personalProfile should be empty after clear", repo.personalProfile.isEmpty)
    }

    @Test
    fun testWordPairsRecordingWithFullSentenceContext() {
        val filesDir = tempFolder.newFolder("files5")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)

        liveMgr.loadProfile()

        // Type full sentence: "I love flowboard because it is fast"
        liveMgr.recordWordTyped("I")
        liveMgr.recordWordTyped("I love")
        liveMgr.recordWordTyped("I love flowboard")
        liveMgr.recordWordTyped("I love flowboard because")
        liveMgr.recordWordTyped("I love flowboard because it")
        liveMgr.recordWordTyped("I love flowboard because it is")
        liveMgr.recordWordTyped("I love flowboard because it is fast")

        val stats = liveMgr.getStats()
        assertTrue("Should have recorded multiple word pairs", (stats["totalPairsCount"] ?: 0) >= 5)
        assertTrue("Should have bigrams recorded", (stats["bigramCount"] ?: 0) >= 4)
        assertTrue("Should have trigrams recorded", (stats["trigramCount"] ?: 0) >= 3)
        assertTrue("Bigram 'i' -> 'love' should exist", repo.personalProfile.bigram["i"]?.containsKey("love") == true)
        assertTrue("Bigram 'love' -> 'flowboard' should exist", repo.personalProfile.bigram["love"]?.containsKey("flowboard") == true)
        assertTrue("Trigram 'i_love' -> 'flowboard' should exist", repo.personalProfile.trigram["i_love"]?.containsKey("flowboard") == true)
    }

    @Test
    fun testCapacityLimitsAndPruning() {
        val filesDir = tempFolder.newFolder("files6")
        val prefs = mutableMapOf<String, Any>(
            "personalization_max_word_freq" to "2",
            "personalization_max_pairs" to "2",
            "personalization_max_oov" to "2"
        )
        val mockContext = MockContext(filesDir, prefs)
        val liveMgr = LiveLearningManager(mockContext)

        liveMgr.loadProfile()

        // Record 5 distinct OOV words and pairs
        liveMgr.recordWordTyped("first customoovone")
        liveMgr.recordWordTyped("second customoovtwo")
        liveMgr.recordWordTyped("third customoovthree")
        liveMgr.recordWordTyped("fourth customoovfour")
        liveMgr.recordWordTyped("fifth customoovfive")

        val stats = liveMgr.getStats()
        assertTrue("wordFreqCount should be pruned to <= 2, but was ${stats["wordFreqCount"]}", (stats["wordFreqCount"] ?: 0) <= 2)
        assertTrue("bigramCount should be pruned to <= 2, but was ${stats["bigramCount"]}", (stats["bigramCount"] ?: 0) <= 2)
        assertTrue("oovCount should be pruned to <= 2, but was ${stats["oovCount"]}", (stats["oovCount"] ?: 0) <= 2)
    }

    @Test
    fun testReloadProfileAfterClearProfile() {
        val filesDir = tempFolder.newFolder("files7")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)

        liveMgr.loadProfile()
        liveMgr.recordWordTyped("hello flowboard")
        liveMgr.saveProfileIfDirty()

        // Clear
        liveMgr.clearProfile()
        assertTrue("Repo should be empty after clear", repo.personalProfile.isEmpty)

        // Reload on fresh instance
        val freshLiveMgr = LiveLearningManager(mockContext)
        freshLiveMgr.loadProfile()
        assertTrue("Repo should remain empty after reloading cleared profile", repo.personalProfile.isEmpty)
        val stats = freshLiveMgr.getStats()
        assertEquals(0, stats["wordFreqCount"])
        assertEquals(0, stats["bigramCount"])
        assertEquals(0, stats["oovCount"])
    }

    @Test
    fun testOOVWordMidWordPredictionAndTapPlacement() {
        val filesDir = tempFolder.newFolder("files_oov_test")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)

        liveMgr.loadProfile()

        // User typed "imsombat"
        liveMgr.recordWordTyped("hello imsombat")

        // Now user starts typing "ims"
        scoringEngine.resetTrieCache()
        val scores = scoringEngine.calculateScores("ims")
        val scoreO = scores["o"] ?: 0.0
        val scoreE = scores["e"] ?: 0.0

        assertTrue("Score for 'o' ($scoreO) should dominate 'e' ($scoreE) for learned OOV word 'imsombat'", scoreO > scoreE)

        val layout = layoutManager.assignLayout(scores)
        val key7 = layout["key_7"]
        assertNotNull(key7)
        assertEquals("Character 'o' must win the TAP slot on key_7 when typing prefix 'ims'", "o", key7?.tap)
    }

    @Test
    fun testPersonalWordBigramAndTrigramSequenceTapPlacement() {
        val filesDir = tempFolder.newFolder("files_bigram_trigram_test")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)

        liveMgr.loadProfile()

        // User types phrase "aight yet name" step-by-step (as spacebar is pressed after each word)
        liveMgr.recordWordTyped("aight")
        liveMgr.recordWordTyped("aight yet")
        liveMgr.recordWordTyped("aight yet name")

        assertTrue("Personal bigram must record 'aight' -> 'yet'",
            repo.personalProfile.bigram["aight"]?.containsKey("yet") == true)
        assertTrue("Personal trigram must record 'aight_yet' -> 'name'",
            repo.personalProfile.trigram["aight_yet"]?.containsKey("name") == true)

        // 1. User types "aight " (space after aight) -> next word is "yet", first char is 'y'
        scoringEngine.resetTrieCache()
        var scores = scoringEngine.calculateScores("aight ")
        var layout = layoutManager.assignLayout(scores)
        assertEquals("Character 'y' (char 1 of yet) must win TAP on key_9", "y", layout["key_9"]?.tap)

        // 1b. User types 'y' -> next char is 'e' (char 2 of yet)
        scores = scoringEngine.calculateScores("aight y")
        layout = layoutManager.assignLayout(scores)
        assertEquals("Character 'e' (char 2 of yet) must win TAP on key_7", "e", layout["key_7"]?.tap)

        // 1c. User types 'e' -> next char is 't' (char 3 of yet)
        scores = scoringEngine.calculateScores("aight ye")
        layout = layoutManager.assignLayout(scores)
        assertEquals("Character 't' (char 3 of yet) must win TAP on key_2", "t", layout["key_2"]?.tap)

        // 2. User finishes "yet" and types space -> "aight yet " -> next word is "name", char 1 is 'n'
        scoringEngine.resetTrieCache()
        scores = scoringEngine.calculateScores("aight yet ")
        layout = layoutManager.assignLayout(scores)
        assertEquals("Character 'n' (char 1 of name) must win TAP on key_5", "n", layout["key_5"]?.tap)

        // 2b. User types 'n' -> next char is 'a' (char 2 of name)
        scores = scoringEngine.calculateScores("aight yet n")
        layout = layoutManager.assignLayout(scores)
        assertEquals("Character 'a' (char 2 of name) must win TAP on key_1", "a", layout["key_1"]?.tap)

        // 2c. User types 'a' -> next char is 'm' (char 3 of name)
        scores = scoringEngine.calculateScores("aight yet na")
        layout = layoutManager.assignLayout(scores)
        assertEquals("Character 'm' (char 3 of name) must win TAP on key_9", "m", layout["key_9"]?.tap)

        // 2d. User types 'm' -> next char is 'e' (char 4 of name)
        scores = scoringEngine.calculateScores("aight yet nam")
        layout = layoutManager.assignLayout(scores)
        assertEquals("Character 'e' (char 4 of name) must win TAP on key_7", "e", layout["key_7"]?.tap)
    }

    @Test
    fun testAiPrefixPartnerSwapTakesKey3Tap() {
        val filesDir = tempFolder.newFolder("files_ai_test")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)

        liveMgr.loadProfile()

        // User typed "aight" once
        liveMgr.recordWordTyped("aight")

        // User is now typing "ai"
        assertFalse("Character 'i' should not be sticky after 'ai'",
            scoringEngine.isDoubleCharValid("ai", "i"))

        repo.stickyChar = null
        scoringEngine.resetTrieCache()
        val scores = scoringEngine.calculateScores("ai")
        val layout = layoutManager.assignLayout(scores)

        // Both 'r' and 'g' occupy TAP slots across key_6 and partner key_3
        val tapChars = setOf(layout["key_6"]?.tap, layout["key_3"]?.tap)
        assertTrue("Both 'r' and 'g' should occupy TAP across Key 3 and Key 6",
            tapChars.contains("r") && tapChars.contains("g"))
    }

    @Test
    fun testFullKeystrokeSequenceTypingAi() {
        val filesDir = tempFolder.newFolder("files_seq_test")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)
        liveMgr.loadProfile()

        // ── Step 1: User types 'a' (key_1) ──
        repo.lastActionKeyId = "key_1"
        repo.lastActionSlot = "up"
        repo.lastActionChar = "a"
        var text = "a"
        repo.stickyChar = if (scoringEngine.isDoubleCharValid(text, repo.lastActionChar!!)) repo.lastActionChar else null
        scoringEngine.resetTrieCache()
        var scores = scoringEngine.calculateScores(text)
        var layout = layoutManager.assignLayout(scores)

        // After 'a', 'i' is on Key 3 TAP
        assertEquals("Character 'i' is TAP on Key 3 after typing 'a'", "i", layout["key_3"]?.tap)

        // ── Step 2: User taps 'i' (key_3) ──
        repo.lastActionKeyId = "key_3"
        repo.lastActionSlot = "tap"
        repo.lastActionChar = "i"
        text = "ai"
        repo.stickyChar = if (scoringEngine.isDoubleCharValid(text, repo.lastActionChar!!)) repo.lastActionChar else null
        assertNull("Sticky key for 'i' must be null after typing 'ai'", repo.stickyChar)

        scoringEngine.resetTrieCache()
        scores = scoringEngine.calculateScores(text)
        layout = layoutManager.assignLayout(scores)

        // After 'ai':
        // Key 3 TAP MUST NOT be 'i'!
        assertNotEquals("Key 3 TAP must NOT be 'i' after typing 'ai'", "i", layout["key_3"]?.tap)
        assertEquals("Key 6 TAP must be 'r' after typing 'ai'", "r", layout["key_6"]?.tap)
        assertEquals("Key 3 TAP must be 'g' after typing 'ai'", "g", layout["key_3"]?.tap)
    }

    private class MockContext(
        private val baseDir: java.io.File,
        private val prefsData: MutableMap<String, Any> = mutableMapOf()
    ) : android.content.ContextWrapper(null) {
        override fun getFilesDir(): java.io.File = baseDir

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
