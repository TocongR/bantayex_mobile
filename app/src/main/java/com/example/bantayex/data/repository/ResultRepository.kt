package com.example.bantayex.data.repository

import com.example.bantayex.core.constants.FirestoreConstants
import com.example.bantayex.core.util.Result
import com.example.bantayex.data.model.ExamResult
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * Shared data — will also be used by the student side later (Step 5, saving a result
 * after an exam) and by the proctoring step (writing violations into the result). Same
 * reasoning as ExamRepository: lives in data/repository/, not inside one feature.
 *
 * Same fix as ExamRepository: toObject()/toObjects()/set(result) replace ~40 lines of
 * manual saveResult()/parseResult() map-building. ExamResult and Violation are both plain
 * data classes with defaults, so Firestore's automatic mapper handles them directly —
 * including the nested List<Violation>.
 */
class ResultRepository @Inject constructor(
    firestore: FirebaseFirestore
) {
    private val resultsCollection = firestore.collection(FirestoreConstants.COLLECTION_RESULTS)

    suspend fun saveResult(result: ExamResult): Result<String> {
        return try {
            val docRef = resultsCollection.document()
            val resultWithId = result.copy(id = docRef.id)
            docRef.set(resultWithId).await()
            Result.Success(docRef.id)
        } catch (e: Exception) {
            Result.Error(Exception("Couldn't save your result. Check your connection."))
        }
    }

    suspend fun getResultsByExamCode(examCode: String): Result<List<ExamResult>> {
        return try {
            val snapshot = resultsCollection
                .whereEqualTo(FirestoreConstants.FIELD_EXAM_CODE, examCode)
                .get()
                .await()
            val results = snapshot.toObjects(ExamResult::class.java)
                .sortedByDescending { it.timestamp }
            Result.Success(results)
        } catch (e: Exception) {
            Result.Error(Exception("Couldn't load results. Check your connection."))
        }
    }
}