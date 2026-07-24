package com.example.bantayex.features.student.takeexam

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.bantayex.R
import com.example.bantayex.data.model.Question
import com.example.bantayex.databinding.ItemQuestionPageBinding

/**
 * Unchanged from the old version — already well-contained, single responsibility.
 * Only the package and R/binding import paths changed.
 */
class QuestionPagerAdapter(
    private val questions: List<Question>,
    private val getAnswer: (String) -> String?,
    private val onAnswerSelected: (position: Int, question: Question, answer: String) -> Unit
) : RecyclerView.Adapter<QuestionPagerAdapter.PageViewHolder>() {

    inner class PageViewHolder(val binding: ItemQuestionPageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemQuestionPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val q = questions[position]
        val b = holder.binding
        val context = b.root.context

        b.tvQuestionText.text = q.text

        // Clear listener + old option views before rebuilding
        b.rgOptions.setOnCheckedChangeListener(null)
        b.rgOptions.removeAllViews()

        val dp = context.resources.displayMetrics.density
        val currentAnswerIndex = getAnswer(q.id)?.toIntOrNull()
        val letters = "ABCDEFGHIJ" // supports up to 10 options; extend if needed

        q.options.forEachIndexed { index, optionText ->
            val prefix = if (index < letters.length) letters[index] else (index + 1).toString()
            val rb = RadioButton(context).apply {
                id = View.generateViewId()
                text = "$prefix.  $optionText"
                textSize = 14f
                setTextColor(context.getColor(R.color.text_primary))
                setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
                setBackgroundResource(R.drawable.option_selector)
                buttonTintList = context.getColorStateList(R.color.primary)
                setLineSpacing(0f, 1.2f)
                tag = index
            }
            val params = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
            b.rgOptions.addView(rb, params)

            if (index == currentAnswerIndex) {
                b.rgOptions.check(rb.id)
            }
        }

        b.rgOptions.setOnCheckedChangeListener { group, checkedId ->
            val checkedView = group.findViewById<RadioButton>(checkedId)
            val index = checkedView?.tag as? Int ?: return@setOnCheckedChangeListener
            onAnswerSelected(position, q, index.toString())
        }
    }

    override fun getItemCount(): Int = questions.size
}