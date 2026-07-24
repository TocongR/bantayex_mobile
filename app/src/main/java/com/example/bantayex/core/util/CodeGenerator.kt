package com.example.bantayex.core.util

/**
 * Moved from the old util/ package, logic unchanged. Kept in core/util (not data/) because
 * this doesn't know what an Exam is — it just makes random strings. If it referenced the
 * Exam model it would belong in data/ instead, same reasoning as ExamScheduleHelper.
 *
 * NOTE: the old CreateExamActivity had its OWN copy-pasted version of this exact logic
 * inline instead of calling this object. That duplication is gone now — CreateExamViewModel
 * is the only caller.
 */
object CodeGenerator {
    private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(length: Int = 6): String =
        (1..length).map { CHARS.random() }.joinToString("")
}