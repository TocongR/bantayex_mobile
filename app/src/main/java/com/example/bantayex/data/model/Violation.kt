package com.example.bantayex.data.model

import java.io.Serializable

data class Violation(
    val type: String = "",
    val timestamp: Long = System.currentTimeMillis()
) : Serializable