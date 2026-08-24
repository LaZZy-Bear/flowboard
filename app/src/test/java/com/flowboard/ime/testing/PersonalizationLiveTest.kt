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
        repo.isPersonalizationEnabled = false
        repo.personalProfile = com.flowboard.ime.data.models.PersonalProfile.EMPTY
        repo.personalizationPairsEnabled = true
        repo.personalizationFreqEnabled = true
        repo.personalizationAlphanumericEnabled = true
        repo.personalizationBoostMultiplier = 1.0
        repo.personalizationOOVMultiplier = 1.3
        repo.personalizationFirstTypeBonus = 250.0
        repo.personalizationUncertaintyGap = 15.0
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
        liveMgr.recordWordTyped("aight yet")
        liveMgr.recordWordTyped("aight yet name")
        liveMgr.recordWordTyped("aight yet name")

        assertTrue("Personal bigram must record 'aight' -> 'yet'",
            repo.personalProfile.bigram["aight"]?.containsKey("yet") == true)
        assertTrue("Personal trigram must record 'aight_yet' -> 'name'",
            repo.personalProfile.trigram["aight_yet"]?.containsKey("name") == true)

        // 1. User types "aight " (space after aight) -> next word is "yet", first char is 'y'
        scoringEngine.resetTrieCache()
        var scores = scoringEngine.calculateScores("aight ")
        var layout = layoutManager.assignLayout(scores)
        assertTrue("Score for 'y' (char 1 of yet) must be boosted", (scores["y"] ?: 0.0) > 0.0)
        assertTrue("Key 9 must contain 'y'", layout["key_9"]?.visibleChars()?.contains("y") == true)

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

        // After 'a', 'i' is on Key 3
        assertTrue("Character 'i' is on Key 3 after typing 'a'", layout["key_3"]?.visibleChars()?.contains("i") == true)

        // ── Step 2: User types 'i' (key_3) ──
        repo.lastActionKeyId = "key_3"
        repo.lastActionSlot = "up"
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
        assertTrue("Key 3 TAP must be assigned after typing 'ai'", layout["key_3"]?.tap?.isNotEmpty() == true)
    }

    @Test
    fun testAllPersonalizationSettingsOptions() {
        val filesDir = tempFolder.newFolder("settings_test")
        val prefs = mutableMapOf<String, Any>()
        val mockContext = MockContext(filesDir, prefs)

        // ── Test 1: Master switch disabled ──
        prefs["personalization_enabled"] = false
        val liveMgr1 = LiveLearningManager(mockContext)
        liveMgr1.loadProfile()
        liveMgr1.recordWordTyped("meet me at galaxy")

        assertFalse("Personalization must be disabled when user turned it off", repo.isPersonalizationEnabled)
        assertEquals("Stats must be 0 when master switch is off", 0, liveMgr1.getStats()["wordFreqCount"])

        // ── Test 2: Master switch enabled ──
        prefs["personalization_enabled"] = true
        val liveMgr2 = LiveLearningManager(mockContext)
        liveMgr2.loadProfile()
        liveMgr2.recordWordTyped("meet me at galaxy")

        assertTrue("Personalization must be enabled when switch is turned on", repo.isPersonalizationEnabled)
        assertTrue("Stats must show learned items", (liveMgr2.getStats()["wordFreqCount"] ?: 0) > 0)

        // ── Test 3: Alphanumeric & Symbols switch disabled ──
        prefs["personalization_alphanumeric_enabled"] = false
        repo.personalizationAlphanumericEnabled = false
        liveMgr2.recordWordTyped("gr8")
        liveMgr2.recordWordTyped("wi-fi")
        assertFalse("Must not learn alphanumeric word when alphanumeric switch is disabled",
            repo.personalProfile.learnedOOV.contains("gr8"))
        assertFalse("Must not learn symbol-containing word when alphanumeric switch is disabled",
            repo.personalProfile.learnedOOV.contains("wi-fi"))

        // Alphanumeric & Symbols switch enabled
        prefs["personalization_alphanumeric_enabled"] = true
        repo.personalizationAlphanumericEnabled = true
        liveMgr2.recordWordTyped("gr8")
        liveMgr2.recordWordTyped("wi-fi")
        assertTrue("Must learn alphanumeric word when alphanumeric switch is enabled",
            repo.personalProfile.learnedOOV.contains("gr8"))
        assertTrue("Must learn symbol-containing word when alphanumeric switch is enabled",
            repo.personalProfile.learnedOOV.contains("wi-fi"))

        // ── Test 4: Word Pairs toggle in PersonalizationEngine ──
        repo.personalizationPairsEnabled = true
        repo.personalizationFreqEnabled = false
        val baseScoreWithPairs = scoringEngine.calculateScores("meet me at ")["g"] ?: 0.0

        repo.personalizationPairsEnabled = false
        val scoreWithoutPairs = scoringEngine.calculateScores("meet me at ")["g"] ?: 0.0
        assertTrue("Score with pairs enabled ($baseScoreWithPairs) must be greater than with pairs disabled ($scoreWithoutPairs)",
            baseScoreWithPairs > scoreWithoutPairs)

        // ── Test 5: Boost Multiplier scaling ──
        repo.personalizationPairsEnabled = true
        repo.personalizationBoostMultiplier = 1.0
        val score1x = scoringEngine.calculateScores("meet me at ")["g"] ?: 0.0

        repo.personalizationBoostMultiplier = 2.0
        val score2x = scoringEngine.calculateScores("meet me at ")["g"] ?: 0.0
        assertTrue("Score with 2.0x boost ($score2x) must be higher than 1.0x boost ($score1x)",
            score2x > score1x)

        // ── Test 6: Clear Profile ──
        liveMgr2.clearProfile()
        assertFalse("Personalization should be disabled after clearProfile", repo.isPersonalizationEnabled)
        assertEquals("Word frequency count must be 0 after clearProfile", 0, liveMgr2.getStats()["wordFreqCount"])
        assertEquals("OOV count must be 0 after clearProfile", 0, liveMgr2.getStats()["oovCount"])
    }

    @Test
    fun testSymbolWordsAndDigitLayoutRules() {
        val filesDir = tempFolder.newFolder("symbol_test")
        val prefs = mutableMapOf<String, Any>()
        prefs["personalization_enabled"] = true
        prefs["personalization_alphanumeric_enabled"] = true
        val mockContext = MockContext(filesDir, prefs)

        val liveMgr = LiveLearningManager(mockContext)
        liveMgr.loadProfile()

        // 1. Learn compound symbol word "wi-fi"
        liveMgr.recordWordTyped("connect")
        liveMgr.recordWordTyped("connect to")
        liveMgr.recordWordTyped("connect to wi-fi")
        liveMgr.recordWordTyped("connect to wi-fi")

        assertTrue("wi-fi should be learned as OOV or frequent word",
            repo.personalProfile.learnedOOV.contains("wi-fi") || repo.personalProfile.wordFreq.containsKey("wi-fi"))

        // 2. Type "wi" -> Personalization should score '-' (hyphen) and allow '-' on Key 4
        val scores = scoringEngine.calculateScores("wi")
        assertTrue("Score for '-' must be greater than 0.0", (scores["-"] ?: 0.0) > 0.0)

        val layout = layoutManager.assignLayout(scores)
        assertTrue("Key 4 should contain '-' or tap is '-'", layout["key_4"]?.tap == "-" || layout["key_4"]?.visibleChars()?.contains("-") == true)

        // 3. Test Word Prediction for "wi-" -> WordPredictionEngine should suggest "wi-fi"
        val wordPredictionEngine = com.flowboard.ime.engine.WordPredictionEngine(repo)
        val predictions = wordPredictionEngine.getPredictions("wi-")
        assertTrue("Predictions for 'wi-' should contain 'wi-fi'", predictions.contains("wi-fi"))

        // 4. Test Digits Layout Rule: Digits (0-9) MUST NEVER be given score bonuses or steal TAP
        liveMgr.recordWordTyped("see you b4 dinner")
        liveMgr.recordWordTyped("see you b4 dinner")

        val scoresAfterB = scoringEngine.calculateScores("b")
        assertNull("Score map must NOT contain digit '4'", scoresAfterB["4"])

        val layoutAfterB = layoutManager.assignLayout(scoresAfterB)
        assertNotEquals("TAP on Key 4 must NOT be digit '4'", "4", layoutAfterB["key_4"]?.tap)
        assertEquals("Swipe-down on Key 4 MUST always remain '4'", "4", layoutAfterB["key_4"]?.down)
    }

    @Test
    fun testEmailLearningScoringAndPrediction() {
        val filesDir = tempFolder.newFolder("files_email_test")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)
        liveMgr.loadProfile()

        val testEmail = "somchai.dev@gmail.com"

        // 1. User types email once in a sentence or field
        liveMgr.recordWordTyped("send report to $testEmail please")

        // Verify email was learned immediately on 1st occurrence
        assertTrue("Email $testEmail must be learned into learnedOOV",
            repo.personalProfile.learnedOOV.contains(testEmail))
        assertTrue("Email $testEmail frequency must be recorded",
            (repo.personalProfile.wordFreq[testEmail] ?: 0) >= 1)

        val wordPredictionEngine = com.flowboard.ime.engine.WordPredictionEngine(repo)

        // 2. Test Character Score Boosting (Requirement 1: ดันคะแนนตัว)
        // a. Typing "somchai" -> next char is '.'
        var scores = scoringEngine.calculateScores("somchai")
        assertTrue("Score for '.' must be boosted", (scores["."] ?: 0.0) > 0.0)

        // b. Typing "somchai.dev" -> next char is '@'
        scores = scoringEngine.calculateScores("somchai.dev")
        assertTrue("Score for '@' must be boosted", (scores["@"] ?: 0.0) > 0.0)
        var layout = layoutManager.assignLayout(scores)
        assertEquals("Character '@' must win Key 8 TAP slot", "@", layout["key_8"]?.tap)

        // c. Typing "somchai.dev@" -> next char is 'g'
        scores = scoringEngine.calculateScores("somchai.dev@")
        assertTrue("Score for 'g' must be boosted", (scores["g"] ?: 0.0) > 0.0)

        // d. Typing "somchai.dev@g" -> next char is 'm'
        scores = scoringEngine.calculateScores("somchai.dev@g")
        assertTrue("Score for 'm' must be boosted", (scores["m"] ?: 0.0) > 0.0)

        // e. Typing "somchai.dev@gmail" -> next char is '.'
        scores = scoringEngine.calculateScores("somchai.dev@gmail")
        assertTrue("Score for '.' must be boosted", (scores["."] ?: 0.0) > 0.0)

        // 3. Test Prediction Bar Recommendation (Requirement 2: ไม่แซงคำสั้น 1 ตัวอักษร แนะนำปกติเมื่อพิมพ์ >= 3 ตัวหรือมี @)
        // From 1-character prefix ("s"): should NOT prematurely force full email over top 1-char word completions
        var predictions1Char = wordPredictionEngine.getPredictions("s")
        assertNotEquals("Prediction bar should not force email at top for 1-char prefix 's'", testEmail, predictions1Char.firstOrNull())

        // From 3-character prefix ("som"):
        var predictions = wordPredictionEngine.getPredictions("som")
        assertTrue("Predictions for 'som' must contain $testEmail", predictions.contains(testEmail))
        assertEquals("Prediction bar top suggestion for 'som' must be $testEmail", testEmail, predictions.firstOrNull())

        // From middle prefix with @:
        predictions = wordPredictionEngine.getPredictions("somchai.dev@g")
        assertTrue("Predictions for 'somchai.dev@g' must contain $testEmail", predictions.contains(testEmail))
        assertEquals("Prediction bar top suggestion must be $testEmail", testEmail, predictions.firstOrNull())

        // From domain prefix:
        predictions = wordPredictionEngine.getPredictions("somchai.dev@gmail.")
        assertTrue("Predictions for 'somchai.dev@gmail.' must contain $testEmail", predictions.contains(testEmail))
        assertEquals("Prediction bar top suggestion must be $testEmail", testEmail, predictions.firstOrNull())
    }

    @Test
    fun testDisallowedSymbolsSplittingAndWordPairs() {
        val filesDir = tempFolder.newFolder("files_symbols_splitting")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)
        liveMgr.loadProfile()

        // 1. User types string with disallowed symbols like Apple2&GG# or Foo$Bar
        liveMgr.recordWordTyped("login with Apple2&GG# today")
        liveMgr.recordWordTyped("login with Apple2&GG# today")

        // Verify sub-words "apple2" and "gg" are learned
        assertTrue("Sub-word 'apple2' must be recorded in wordFreq", repo.personalProfile.wordFreq.containsKey("apple2"))
        assertTrue("Sub-word 'gg' must be recorded in wordFreq", repo.personalProfile.wordFreq.containsKey("gg"))

        // Verify disallowed symbols '&' and '#' are NOT in wordFreq or learnedOOV
        assertFalse("Disallowed symbol '&' must NOT be in wordFreq", repo.personalProfile.wordFreq.containsKey("&"))
        assertFalse("Disallowed symbol '#' must NOT be in wordFreq", repo.personalProfile.wordFreq.containsKey("#"))
        assertFalse("Combined symbol string must NOT be in learnedOOV", repo.personalProfile.learnedOOV.contains("apple2&gg#"))

        // Verify word pair bigram "apple2" -> "gg" was recorded
        assertTrue("Bigram must record 'apple2' -> 'gg'",
            repo.personalProfile.bigram["apple2"]?.containsKey("gg") == true)

        // 2. User types "apple2 " (space after apple2) -> next word is "gg", first char is 'g'
        scoringEngine.resetTrieCache()
        var scores = scoringEngine.calculateScores("apple2 ")
        assertTrue("Score for 'g' (char 1 of gg) must be boosted", (scores["g"] ?: 0.0) > 0.0)

        var layout = layoutManager.assignLayout(scores)
        assertEquals("Character 'g' must win TAP on key_6", "g", layout["key_6"]?.tap)

        // 3. User types "apple2&" (NO space, symbol '&' delimiter) -> next word is "gg", first char is 'g'
        scoringEngine.resetTrieCache()
        scores = scoringEngine.calculateScores("login with apple2&")
        assertTrue("Score for 'g' after symbol '&' delimiter must be boosted", (scores["g"] ?: 0.0) > 0.0)

        layout = layoutManager.assignLayout(scores)
        assertEquals("Character 'g' must win TAP on key_6 even after '&' symbol delimiter", "g", layout["key_6"]?.tap)

        val wordPredictionEngine = com.flowboard.ime.engine.WordPredictionEngine(repo)
        val predictionsAfterAmp = wordPredictionEngine.getPredictions("login with apple2&")
        assertTrue("Prediction after '&' delimiter must suggest 'gg'", predictionsAfterAmp.contains("gg"))

        val predictionsAfterAmpG = wordPredictionEngine.getPredictions("login with apple2&g")
        assertTrue("Prediction after '&g' must suggest 'gg'", predictionsAfterAmpG.contains("gg"))
    }

    @Test
    fun testPasswordLearningToggle() {
        val filesDir = tempFolder.newFolder("files_pwd_toggle")
        val prefsData = mutableMapOf<String, Any>()
        val mockContext = MockContext(filesDir, prefsData)
        val liveMgr = LiveLearningManager(mockContext)

        // Default: Learn Passwords is OFF (false)
        assertFalse("Default learn passwords should be disabled", liveMgr.isLearnPasswordsEnabled())

        // Enable Learn Passwords in settings
        prefsData["personalization_learn_passwords"] = true
        assertTrue("Learn passwords should now be enabled", liveMgr.isLearnPasswordsEnabled())

        // Learn words from a password-like combination
        liveMgr.recordWordTyped("secret Pass123&Word99# here")
        assertTrue("Pass123 should be learned when enabled", repo.personalProfile.wordFreq.containsKey("pass123"))
        assertTrue("Word99 should be learned when enabled", repo.personalProfile.wordFreq.containsKey("word99"))
        assertTrue("Bigram pass123 -> word99 should be recorded",
            repo.personalProfile.bigram["pass123"]?.containsKey("word99") == true)

        // Test real-time typing: typing "Pass123&" (NO space) must immediately boost 'w' for Word99
        scoringEngine.resetTrieCache()
        val scoresAfterPass = scoringEngine.calculateScores("Pass123&")
        assertTrue("Score for 'w' (char 1 of Word99) must be boosted after symbol '&'", (scoresAfterPass["w"] ?: 0.0) > 0.0)

        val layoutAfterPass = layoutManager.assignLayout(scoresAfterPass)
        assertEquals("Key 9 TAP must be 'w' after typing 'Pass123&'", "w", layoutAfterPass["key_9"]?.tap)

        val wordPredictionEngine = com.flowboard.ime.engine.WordPredictionEngine(repo)
        val predictions = wordPredictionEngine.getPredictions("Pass123&")
        assertTrue("Prediction bar must suggest 'word99' after 'Pass123&'", predictions.contains("word99"))

        val predictionsWithW = wordPredictionEngine.getPredictions("Pass123&w")
        assertTrue("Prediction bar must suggest 'word99' after 'Pass123&w'", predictionsWithW.contains("word99"))
        assertEquals("Prediction bar top suggestion for 'Pass123&w' must be 'word99'", "word99", predictionsWithW.firstOrNull())
    }

    @Test
    fun testUncommittedWordLearningOnKeyboardDismissAndFieldSwitch() {
        val filesDir = tempFolder.newFolder("files_dismiss_learn")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)
        liveMgr.loadProfile()

        // 1. User types in a single field without space, e.g. "myuncommittedword" and collapses keyboard
        liveMgr.recordWordTyped("myuncommittedword")
        liveMgr.saveProfileIfDirty()

        assertTrue("Uncommitted word without trailing space must be in wordFreq",
            repo.personalProfile.wordFreq.containsKey("myuncommittedword"))
        assertTrue("Uncommitted OOV word must be in learnedOOV",
            repo.personalProfile.learnedOOV.contains("myuncommittedword"))

        // 2. User types multi-word without trailing space in Field 1, e.g. "order from myfastshop" and switches to Field 2
        liveMgr.recordWordTyped("order from myfastshop")
        liveMgr.saveProfileIfDirty()

        assertTrue("Last word 'myfastshop' must be in wordFreq",
            repo.personalProfile.wordFreq.containsKey("myfastshop"))
        assertTrue("Bigram 'from' -> 'myfastshop' must be recorded",
            repo.personalProfile.bigram["from"]?.containsKey("myfastshop") == true)

        // 3. Verify real-time prediction works for the learned words
        val wordPredictionEngine = com.flowboard.ime.engine.WordPredictionEngine(repo)
        val predictions = wordPredictionEngine.getPredictions("order from myfast", 3)
        assertTrue("Prediction bar must suggest 'myfastshop'", predictions.contains("myfastshop"))
    }

    @Test
    fun testLogarithmicScalingAndAgingDecay() {
        val filesDir = tempFolder.newFolder("files_decay_test")
        val mockContext = MockContext(filesDir)
        val liveMgr = LiveLearningManager(mockContext)
        liveMgr.loadProfile()

        // 1. Logarithmic Scaling Test: count 50 vs count 5
        for (i in 0 until 50) {
            liveMgr.recordWordTyped("apple2")
        }
        for (i in 0 until 5) {
            liveMgr.recordWordTyped("banana2")
        }

        val countApple = repo.personalProfile.wordFreq["apple2"] ?: 0
        val countBanana = repo.personalProfile.wordFreq["banana2"] ?: 0
        assertTrue("Apple2 count should be >= 50", countApple >= 50)
        assertTrue("Banana2 count should be 5", countBanana == 5)

        scoringEngine.resetTrieCache()
        val scoresApple = scoringEngine.calculateScores("appl")
        val scoresBanana = scoringEngine.calculateScores("bana")
        val appleBonus = scoresApple["e"] ?: 0.0
        val bananaBonus = scoresBanana["n"] ?: 0.0
        assertTrue("Logarithmic scaling ensures 50x word gets higher bonus than 5x word",
            appleBonus > bananaBonus)

        // 2. Aging Decay Test
        liveMgr.recordWordTyped("onceonlyword")
        assertTrue("onceonlyword should be recorded", repo.personalProfile.wordFreq.containsKey("onceonlyword"))

        // Apply aging decay step with 0.4 decay rate
        liveMgr.applyAgingDecay(0.4)

        // rareWord with count=1 * 0.4 = 0.4 -> rounded to 0 -> should be pruned
        assertFalse("Rare word decayed to 0 must be removed from wordFreq",
            repo.personalProfile.wordFreq.containsKey("onceonlyword"))
        assertFalse("Rare word decayed to 0 must be removed from learnedOOV",
            repo.personalProfile.learnedOOV.contains("onceonlyword"))

        // Active high frequency words decayed gracefully (50 * 0.4 = 20)
        val newAppleCount = repo.personalProfile.wordFreq["apple2"] ?: 0
        assertTrue("Apple2 count must decay gracefully (50 * 0.4 = 20)", newAppleCount in 18..22)
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
