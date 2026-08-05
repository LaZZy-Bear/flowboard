package com.flowboard.ime.testing

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.engine.LayoutManager
import com.flowboard.ime.engine.ScoringEngine
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Automated accuracy and Tap Rate benchmark test for Prototype 22 English core engine.
 * Tests both FULL input mode and LETTERS only mode, matching JS bot.js.
 */
class BotTesterTest {

    private val repo = FlowboardRepository
    private lateinit var scoringEngine: ScoringEngine
    private lateinit var layoutManager: LayoutManager
    private lateinit var botTester: BotTester

    private val testSentences = listOf(
        "hello how are you doing today",
        "let me know when you arrive at the station",
        "the quick brown fox jumps over the lazy dog",
        "sounds good see you later",
        "make sure you check your email",
        "what time is the meeting tomorrow morning",
        "i am on my way right now",
        "thanks a lot for your help",
        "this is working really well",
        "see you on sunday"
    )

    @Before
    fun setup() {
        TestDataFactory.loadRepo(repo)
        scoringEngine = ScoringEngine(repo)
        layoutManager = LayoutManager(repo)
        botTester = BotTester(repo, scoringEngine, layoutManager)
    }

    @Test
    fun `run benchmark in FULL mode (matches web UI Full Input)`() {
        val stats = botTester.runTest(testSentences, BotTester.EvalMode.FULL)

        println("\n=========================================")
        println("   FLOWBOARD P22 REPORT (FULL INPUT)     ")
        println("=========================================")
        println("Total Characters Tested: ${stats.totalChars}")
        println("Taps  : ${stats.taps} (${String.format("%.1f", stats.tapPercent)}%)")
        println("Swipes: ${stats.swipes} (${String.format("%.1f", stats.swipePercent)}%)")
        println("Misses: ${stats.misses} (${String.format("%.1f", stats.missPercent)}%)")
        println("-----------------------------------------")
        println("Engine Breakdown:")
        for ((engine, eStat) in stats.engineStats) {
            val total = eStat.taps + eStat.swipes + eStat.misses
            val tapPct = if (total > 0) (eStat.taps.toDouble() / total) * 100.0 else 0.0
            println("  $engine -> Taps: ${eStat.taps}/$total (${String.format("%.1f", tapPct)}%)")
        }
        println("=========================================\n")

        assertTrue("FULL Tap Rate should be >= 88.0%, actual: ${stats.tapPercent}%", stats.tapPercent >= 88.0)
    }

    @Test
    fun `run benchmark in LETTERS mode (a-z and single quote only)`() {
        val stats = botTester.runTest(testSentences, BotTester.EvalMode.LETTERS)

        println("\n=========================================")
        println("   FLOWBOARD P22 REPORT (LETTERS ONLY)   ")
        println("=========================================")
        println("Total Characters Tested: ${stats.totalChars}")
        println("Taps  : ${stats.taps} (${String.format("%.1f", stats.tapPercent)}%)")
        println("Swipes: ${stats.swipes} (${String.format("%.1f", stats.swipePercent)}%)")
        println("Misses: ${stats.misses} (${String.format("%.1f", stats.missPercent)}%)")
        println("-----------------------------------------")
        println("Engine Breakdown:")
        for ((engine, eStat) in stats.engineStats) {
            val total = eStat.taps + eStat.swipes + eStat.misses
            val tapPct = if (total > 0) (eStat.taps.toDouble() / total) * 100.0 else 0.0
            println("  $engine -> Taps: ${eStat.taps}/$total (${String.format("%.1f", tapPct)}%)")
        }
        println("=========================================\n")

        assertTrue("LETTERS Tap Rate should be >= 85.0%, actual: ${stats.tapPercent}%", stats.tapPercent >= 85.0)
    }
}
