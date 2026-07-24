package com.example.bantayex.features.student.entercode

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.bantayex.R
import com.example.bantayex.core.util.UiState
import com.example.bantayex.databinding.ActivityEnterCodeBinding
// NOTE: still points at the OLD student package for now. Updated when we rebuild the
// exam-details / take-exam screens later in the roadmap.
import com.example.bantayex.features.student.examdetails.ExamDetailsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EnterCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEnterCodeBinding
    private val viewModel: EnterCodeViewModel by viewModels()
    private lateinit var boxes: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnterCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        boxes = listOf(
            binding.tvBox1, binding.tvBox2, binding.tvBox3,
            binding.tvBox4, binding.tvBox5, binding.tvBox6
        )

        updateBoxes("")
        boxes.forEach { it.setOnClickListener { openKeyboard() } }
        binding.root.postDelayed({ openKeyboard() }, 150)

        binding.etHiddenCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateBoxes(s.toString().uppercase())
            }
        })

        // Length check now lives ONLY in the ViewModel (see login step) — Activity just forwards.
        binding.btnSubmitCode.setOnClickListener {
            hideKeyboard()
            viewModel.validateCode(binding.etHiddenCode.text.toString().trim().uppercase())
        }

        binding.btnBack.setOnClickListener { finish() }

        observeCodeState()
    }

    override fun onResume() {
        super.onResume()
        // Reset so student can try again after coming back from Exam Details.
        viewModel.resetState()
        binding.etHiddenCode.setText("")
        updateBoxes("")
    }

    private fun observeCodeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.codeState.collect { state ->
                    when (state) {
                        null -> Unit
                        is UiState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.btnSubmitCode.isEnabled = false
                        }
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnSubmitCode.isEnabled = true
                            val intent = Intent(this@EnterCodeActivity, ExamDetailsActivity::class.java)
                            intent.putExtra("examId", state.data.id)
                            intent.putExtra("examCode", state.data.examCode)
                            startActivity(intent)
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnSubmitCode.isEnabled = true
                            shakeBoxes()
                            Toast.makeText(this@EnterCodeActivity, state.message, Toast.LENGTH_LONG).show()
                            binding.etHiddenCode.setText("")
                        }
                    }
                }
            }
        }
    }

    private fun updateBoxes(text: String) {
        for (i in boxes.indices) {
            val box = boxes[i]
            when {
                i < text.length -> {
                    box.text = text[i].toString()
                    box.setBackgroundResource(R.drawable.code_box_filled)
                }
                i == text.length -> {
                    box.text = ""
                    box.setBackgroundResource(R.drawable.code_box_active)
                }
                else -> {
                    box.text = ""
                    box.setBackgroundResource(R.drawable.code_box_default)
                }
            }
        }
    }

    private fun shakeBoxes() {
        boxes.forEach { box ->
            box.animate()
                .translationX(14f).setDuration(60)
                .withEndAction {
                    box.animate().translationX(-14f).setDuration(60)
                        .withEndAction {
                            box.animate().translationX(8f).setDuration(60)
                                .withEndAction {
                                    box.animate().translationX(0f).setDuration(60).start()
                                }.start()
                        }.start()
                }.start()
        }
    }

    private fun openKeyboard() {
        binding.etHiddenCode.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etHiddenCode, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}