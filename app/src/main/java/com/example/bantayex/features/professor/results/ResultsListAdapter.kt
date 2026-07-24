package com.example.bantayex.features.professor.results

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bantayex.data.ViolationLabels
import com.example.bantayex.data.model.ExamResult
import com.example.bantayex.databinding.ItemResultBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unchanged from the Step 4c version except formatViolationType() is gone — it now
 * calls the shared ViolationLabels.labelFor() (see data/ViolationLabels.kt) instead of
 * duplicating its own copy of the same mapping with slightly different wording.
 */
class ResultsListAdapter : ListAdapter<ExamResult, ResultsListAdapter.ResultViewHolder>(DiffCallback()) {

    inner class ResultViewHolder(private val binding: ItemResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(result: ExamResult) {
            binding.tvStudentName.text = result.studentName
            binding.tvScore.text = "${result.score}/${result.totalQuestions}"
            val percent = if (result.totalQuestions > 0) (result.score * 100 / result.totalQuestions) else 0
            binding.tvPercent.text = "$percent%"
            binding.tvStatus.text = if (result.passed) "PASSED" else "FAILED"
            binding.tvStatus.setTextColor(
                if (result.passed)
                    binding.root.context.getColor(android.R.color.holo_green_dark)
                else
                    binding.root.context.getColor(android.R.color.holo_red_dark)
            )
            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            binding.tvTimestamp.text = sdf.format(Date(result.timestamp))

            if (result.violations > 0) {
                binding.tvViolations.visibility = View.VISIBLE
                val typesSummary = result.violationLog
                    .groupingBy { it.type }
                    .eachCount()
                    .entries.joinToString(", ") { "${ViolationLabels.labelFor(it.key)}: ${it.value}" }
                binding.tvViolations.text =
                    "${result.violations} total violation(s)\n$typesSummary" +
                            (if (!result.gazeMonitoringEnabled) "\n(no camera consent)" else "")
            } else {
                binding.tvViolations.visibility = View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ResultViewHolder(ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) =
        holder.bind(getItem(position))

    class DiffCallback : DiffUtil.ItemCallback<ExamResult>() {
        override fun areItemsTheSame(oldItem: ExamResult, newItem: ExamResult) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ExamResult, newItem: ExamResult) = oldItem == newItem
    }
}