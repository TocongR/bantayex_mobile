package com.example.bantayex.features.student.result

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.bantayex.R
import com.example.bantayex.data.ViolationLabels
import com.example.bantayex.data.model.Violation
import com.example.bantayex.databinding.ActivityResultBinding
import com.example.bantayex.features.welcome.WelcomeActivity

/**
 * No ViewModel here on purpose — every value on this screen comes from Intent extras
 * set by TakeExamActivity after a successful submit. There's no Firestore call, no
 * business logic, nothing to survive a rotation that isn't already in the Intent.
 * A ViewModel that just re-read the same Intent extras would be an empty wrapper.
 *
 * BUG FIX: "Done" used to navigate to LoginActivity — the PROFESSOR login screen.
 * Students never authenticate via Firebase Auth in this app (they just enter an exam
 * code), so a student finishing an exam would land on a login form meant for
 * professors. Now returns to WelcomeActivity instead.
 */
class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val score = intent.getIntExtra("score", 0)
        val total = intent.getIntExtra("total", 0)
        val passed = intent.getBooleanExtra("passed", false)
        val studentName = intent.getStringExtra("studentName") ?: ""
        val examTitle = intent.getStringExtra("examTitle") ?: ""
        @Suppress("UNCHECKED_CAST")
        val violationLog = intent.getSerializableExtra("violationLog") as? ArrayList<Violation> ?: arrayListOf()

        val percent = if (total > 0) (score * 100 / total) else 0

        binding.tvStudentName.text = studentName
        binding.tvExamTitle.text = examTitle
        binding.tvScore.text = "$score / $total"
        binding.tvPercent.text = "$percent%"
        binding.tvStatus.text = if (passed) "PASSED" else "FAILED"
        binding.tvStatus.setTextColor(
            if (passed) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )

        setupViolationBreakdown(violationLog)

        binding.btnDone.setOnClickListener {
            val intent = Intent(this, WelcomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun setupViolationBreakdown(violationLog: List<Violation>) {
        if (violationLog.isEmpty()) {
            binding.violationsSection.visibility = View.GONE
            return
        }

        binding.violationsSection.visibility = View.VISIBLE
        binding.tvViolationTotal.text = "${violationLog.size} violation(s) logged"

        val counts = violationLog.groupingBy { it.type }.eachCount()
        val dp = resources.displayMetrics.density

        binding.violationsContainer.removeAllViews()
        for ((type, count) in counts) {
            val label = ViolationLabels.labelFor(type)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * dp).toInt() }
            }

            val labelView = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val countView = TextView(this).apply {
                text = "×$count"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(android.R.color.holo_red_dark))
            }

            row.addView(labelView)
            row.addView(countView)
            binding.violationsContainer.addView(row)
        }
    }
}