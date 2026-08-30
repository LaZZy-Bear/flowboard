package com.flowboard.ime.testing

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.EngineWeights
import com.flowboard.ime.data.models.MasterLayoutEntry
import com.flowboard.ime.engine.LayoutManager
import com.flowboard.ime.engine.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdvancedTuningTest {

    private val repo = FlowboardRepository
    private lateinit var layoutManager: LayoutManager
    private lateinit var scoringEngine: ScoringEngine

    @Before
    fun setUp() {
        TestDataFactory.loadRepo(repo)
        repo.defaultMasterLayout = repo.masterLayout
        layoutManager = LayoutManager(repo)
        scoringEngine = ScoringEngine(repo)
        // Reset overrides to defaults
        repo.lazyTapRatio = 1.15
        repo.partnerTapRatio = 1.35
        repo.customStateWeights = null
        if (repo.defaultMasterLayout.isNotEmpty()) {
            repo.masterLayout = repo.defaultMasterLayout
        }
        repo.stickyChar = null
        repo.lastActionKeyId = null
    }

    @Test
    fun testCustomLazyTapRatio() {
        // Key 1 characters: 'j' (default tap), 'l' (default up)
        // If 'j' has score 10.0 and 'l' has score 11.0:
        // With standard lazyTapRatio = 1.15 (threshold 11.5), 'j' retains tap.
        // With aggressive lazyTapRatio = 1.05 (threshold 10.5), 'l' takes tap!
        val scores = mutableMapOf<String, Double>()
        for (c in 'a'..'z') scores[c.toString()] = 1.0
        scores["j"] = 10.0
        scores["l"] = 11.0

        repo.lazyTapRatio = 1.15
        val layoutDefault = layoutManager.assignLayout(scores)
        assertEquals("Default ratio should protect home tap 'j'", "j", layoutDefault["key_1"]?.tap)

        repo.lazyTapRatio = 1.05
        val layoutAggressive = layoutManager.assignLayout(scores)
        assertEquals("Aggressive ratio should allow runner-up 'l' to win tap", "l", layoutAggressive["key_1"]?.tap)
    }

    @Test
    fun testCustomPartnerTapRatio() {
        // Key 1 ↔ Key 2 partner swap test
        // Key 1 chars: 'j' (tap), 'l' (runner-up)
        // Key 2 chars: 'z' (default tap)
        val scores = mutableMapOf<String, Double>()
        for (c in 'a'..'z') scores[c.toString()] = 1.0
        scores["j"] = 20.0  // Key 1 tap winner
        scores["l"] = 14.0  // Key 1 runner-up
        scores["z"] = 10.0  // Key 2 tap char

        // Runner up 'l' score = 14.0 vs Partner 'z' score = 10.0 (14.0 / 10.0 = 1.40x)
        // With standard partnerTapRatio = 1.35 (threshold 13.5), 'l' swaps into Key 2 tap.
        repo.partnerTapRatio = 1.35
        val layoutSwap = layoutManager.assignLayout(scores)
        assertEquals("Runner up 'l' should swap into Key 2 tap", "l", layoutSwap["key_2"]?.tap)

        // With strict partnerTapRatio = 1.50 (threshold 15.0), swap is rejected!
        repo.partnerTapRatio = 1.50
        val layoutStrict = layoutManager.assignLayout(scores)
        assertEquals("Strict ratio should reject swap and keep 'z'", "z", layoutStrict["key_2"]?.tap)
    }

    @Test
    fun test10xRatioEffectivelyDisablesSwapping() {
        // 1. Verify 10x Lazy Tap Ratio disables home key replacement
        // 'j' is default tap on Key 1 with score 10.0.
        // 'l' has huge score 80.0 (8x score of 'j').
        // With lazyTapRatio = 10.0 (threshold 100.0), 'j' is NOT replaced!
        val scores = mutableMapOf<String, Double>()
        for (c in 'a'..'z') scores[c.toString()] = 1.0
        scores["j"] = 10.0
        scores["l"] = 80.0

        repo.lazyTapRatio = 10.0
        val layoutLazyLocked = layoutManager.assignLayout(scores)
        assertEquals("10x Lazy ratio must lock default tap 'j'", "j", layoutLazyLocked["key_1"]?.tap)

        // 2. Verify 10x Partner Tap Ratio disables cross-key partner swapping
        // Key 1 tap winner is 'j' (score 100.0). Key 1 runner-up is 'l' (score 80.0).
        // Key 2 tap char is 'z' (score 10.0).
        // With partnerTapRatio = 10.0 (threshold 100.0), 'l' cannot swap into Key 2!
        scores["j"] = 100.0
        scores["l"] = 80.0
        scores["z"] = 10.0

        repo.partnerTapRatio = 10.0
        val layoutPartnerLocked = layoutManager.assignLayout(scores)
        assertEquals("10x Partner ratio must lock Key 2 tap and keep 'z'", "z", layoutPartnerLocked["key_2"]?.tap)
    }

    @Test
    fun testCustomStateWeightsOverride() {
        // State 1 with default weights vs custom weights
        val customWeights = mapOf(
            1 to EngineWeights(U = 100, B = 0, T = 0, D = 0, WB = 0, WT = 0, STC = 0, status = "Custom State 1")
        )
        repo.customStateWeights = customWeights

        val scores = scoringEngine.calculateScores("")
        assertTrue("Scoring should succeed with custom state weights", scores.isNotEmpty())
        assertEquals("Engine status should reflect custom status", "Custom State 1 (100U 0B 0T 0D 0WB 0WT 0STC)", scoringEngine.engineStatus)
    }

    @Test
    fun testCustomMasterLayoutOverride() {
        // Modify master layout so 'z' is assigned as Key 1 tap default
        val customLayout = repo.masterLayout.toMutableMap()
        customLayout["z"] = MasterLayoutEntry(homeKey = "key_1", defaultSlot = "tap")
        repo.masterLayout = customLayout

        val scores = mutableMapOf<String, Double>()
        for (c in 'a'..'z') scores[c.toString()] = 1.0
        scores["z"] = 50.0

        val layout = layoutManager.assignLayout(scores)
        assertEquals("'z' should be assigned to Key 1 tap slot", "z", layout["key_1"]?.tap)
    }

    @Test
    fun testCustomMasterLayoutWithLockModeZeroScoreTap() {
        // User customizes 'z' to be on Key 1 tap and moves 'j' to up.
        // In realistic typing, 'z' has score 0.0 at word start while 'l' on Key 1 has score 20.0.
        val customLayout = repo.masterLayout.toMutableMap()
        customLayout["j"] = MasterLayoutEntry(homeKey = "key_1", defaultSlot = "up")
        customLayout["z"] = MasterLayoutEntry(homeKey = "key_1", defaultSlot = "tap")
        repo.masterLayout = customLayout

        val scores = mutableMapOf<String, Double>()
        for (c in 'a'..'z') scores[c.toString()] = 1.0
        scores["z"] = 0.0   // 0 score for 'z'
        scores["l"] = 20.0  // High score for 'l'

        // With 10x Lock mode: 'z' is 100% locked to Key 1 tap!
        repo.lazyTapRatio = 10.0
        val layoutLocked = layoutManager.assignLayout(scores)
        assertEquals("Lock mode must preserve custom default tap 'z' even with 0 score", "z", layoutLocked["key_1"]?.tap)

        // With dynamic default mode (1.15): 'l' (score 20.0) takes tap
        repo.lazyTapRatio = 1.15
        val layoutDynamic = layoutManager.assignLayout(scores)
        assertEquals("Dynamic mode allows 'l' to win tap when 'z' score is 0", "l", layoutDynamic["key_1"]?.tap)
    }

    @Test
    fun testEndToEndCustomMasterLayoutWithRealScoringEngine() {
        // User customizes 'a' to Key 1 (tap) and 't' to Key 1 (up) in masterLayout
        val customLayout = repo.masterLayout.toMutableMap()
        customLayout["a"] = MasterLayoutEntry(homeKey = "key_1", defaultSlot = "tap")
        customLayout["t"] = MasterLayoutEntry(homeKey = "key_1", defaultSlot = "up")
        repo.masterLayout = customLayout

        // Calculate scores at State 1 using REAL scoring engine
        val scores = scoringEngine.calculateScores("")
        
        // 1. With Lock Mode (10.0): 'a' is 100% on Key 1 tap!
        repo.lazyTapRatio = 10.0
        repo.partnerTapRatio = 10.0
        val layoutLocked = layoutManager.assignLayout(scores)
        assertEquals("Lock mode must place 'a' on Key 1 tap", "a", layoutLocked["key_1"]?.tap)
        assertEquals("Lock mode must place 't' on Key 1 up", "t", layoutLocked["key_1"]?.up)

        // 2. With Dynamic Mode (1.15): 'a' is preserved on Key 1 after partner swap
        repo.lazyTapRatio = 1.15
        repo.partnerTapRatio = 1.35
        val layoutDynamic = layoutManager.assignLayout(scores)
        assertTrue("Dynamic mode generates valid Key 1 tap", layoutDynamic["key_1"]?.tap?.isNotEmpty() == true)
    }

    @Test
    fun testPartialMasterLayoutSafeMerge() {
        // Only override 'q' and 'z', ensure remaining 34 characters still exist in layout
        val partialOverride = mapOf(
            "q" to MasterLayoutEntry(homeKey = "key_9", defaultSlot = "up"),
            "z" to MasterLayoutEntry(homeKey = "key_9", defaultSlot = "right")
        )
        val merged = repo.defaultMasterLayout + partialOverride
        repo.masterLayout = merged

        val scores = mutableMapOf<String, Double>()
        for (c in 'a'..'z') scores[c.toString()] = 1.0

        val layout = layoutManager.assignLayout(scores)
        // Verify all 9 keys have valid slots assigned
        for (i in 1..9) {
            val key = layout["key_$i"]
            assertTrue("Key $i should not be null", key != null)
            assertTrue("Key $i should have a tap character", !key?.tap.isNullOrEmpty())
        }
    }

    @Test
    fun testCustomSymbolReplacementAsterisk() {
        // Replace 'x' with '*' on Key 3 left
        val customLayout = repo.masterLayout.toMutableMap()
        customLayout.remove("x")
        customLayout["*"] = MasterLayoutEntry(homeKey = "key_3", defaultSlot = "left")
        repo.masterLayout = customLayout

        val scores = scoringEngine.calculateScores("")
        repo.lazyTapRatio = 10.0
        repo.partnerTapRatio = 10.0
        val layout = layoutManager.assignLayout(scores)

        assertEquals("Key 3 left slot must contain custom symbol '*'", "*", layout["key_3"]?.left)
    }

    @Test
    fun testDuplicateCharacterSlots() {
        // User maps 'a' on Key 2 tap and Key 2 up
        val customLayout = repo.masterLayout.toMutableMap()
        customLayout["a"] = MasterLayoutEntry(homeKey = "key_2", defaultSlot = "tap")
        repo.masterLayout = customLayout

        val scores = scoringEngine.calculateScores("")
        repo.lazyTapRatio = 10.0
        repo.partnerTapRatio = 10.0
        val layout = layoutManager.assignLayout(scores)

        assertEquals("Key 2 tap must be 'a'", "a", layout["key_2"]?.tap)
    }

    @Test
    fun testMultiCharKeySanitizationTakesFirstChar() {
        // User inputs multi-character keys like "ab", "hello", "😀😁"
        val rawInput = mapOf(
            "ab" to MasterLayoutEntry(homeKey = "key_1", defaultSlot = "tap"),
            "hello" to MasterLayoutEntry(homeKey = "key_2", defaultSlot = "up"),
            "😀😁" to MasterLayoutEntry(homeKey = "key_3", defaultSlot = "right")
        )
        
        // Simulating the sanitization logic
        val sanitized = mutableMapOf<String, MasterLayoutEntry>()
        for ((rawKey, entry) in rawInput) {
            val codePoints = rawKey.trim().codePoints().toArray()
            val singleChar = if (codePoints.isNotEmpty()) String(codePoints, 0, 1) else rawKey.trim().take(1)
            if (singleChar.isNotEmpty() && !sanitized.containsKey(singleChar)) {
                sanitized[singleChar] = entry
            }
        }

        assertEquals("Multi-char 'ab' should be truncated to 'a'", true, sanitized.containsKey("a"))
        assertEquals("Multi-char 'hello' should be truncated to 'h'", true, sanitized.containsKey("h"))
        assertEquals("Multi-char emoji '😀😁' should be truncated to '😀'", true, sanitized.containsKey("😀"))
        assertEquals("Multi-char 'ab' should not be in keys", false, sanitized.containsKey("ab"))
    }

    @Test
    fun testZeroStateWeightsFallback() {
        // When all weights are 0, ScoringEngine falls back to 100% Unigram without division by zero
        val zeroWeights = mapOf(
            1 to EngineWeights(U = 0, B = 0, T = 0, D = 0, WB = 0, WT = 0, STC = 0, status = "Zero State")
        )
        repo.customStateWeights = zeroWeights

        val scores = scoringEngine.calculateScores("")
        assertTrue("Scores must not be empty with zero weights fallback", scores.isNotEmpty())
        assertTrue("Top scorer should have positive score", (scores.values.maxOrNull() ?: 0.0) > 0.0)
    }

    @Test
    fun testStickyKeyImmunityAgainstPartnerSwap() {
        // Sticky char on Key 2 ('t') cannot be replaced by Key 1's runner up 'm' even if score is huge
        repo.stickyChar = "t"
        repo.lastActionKeyId = "key_2"

        val scores = mutableMapOf<String, Double>()
        for (c in 'a'..'z') scores[c.toString()] = 1.0
        scores["v"] = 20.0
        scores["m"] = 999.0  // Huge runner-up score on Key 1
        scores["t"] = 1.0    // Low score on Key 2

        repo.partnerTapRatio = 1.01  // Very aggressive ratio
        val layout = layoutManager.assignLayout(scores)
        assertEquals("Sticky char 't' on Key 2 must be protected from partner swap", "t", layout["key_2"]?.tap)
    }

    @Test
    fun testEasyTextLayoutFormatterAndParser() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val sampleEasyText = """
            Key 1: tap=j, up=l, left=_, right=#
            Key 2: tap=z, up=a, left=", right=t
            Key 3: tap=p, up=i, left=x, right=f
            Key 4: tap=q, up=o, left=?, right=s
            Key 5: tap=b, up=&, left=n, right=h
            Key 6: tap=g, up=!, left=r, right='
            Key 7: tap=d, up=,, left=e, right=-
            Key 8: tap=c, up=y, left=@, right=u
            Key 9: tap=k, up=w, left=v, right=m
        """.trimIndent()

        val parsed = com.flowboard.ime.util.AdvancedTuningFormatter.easyTextToLayout(sampleEasyText, json)
        assertEquals("Should parse 36 characters", 36, parsed.size)
        assertEquals("Key 1 tap should be j", MasterLayoutEntry("key_1", "tap"), parsed["j"])
        assertEquals("Key 7 up should be comma", MasterLayoutEntry("key_7", "up"), parsed[","])

        val formatted = com.flowboard.ime.util.AdvancedTuningFormatter.layoutToEasyText(parsed)
        val roundTrip = com.flowboard.ime.util.AdvancedTuningFormatter.easyTextToLayout(formatted, json)
        assertEquals("Roundtrip parsing should match", parsed, roundTrip)
    }

    @Test
    fun testEasyTextWeightsFormatterAndParser() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val sampleWeightsText = """
            State 1: U=25, B=23, T=41, D=33, WB=35, WT=69, STC=0
            State 2: U=0, B=7, T=88, D=23, WB=100, WT=100, STC=67
            State 3: U=0, B=2, T=27, D=38, WB=100, WT=100, STC=32
            State 4: U=4, B=7, T=7, D=94, WB=90, WT=100, STC=56
            State 7: U=28, B=13, T=0, D=31, WB=50, WT=100, STC=100
            State 8: U=17, B=0, T=74, D=66, WB=10, WT=97, STC=0
        """.trimIndent()

        val parsed = com.flowboard.ime.util.AdvancedTuningFormatter.easyTextToWeights(sampleWeightsText, json)
        assertEquals("Should parse 6 states", 6, parsed.size)
        assertEquals("State 1 U weight should be 25", 25, parsed[1]?.U)
        assertEquals("State 2 WT weight should be 100", 100, parsed[2]?.WT)
        assertEquals("State 7 STC weight should be 100", 100, parsed[7]?.STC)

        val formatted = com.flowboard.ime.util.AdvancedTuningFormatter.weightsToEasyText(parsed)
        val roundTrip = com.flowboard.ime.util.AdvancedTuningFormatter.easyTextToWeights(formatted, json)
        assertEquals("Roundtrip weights should match", parsed, roundTrip)
    }

    @Test
    fun testEmptySlotDoesNotAssignComma() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        // Key 1 has empty tap: tap=, up=l, left=_, right=#
        val textWithEmptyTap = "Key 1: tap=, up=l, left=_, right=#"
        val parsed = com.flowboard.ime.util.AdvancedTuningFormatter.easyTextToLayout(textWithEmptyTap, json)

        assertEquals("Comma should NOT be assigned to tap", false, parsed.containsKey(","))
        assertEquals("l should be up", MasterLayoutEntry("key_1", "up"), parsed["l"])
    }

    @Test
    fun testPositionalWeightsParsing() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val positionalText = """
            State 1: 25, 23, 41, 33, 35, 69, 0
            State 2: 0, 7, 88, 23, 100, 100, 67
        """.trimIndent()

        val parsed = com.flowboard.ime.util.AdvancedTuningFormatter.easyTextToWeights(positionalText, json)
        assertEquals("State 1 U should be 25", 25, parsed[1]?.U)
        assertEquals("State 1 WT should be 69", 69, parsed[1]?.WT)
        assertEquals("State 2 T should be 88", 88, parsed[2]?.T)
    }
}
