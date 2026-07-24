package com.example.bantayex.features.professor.dashboard

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bantayex.R
import com.example.bantayex.data.ExamScheduleHelper
import com.example.bantayex.data.model.Exam
import com.example.bantayex.databinding.ItemExamBinding

/**
 * Unchanged from the old version — this file was already well-organized (ListAdapter +
 * DiffUtil used correctly, single responsibility). Only the package and the
 * ExamScheduleHelper import path changed (it moved from util/ to data/ in Step 3).
 */
class ExamListAdapter(
    private val onClickResults: (Exam) -> Unit,
    private val onClickEdit: (Exam) -> Unit,
    private val onClickDelete: (Exam) -> Unit
) : ListAdapter<Exam, ExamListAdapter.ExamViewHolder>(DiffCallback()) {

    inner class ExamViewHolder(private val binding: ItemExamBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(exam: Exam) {
            val status = ExamScheduleHelper.getStatus(exam)

            binding.tvExamTitle.text = exam.title
            binding.tvExamCode.text = exam.examCode
            binding.tvExamMeta.text =
                "${exam.timeLimitMinutes} min  ·  ${exam.questions.size} question${if (exam.questions.size != 1) "s" else ""}  ·  Pass: ${exam.passingScore}/${exam.questions.size}"
            binding.tvExamSchedule.text =
                if (exam.availableDate.isNotEmpty())
                    "${exam.availableDate}   ${exam.availableFrom} – ${exam.availableTo}"
                else "No schedule set"

            when (status) {
                ExamScheduleHelper.ExamStatus.ACTIVE -> {
                    binding.tvStatus.text = "ACTIVE"
                    binding.tvStatus.setTextColor(Color.parseColor("#2D7A4F"))
                    binding.tvStatus.setBackgroundResource(R.drawable.status_active_bg)
                    binding.tvExamCode.setBackgroundResource(R.drawable.exam_code_badge)
                    binding.root.alpha = 1f
                }
                ExamScheduleHelper.ExamStatus.UPCOMING -> {
                    binding.tvStatus.text = "UPCOMING"
                    binding.tvStatus.setTextColor(Color.parseColor("#B07D2A"))
                    binding.tvStatus.setBackgroundResource(R.drawable.status_upcoming_bg)
                    binding.tvExamCode.setBackgroundResource(R.drawable.exam_code_badge)
                    binding.root.alpha = 1f
                }
                ExamScheduleHelper.ExamStatus.EXPIRED -> {
                    binding.tvStatus.text = "EXPIRED"
                    binding.tvStatus.setTextColor(Color.parseColor("#B4B2A9"))
                    binding.tvStatus.setBackgroundResource(R.drawable.status_badge_bg)
                    binding.tvExamCode.setBackgroundResource(R.drawable.exam_code_badge_expired)
                    binding.root.alpha = 0.6f
                }
            }

            binding.btnEdit.visibility =
                if (status == ExamScheduleHelper.ExamStatus.UPCOMING) View.VISIBLE else View.GONE
            binding.btnDelete.visibility = View.VISIBLE

            binding.btnViewResults.setOnClickListener { onClickResults(exam) }
            binding.btnEdit.setOnClickListener { onClickEdit(exam) }
            binding.btnDelete.setOnClickListener { onClickDelete(exam) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ExamViewHolder(
            ItemExamBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ExamViewHolder, position: Int) =
        holder.bind(getItem(position))

    class DiffCallback : DiffUtil.ItemCallback<Exam>() {
        override fun areItemsTheSame(oldItem: Exam, newItem: Exam) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Exam, newItem: Exam) = oldItem == newItem
    }
}