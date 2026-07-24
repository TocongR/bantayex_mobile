package com.example.bantayex.data.model

data class Exam(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val timeLimitMinutes: Int = 30,
    val passingScore: Int = 1,           // raw number of correct answers required, NOT a percentage
    val examCode: String = "",
    val professorId: String = "",
    val professorName: String = "",      // denormalized at creation time — see CreateExamViewModel.
    // Exams created before this field existed will read as "".
    val questions: List<Question> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val availableDate: String = "",      // "yyyy-MM-dd"
    val availableFrom: String = "",      // "HH:mm"
    val availableTo: String = ""         // "HH:mm"
)