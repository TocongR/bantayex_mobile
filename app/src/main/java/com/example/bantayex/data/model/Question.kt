package com.example.bantayex.data.model

data class Question(
    val id: String = "",
    val text: String = "",
    val options: List<String> = emptyList(),
    val correctAnswer: Int = -1   // index into `options`
)