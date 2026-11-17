package com.example.bantayex.features.professor.results

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bantayex.core.util.Result
import com.example.bantayex.core.util.UiState
import com.example.bantayex.data.ProofImageCodec
import com.example.bantayex.data.ViolationLabels
import com.example.bantayex.data.model.ExamResult
import com.example.bantayex.databinding.ActivityExamResultsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class ExamResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamResultsBinding
    private val viewModel: ExamResultsViewModel by viewModels()
    private lateinit var resultsAdapter: ResultsListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val examCode = intent.getStringExtra("examCode") ?: ""
        val examTitle = intent.getStringExtra("examTitle") ?: ""

        binding.tvExamTitle.text = examTitle
        binding.tvExamCode.text = "Code: $examCode"

        setupRecyclerView()
        binding.btnBack.setOnClickListener { finish() }

        observeUiState()
        viewModel.loadResults(examCode)
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is UiState.Loading -> binding.progressBar.visibility = View.VISIBLE
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            renderResults(state.data)
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(this@ExamResultsActivity, state.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun renderResults(results: List<ExamResult>) {
        binding.tvTotalStudents.text = "Total students: ${results.size}"
        if (results.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvResults.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvResults.visibility = View.VISIBLE
            resultsAdapter.submitList(results)
        }
    }

    private fun setupRecyclerView() {
        resultsAdapter = ResultsListAdapter(onViewEvidence = { showEvidence(it) })
        binding.rvResults.apply {
            layoutManager = LinearLayoutManager(this@ExamResultsActivity)
            adapter = resultsAdapter
        }
    }

    // ── Evidence viewer ──────────────────────────────────────────────────────

    private fun showEvidence(result: ExamResult) {
        val ids = result.violationLog.map { it.proofId }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return

        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val status = TextView(this).apply { text = "Loading evidence…" }
        content.addView(status)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Evidence — ${result.studentName}")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Close", null)
            .show()

        lifecycleScope.launch {
            when (val outcome = viewModel.fetchProofs(ids)) {
                is Result.Error -> status.text = outcome.exception.message ?: "Couldn't load evidence."
                is Result.Success -> {
                    content.removeAllViews()
                    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                    outcome.data.forEach { proof ->
                        val bmp = proof.image?.toBytes()?.let { ProofImageCodec.decode(it) } ?: return@forEach

                        content.addView(TextView(this@ExamResultsActivity).apply {
                            text = "${ViolationLabels.labelFor(proof.type)} • ${timeFmt.format(Date(proof.timestamp))}"
                            textSize = 13f
                            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
                        })
                        content.addView(ImageView(this@ExamResultsActivity).apply {
                            adjustViewBounds = true
                            setImageBitmap(bmp)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        })
                    }

                    val missing = ids.size - content.childCount / 2
                    if (content.childCount == 0) {
                        content.addView(TextView(this@ExamResultsActivity).apply {
                            text = "No evidence images are available yet. They may still be uploading from the student's device."
                        })
                    } else if (missing > 0) {
                        content.addView(TextView(this@ExamResultsActivity).apply {
                            text = "$missing image(s) unavailable (may still be uploading)."
                            textSize = 12f
                            setPadding(0, (12 * dp).toInt(), 0, 0)
                        })
                    }
                }
            }
        }
        dialog.setCanceledOnTouchOutside(true)
    }
}