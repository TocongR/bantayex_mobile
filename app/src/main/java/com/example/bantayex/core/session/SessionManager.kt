package com.example.bantayex.core.session

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "who is currently using the app."
 *
 * Only Professors authenticate via Firebase Auth in this app — Students just enter an
 * exam code (see EnterCodeViewModel in Step 3), they never get a FirebaseUser. So this
 * class currently only tracks Professor auth state. If student accounts get added later,
 * this is the one place that changes — nothing downstream should call FirebaseAuth directly.
 *
 * Exposed as a StateFlow (not a plain val) so any screen can reactively react to logout,
 * e.g. auto-navigating back to Welcome if a session expires mid-use.
 */
@Singleton
class SessionManager @Inject constructor(
    private val auth: FirebaseAuth
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val currentUser: StateFlow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = auth.currentUser
    )

    val isLoggedIn: Boolean
        get() = auth.currentUser != null

    val currentUserId: String?
        get() = auth.currentUser?.uid

    fun logout() {
        auth.signOut()
    }
}