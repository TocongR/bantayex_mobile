package com.example.bantayex.data

/**
 * Was duplicated in two places with slightly different wording: ResultsListAdapter
 * (professor results list, Step 4c) had its own formatViolationType() function, and
 * ResultActivity (student result screen) had its own VIOLATION_LABELS map — same four
 * violation types, different label text in each ("Repeated look-away" vs "Repeated
 * look-away pattern", "Left app" vs "Left the app"). One canonical mapping now; both
 * screens read from here.
 */
object ViolationLabels {
    private val LABELS = mapOf(
        "LOOK_AWAY" to "Looked away",
        "REPEATED_LOOK_AWAY" to "Repeated look-away pattern",
        "NO_FACE" to "Face not visible",
        "APP_EXIT" to "Left the app"
    )

    fun labelFor(type: String): String = LABELS[type] ?: type
}