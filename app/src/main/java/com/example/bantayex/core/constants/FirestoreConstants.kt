package com.example.bantayex.core.constants

/**
 * Centralized Firestore collection and field names. In the old ExamRepository these
 * were string literals ("exams", "examCode", "professorId"...) scattered across
 * multiple functions. A typo in one of those strings fails silently at runtime with
 * no compile error. Centralizing them here means a rename is a one-line change and
 * autocomplete catches typos.
 *
 * Add to this file whenever a new Firestore collection or a field name used in a
 * `.whereEqualTo(...)` query gets introduced.
 */
object FirestoreConstants {
    const val COLLECTION_EXAMS = "exams"
    const val COLLECTION_RESULTS = "results"
    const val COLLECTION_VIOLATION_PROOFS = "violation_proofs"

    const val FIELD_EXAM_CODE = "examCode"
    const val FIELD_PROFESSOR_ID = "professorId"
}