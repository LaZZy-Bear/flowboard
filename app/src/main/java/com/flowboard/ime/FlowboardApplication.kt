package com.flowboard.ime

import android.app.Application
import android.util.Log
import com.flowboard.ime.data.AssetLoader
import com.flowboard.ime.data.FlowboardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class for Flowboard.
 *
 * Responsible for orchestrating the 3-phase data loading sequence:
 * 1. **Phase A (Critical)**: Loads small essential files so the keyboard
 *    can render immediately with Unigram-only predictions.
 * 2. **Phase B (Normal)**: Loads medium-sized files (bigram, trie, etc.)
 *    to enable dictionary-based predictions.
 * 3. **Phase C (Deferred)**: Loads large files (trigram 2.5MB, hybrid_word_trie 17MB)
 *    in the background for full prediction accuracy.
 *
 * The keyboard is usable after Phase A completes (~20ms).
 * Full prediction accuracy is available after Phase C completes.
 */
class FlowboardApplication : Application() {

    companion object {
        private const val TAG = "FlowboardApp"
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application onCreate — starting data loading pipeline")

        val assetLoader = AssetLoader(this)
        val repo = FlowboardRepository

        appScope.launch {
            try {
                // Phase A: Critical data (must complete before keyboard renders)
                assetLoader.loadCriticalData(repo)
                repo.markReady()
                Log.i(TAG, "✅ Phase A complete — keyboard is ready to render")

                // Phase B: Normal data (enhances predictions while user types)
                assetLoader.loadNormalData(repo)
                Log.i(TAG, "✅ Phase B complete — dictionary predictions active")

                // Phase C: Deferred data (large files, full accuracy)
                assetLoader.loadDeferredData(repo)
                repo.markFullyLoaded()
                Log.i(TAG, "✅ Phase C complete — all engines at full power")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Data loading failed: ${e.message}", e)
            }
        }
    }
}
