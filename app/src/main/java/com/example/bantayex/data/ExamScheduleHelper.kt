package com.example.bantayex.data

import com.example.bantayex.data.model.Exam
import java.text.SimpleDateFormat
import java.util.*

/**
 * This was already well-organized in the old codebase (single responsibility, pure
 * functions, no changes needed to the logic itself). Only its location changed: it
 * moved from the generic `util/` package into `data/`, because it's Exam-domain logic
 * (it knows what an Exam is), not a feature-agnostic helper like DateUtils would be.
 * It's used by both the student flow (Step 3) and the professor flow (Step 4), so it
 * stays in the shared data/ package rather than inside either feature.
 */
object ExamScheduleHelper {

    fun todayDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    enum class ExamStatus { UPCOMING, ACTIVE, EXPIRED }

    fun getStatus(exam: Exam): ExamStatus {
        val date = exam.availableDate.trim()
        val from = exam.availableFrom.trim()
        val to = exam.availableTo.trim()

        // If any schedule field is missing, treat as EXPIRED so students can't access it
        if (date.isEmpty() || from.isEmpty() || to.isEmpty()) {
            return ExamStatus.EXPIRED
        }

        val now = Calendar.getInstance()
        val start = parseDateTime(date, from) ?: return ExamStatus.EXPIRED
        val end = parseDateTime(date, to) ?: return ExamStatus.EXPIRED

        // Sanity: if end <= start, treat as invalid -> expired
        if (!end.after(start)) return ExamStatus.EXPIRED

        return when {
            now.before(start) -> ExamStatus.UPCOMING
            now.after(end) -> ExamStatus.EXPIRED
            else -> ExamStatus.ACTIVE
        }
    }

    fun isAvailableNow(exam: Exam): Boolean = getStatus(exam) == ExamStatus.ACTIVE

    fun isEditable(exam: Exam): Boolean = getStatus(exam) == ExamStatus.UPCOMING

    fun statusLabel(exam: Exam): String {
        val date = exam.availableDate.trim()
        val from = exam.availableFrom.trim()
        val to = exam.availableTo.trim()

        if (date.isEmpty() || from.isEmpty() || to.isEmpty()) return "⚠ No schedule set"

        return when (getStatus(exam)) {
            ExamStatus.UPCOMING -> "⏳ Upcoming • $date  $from – $to"
            ExamStatus.ACTIVE -> "✅ Active • $from – $to"
            ExamStatus.EXPIRED -> "🔴 Expired • $date  $from – $to"
        }
    }

    private fun parseDateTime(date: String, time: String): Calendar? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.isLenient = false
            val d = sdf.parse("$date $time") ?: return null
            Calendar.getInstance().apply { this.time = d }
        } catch (e: Exception) {
            null
        }
    }
}