package com.example.bantayex.data.model

import java.io.Serializable

data class Violation(
    val type: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val proofId: String = ""   // document id in the "violation_proofs" collection; "" = no image
) : Serializable