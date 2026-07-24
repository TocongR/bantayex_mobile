package com.example.bantayex.features.professor.results

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bantayex.core.util.UiState
import com.example.bantayex.data.model.ExamResult
import com.example.bantayex.databinding.ActivityExamResultsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

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
        resultsAdapter = ResultsListAdapter()
        binding.rvResults.apply {
            layoutManager = LinearLayoutManager(this@ExamResultsActivity)
            adapter = resultsAdapter
        }
    }
}