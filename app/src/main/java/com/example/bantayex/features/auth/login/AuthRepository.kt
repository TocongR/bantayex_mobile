package com.example.bantayex.features.auth.login

import com.example.bantayex.core.util.Result
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * Only Professors sign in via Firebase Auth in this app, so this repository stays
 * intentionally small — one method. No interface: there is exactly one implementation
 * and no tests planned, so an `AuthRepository` interface + `AuthRepositoryImpl` pair
 * would be a file that exists purely to satisfy a "pattern," not a real need.
 *
 * FirebaseAuth is injected (see core/di/FirebaseModule.kt) instead of calling
 * FirebaseAuth.getInstance() here directly — that's the one seam that would matter
 * later if e.g. you wanted to point tests at the Firebase emulator.
 */
class AuthRepository @Inject constructor(
    private val auth: FirebaseAuth
) {
    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            auth.signInWithEmailAndPassword(email, password).await()
            Result.Success(Unit)
        } catch (e: FirebaseAuthInvalidUserException) {
            Result.Error(Exception("No account found with that email."))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            Result.Error(Exception("Wrong password."))
        } catch (e: Exception) {
            Result.Error(Exception("Login failed: ${e.message}"))
        }
    }
}