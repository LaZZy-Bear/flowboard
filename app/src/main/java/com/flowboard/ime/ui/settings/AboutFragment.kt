package com.flowboard.ime.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.flowboard.ime.MainActivity
import com.flowboard.ime.R
import com.flowboard.ime.ui.onboarding.OnboardingFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = activity as? MainActivity ?: return

        // Set Toolbar Title with Back Button enabled
        mainActivity.setToolbarTitle(getString(R.string.about_title), true)

        // Bind Version Info dynamically via PackageManager
        val tvVersion = view.findViewById<TextView>(R.id.tvAboutVersion)
        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val vName = pInfo.versionName ?: "1.0.0"
            val vCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
            tvVersion?.text = getString(R.string.about_version_format, vName, vCode)
        } catch (_: Exception) {
            tvVersion?.text = getString(R.string.about_version_format, "1.0.0", 1)
        }

        // 5-Tap Developer Mode Activation
        val prefs = requireContext().getSharedPreferences("flowboard_settings", android.content.Context.MODE_PRIVATE)
        var iconTapCount = 0
        var lastIconTapTime = 0L

        val onIconClicked: (View) -> Unit = {
            val now = System.currentTimeMillis()
            if (now - lastIconTapTime > 2500) {
                iconTapCount = 0
            }
            lastIconTapTime = now
            iconTapCount++

            val isAlreadyUnlocked = prefs.getBoolean("developer_options_unlocked", false)
            if (isAlreadyUnlocked) {
                Toast.makeText(requireContext(), "Developer Tuning options are active in Settings!", Toast.LENGTH_SHORT).show()
            } else if (iconTapCount in 2..4) {
                val remaining = 5 - iconTapCount
                Toast.makeText(requireContext(), "Tap $remaining more times to unlock Developer Tuning", Toast.LENGTH_SHORT).show()
            } else if (iconTapCount >= 5) {
                prefs.edit { putBoolean("developer_options_unlocked", true) }
                Toast.makeText(requireContext(), "🎉 Developer Tuning Unlocked! Check Keyboard Settings.", Toast.LENGTH_LONG).show()
                iconTapCount = 0
            }
        }

        view.findViewById<View>(R.id.cardAboutAppIcon)?.setOnClickListener(onIconClicked)
        view.findViewById<View>(R.id.ivAboutAppIcon)?.setOnClickListener(onIconClicked)

        // GitHub Link
        view.findViewById<View>(R.id.btnGitHub)?.setOnClickListener {
            val url = "https://github.com/LaZZy-Bear/flowboard"
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(requireContext(), url, Toast.LENGTH_SHORT).show()
            }
        }

        // Feedback & Support Email
        view.findViewById<View>(R.id.btnFeedback)?.setOnClickListener {
            val email = "sathit.imdev@gmail.com"
            try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = "mailto:$email".toUri()
                    putExtra(Intent.EXTRA_SUBJECT, "Flowboard Keyboard Feedback")
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(requireContext(), email, Toast.LENGTH_SHORT).show()
            }
        }

        // Open-Source Licenses Dialog
        view.findViewById<View>(R.id.btnLicenses)?.setOnClickListener {
            showLicensesDialog()
        }

        // Replay Setup Wizard
        view.findViewById<View>(R.id.btnReplayWizard)?.setOnClickListener {
            mainActivity.navigateToFragment(OnboardingFragment())
        }
    }

    private fun showLicensesDialog() {
        val licenses = """
            • Kotlin Standard Library & Coroutines (Apache 2.0)
            • Kotlinx Serialization (Apache 2.0)
            • AndroidX Core, AppCompat & Lifecycle (Apache 2.0)
            • Google Material Components for Android (Apache 2.0)
        """.trimIndent()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.btn_licenses)
            .setMessage(licenses)
            .setPositiveButton("Close", null)
            .show()
    }
}
