package com.example.bantayex.features.professor.createexam

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.recyclerview.widget.RecyclerView
import com.example.bantayex.data.model.Question
import com.example.bantayex.databinding.ItemQuestionCreateBinding

/**
 * Unchanged from the old version — this adapter's logic (dynamically building option rows,
 * live-syncing text into the in-memory Question list, cursor-jump protection on the text
 * watcher) was already well-contained and doing exactly one job. Only the package moved.
 */
class QuestionCreateAdapter(
    private val questions: MutableList<Question>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<QuestionCreateAdapter.QuestionViewHolder>() {

    inner class QuestionViewHolder(val binding: ItemQuestionCreateBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuestionViewHolder {
        val binding = ItemQuestionCreateBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return QuestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QuestionViewHolder, position: Int) {
        val question = questions[position]
        val binding = holder.binding
        val context = binding.root.context

        binding.tvQuestionNumber.text = "Question ${position + 1}"

        if (binding.etQuestionText.text.toString() != question.text) {
            binding.etQuestionText.setText(question.text)
        }
        binding.tvQuestionPreview.text =
            question.text.ifEmpty { "No question text yet" }
        binding.tvQuestionPreview.setTextColor(
            if (question.text.isEmpty()) context.getColor(android.R.color.darker_gray)
            else Color.parseColor("#2C2C2A")
        )

        binding.etQuestionText.tag?.let { (it as? TextWatcher)?.let(binding.etQuestionText::removeTextChangedListener) }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.tvQuestionPreview.text =
                    if (s.isNullOrEmpty()) "No question text yet" else s.toString()
                binding.tvQuestionPreview.setTextColor(
                    if (s.isNullOrEmpty()) context.getColor(android.R.color.darker_gray)
                    else Color.parseColor("#2C2C2A")
                )
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        binding.etQuestionText.addTextChangedListener(watcher)
        binding.etQuestionText.tag = watcher

        val options = if (question.options.isEmpty())
            mutableListOf("", "") else question.options.toMutableList()
        if (question.options.isEmpty()) {
            questions[position] = question.copy(options = options, correctAnswer = -1)
        }

        rebuildOptionRows(holder, position)

        binding.btnAddOption.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val q = questions[pos]
            questions[pos] = q.copy(options = q.options + "")
            rebuildOptionRows(holder, pos)
        }

        binding.btnDeleteQuestion.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onDelete(pos)
        }
    }

    private fun rebuildOptionRows(holder: QuestionViewHolder, position: Int) {
        val binding = holder.binding
        val context = binding.root.context
        val question = questions[position]
        val dp = context.resources.displayMetrics.density

        binding.llOptionsContainer.removeAllViews()

        question.options.forEachIndexed { index, optionText ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
            }

            val radio = RadioButton(context).apply {
                id = View.generateViewId()
                isChecked = (index == question.correctAnswer)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    questions[pos] = questions[pos].copy(correctAnswer = index)
                    rebuildOptionRows(holder, pos)
                }
            }

            val editText = AppCompatEditText(context).apply {
                setText(optionText)
                hint = "Option ${index + 1}"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (8 * dp).toInt() }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val pos = holder.bindingAdapterPosition
                        if (pos == RecyclerView.NO_POSITION) return
                        val current = questions[pos]
                        val updatedOptions = current.options.toMutableList()
                        if (index < updatedOptions.size) {
                            updatedOptions[index] = s.toString()
                            questions[pos] = current.copy(options = updatedOptions)
                        }
                    }
                })
            }

            val removeBtn = android.widget.TextView(context).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Color.parseColor("#E57373"))
                setPadding((8 * dp).toInt(), 0, 0, 0)
                setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val current = questions[pos]
                    if (current.options.size <= 2) return@setOnClickListener
                    val updatedOptions = current.options.toMutableList().apply { removeAt(index) }
                    val newCorrect = when {
                        current.correctAnswer == index -> -1
                        current.correctAnswer > index -> current.correctAnswer - 1
                        else -> current.correctAnswer
                    }
                    questions[pos] = current.copy(options = updatedOptions, correctAnswer = newCorrect)
                    rebuildOptionRows(holder, pos)
                }
            }

            row.addView(radio)
            row.addView(editText)
            row.addView(removeBtn)
            binding.llOptionsContainer.addView(row)
        }
    }

    override fun getItemCount() = questions.size

    // Called from Activity to sync text fields before saving. Options/correctAnswer are
    // already kept in sync live via the listeners above, so this only needs to flush the
    // question text (which uses cursor-jump protection above).
    fun syncQuestion(position: Int, recyclerView: RecyclerView) {
        val vh = recyclerView.findViewHolderForAdapterPosition(position) as? QuestionViewHolder
            ?: return
        val b = vh.binding
        questions[position] = questions[position].copy(
            text = b.etQuestionText.text.toString().trim(),
            options = questions[position].options.map { it.trim() }
        )
    }
}