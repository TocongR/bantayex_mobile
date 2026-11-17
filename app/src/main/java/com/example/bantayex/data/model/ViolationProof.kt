package com.example.bantayex.data.model

import com.google.firebase.firestore.Blob

/**
 * One evidence photo, stored as its own Firestore document (collection "violation_proofs").
 *
 * It is deliberately NOT embedded in the ExamResult document:
 *  - a document is capped at 1 MiB, so several photos inside "results" could break result saving;
 *  - the professor's results list would download every photo just to show scores.
 * ExamResult.violationLog only keeps the small proofId; photos are fetched on demand.
 */
data class ViolationProof(
    val id: String = "",
    val examCode: String = "",
    val studentName: String = "",
    val type: String = "",
    val timestamp: Long = 0L,
    val image: Blob? = null      // compressed JPEG bytes
)