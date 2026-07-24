package com.example.bantayex.features.professor.dashboard

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bantayex.core.util.UiState
import com.example.bantayex.databinding.ActivityProfessorDashboardBinding
import com.example.bantayex.features.auth.login.LoginActivity
import com.example.bantayex.features.professor.createexam.CreateExamActivity
import com.example.bantayex.features.professor.results.ExamResultsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProfessorDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfessorDashboardBinding
    private val viewModel: ProfessorDashboardViewModel by viewModels()
    private lateinit var examAdapter: ExamListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfessorDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()

        binding.fabCreateExam.setOnClickListener {
            startActivity(Intent(this, CreateExamActivity::class.java))
        }

        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sign out")
                .setMessage("Are you sure you want to sign out?")
                .setPositiveButton("Sign out") { _, _ ->
                    viewModel.logout()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        observeUiState()
        observeEvents()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadExams()
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is UiState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            renderExamList(state.data)
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(this@ProfessorDashboardActivity, state.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { message ->
                    Toast.makeText(this@ProfessorDashboardActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderExamList(exams: List<com.example.bantayex.data.model.Exam>) {
        binding.tvExamCount.text = "${exams.size} exam${if (exams.size != 1) "s" else ""} created"
        if (exams.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvExams.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvExams.visibility = View.VISIBLE
            examAdapter.submitList(exams)
        }
    }

    private fun setupRecyclerView() {
        examAdapter = ExamListAdapter(
            onClickResults = { exam ->
                val intent = Intent(this, ExamResultsActivity::class.java)
                intent.putExtra("examCode", exam.examCode)
                intent.putExtra("examTitle", exam.title)
                startActivity(intent)
            },
            onClickEdit = { exam ->
                val intent = Intent(this, CreateExamActivity::class.java)
                intent.putExtra("editExamId", exam.id)
                startActivity(intent)
            },
            onClickDelete = { exam ->
                AlertDialog.Builder(this)
                    .setTitle("Delete exam")
                    .setMessage("Delete \"${exam.title}\"? This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deleteExam(exam.id) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        binding.rvExams.apply {
            layoutManager = LinearLayoutManager(this@ProfessorDashboardActivity)
            adapter = examAdapter
            addItemDecoration(bottomDividerDecoration())
        }
    }

    private fun bottomDividerDecoration() = object : RecyclerView.ItemDecoration() {
        private val paint = Paint().apply { color = Color.parseColor("#F5F5F3") }
        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (i in 0 until parent.childCount - 1) {
                val child = parent.getChildAt(i)
                c.drawRect(
                    child.left.toFloat(), child.bottom.toFloat(),
                    child.right.toFloat(), (child.bottom + 1).toFloat(), paint
                )
            }
        }
    }
}