package com.flowboard.ime.testing

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.engine.LayoutManager
import com.flowboard.ime.engine.ScoringEngine
import org.junit.Before
import org.junit.Test
import java.util.Locale

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
    fun testBotPerformance() {
        // Run LETTERS ONLY evaluation mode
        val statsLetters = botTester.runTest(testSentences, evalMode = BotTester.EvalMode.LETTERS)
        printReport("FLOWBOARD BENCHMARK REPORT (LETTERS ONLY)", statsLetters)

        println("\n" + "=".repeat(45) + "\n")

        // Run FULL INPUT evaluation mode
        val statsFull = botTester.runTest(testSentences, evalMode = BotTester.EvalMode.FULL)
        printReport("FLOWBOARD BENCHMARK REPORT (FULL INPUT)", statsFull)
    }

    private fun printReport(header: String, stats: BotTester.BotStats) {
        println("=========================================")
        println("   $header   ")
        println("=========================================")
        println("Total Characters Tested: ${stats.totalChars}")
        println(String.format(Locale.US, "Taps  : %d (%.1f%%)", stats.taps, stats.tapPercent))
        println(String.format(Locale.US, "Swipes: %d (%.1f%%)", stats.swipes, stats.swipePercent))
        println(String.format(Locale.US, "Misses: %d (%.1f%%)", stats.misses, stats.missPercent))
        println("-----------------------------------------")
        println("Engine Breakdown:")
        stats.engineStats.forEach { (engine, stat) ->
            val engineTapPct = if (stat.total > 0) (stat.taps.toDouble() / stat.total) * 100.0 else 0.0
            println(String.format(Locale.US, "  %s -> Taps: %d/%d (%.1f%%)", engine, stat.taps, stat.total, engineTapPct))
        }
        println("=========================================")
    }
}
