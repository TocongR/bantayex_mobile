package com.example.bantayex.data.model

data class ExamResult(
    val id: String = "",
    val examCode: String = "",
    val examId: String = "",
    val examTitle: String = "",
    val studentName: String = "",
    val score: Int = 0,
    val totalQuestions: Int = 0,
    val answers: Map<String, String> = emptyMap(),
    val passed: Boolean = false,
    val violations: Int = 0,
    val violationLog: List<Violation> = emptyList(),
    val gazeMonitoringEnabled: Boolean = false,
    val autoSubmitted: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)