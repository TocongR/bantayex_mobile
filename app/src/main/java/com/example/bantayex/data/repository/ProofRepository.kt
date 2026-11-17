package com.example.bantayex.data.repository

import android.util.Log
import com.example.bantayex.core.constants.FirestoreConstants
import com.example.bantayex.core.util.Result
import com.example.bantayex.data.model.ViolationProof
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * Evidence photos live in Firestore (Blob field) instead of Cloud Storage, so everything
 * stays on the free Spark plan.
 */
class ProofRepository @Inject constructor(
    firestore: FirebaseFirestore
) {
    private val proofs = firestore.collection(FirestoreConstants.COLLECTION_VIOLATION_PROOFS)

    /** Generated locally (no network), so it can be put in the violation log immediately. */
    fun newProofId(): String = proofs.document().id

    /**
     * Fire-and-forget. Firestore keeps unsent writes in its offline queue and retries when
     * the connection is back, so a photo taken on a bad connection is not lost.
     */
    fun uploadProof(
        id: String,
        examCode: String,
        studentName: String,
        type: String,
        timestamp: Long,
        jpegBytes: ByteArray
    ) {
        val proof = ViolationProof(
            id = id,
            examCode = examCode,
            studentName = studentName,
            type = type,
            timestamp = timestamp,
            image = Blob.fromBytes(jpegBytes)
        )
        proofs.document(id).set(proof)
            .addOnFailureListener { Log.w("ProofRepository", "Upload failed for proof $id", it) }
    }

    /** Fetched on demand when the professor opens a student's evidence. */
    suspend fun getProofsByIds(ids: List<String>): Result<List<ViolationProof>> {
        return try {
            val found = ids.distinct().chunked(10).flatMap { chunk ->
                proofs.whereIn(FieldPath.documentId(), chunk)
                    .get()
                    .await()
                    .toObjects(ViolationProof::class.java)
            }.sortedBy { it.timestamp }
            Result.Success(found)
        } catch (e: Exception) {
            Result.Error(Exception("Couldn't load evidence. Check your connection."))
        }
    }
}