package com.example.bantayex.data.repository

import com.example.bantayex.core.constants.FirestoreConstants
import com.example.bantayex.core.util.Result
import com.example.bantayex.data.model.Exam
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * Shared across the student flow (Step 3) and the professor flow (this step) — lives in
 * the top-level data/ package rather than inside one feature folder.
 *
 * No interface: one implementation (Firestore), no tests planned for this project.
 *
 * Every method uses Firestore's built-in toObject()/toObjects()/set(exam) instead of the
 * old hand-written examToMap()/parseExam() functions (~70 lines removed). This works
 * because Exam and Question are plain data classes with default values for every
 * property, which is exactly what Firestore's automatic mapper requires.
 *
 * Reads copy the snapshot's document ID into Exam.id. The mapper only fills `id`
 * from a stored field, and exams created by the web app omit that field, so without
 * this the ID defaults to "" and edit/delete target an invalid document.
 */
class ExamRepository @Inject constructor(
    firestore: FirebaseFirestore
) {
    private val examsCollection = firestore.collection(FirestoreConstants.COLLECTION_EXAMS)

    suspend fun createExam(exam: Exam): Result<String> {
        return try {
            val docRef = examsCollection.document()
            val examWithId = exam.copy(id = docRef.id)
            docRef.set(examWithId).await()
            Result.Success(docRef.id)
        } catch (e: Exception) {
            Result.Error(Exception("Couldn't create exam. Check your connection."))
        }
    }

    suspend fun updateExam(exam: Exam): Result<Unit> {
        return try {
            examsCollection.document(exam.id).set(exam).await()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(Exception("Couldn't save changes. Check your connection."))
        }
    }

    suspend fun deleteExam(examId: String): Result<Unit> {
        return try {
            examsCollection.document(examId).delete().await()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(Exception("Couldn't delete exam. Check your connection."))
        }
    }

    suspend fun getExamByCode(code: String): Result<Exam> {
        return try {
            val snapshot = examsCollection
                .whereEqualTo(FirestoreConstants.FIELD_EXAM_CODE, code.uppercase())
                .get()
                .await()

            val doc = snapshot.documents.firstOrNull()
                ?: return Result.Error(Exception("Exam not found. Check your code."))
            val exam = doc.toObject(Exam::class.java)
                ?.copy(id = doc.id)
                ?: return Result.Error(Exception("Exam not found. Check your code."))

            Result.Success(exam)
        } catch (e: Exception) {
            Result.Error(Exception("Couldn't reach the exam. Check your connection."))
        }
    }

    suspend fun getExamById(examId: String): Result<Exam> {
        return try {
            val doc = examsCollection.document(examId).get().await()
            val exam = doc.toObject(Exam::class.java)
                ?.copy(id = doc.id)
                ?: return Result.Error(Exception("Exam not found."))
            Result.Success(exam)
        } catch (e: Exception) {
            Result.Error(Exception("Couldn't reach the exam. Check your connection."))
        }
    }

    suspend fun getExamsByProfessor(professorId: String): Result<List<Exam>> {
        return try {
            val snapshot = examsCollection
                .whereEqualTo(FirestoreConstants.FIELD_PROFESSOR_ID, professorId)
                .get()
                .await()
            val exams = snapshot.documents
                .mapNotNull { doc -> doc.toObject(Exam::class.java)?.copy(id = doc.id) }
                .sortedByDescending { it.createdAt }
            Result.Success(exams)
        } catch (e: Exception) {
            Result.Error(Exception("Couldn't load your exams. Check your connection."))
        }
    }

    /** Used by the Create/Edit Exam screen (Step 4b) to stop duplicate exam codes. */
    suspend fun isExamCodeTaken(code: String, excludeExamId: String = ""): Boolean {
        return try {
            val snapshot = examsCollection
                .whereEqualTo(FirestoreConstants.FIELD_EXAM_CODE, code.uppercase())
                .get()
                .await()
            snapshot.documents.any { it.id != excludeExamId }
        } catch (e: Exception) {
            false
        }
    }
}