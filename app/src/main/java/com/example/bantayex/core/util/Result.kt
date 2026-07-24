package com.example.bantayex.core.util

/**
 * Wraps the outcome of a repository operation (Firestore/Auth calls).
 * Used ONLY at the Repository -> ViewModel boundary.
 *
 * Why not kotlin.Result? kotlin.Result mixes poorly with sealed-`when` exhaustiveness
 * checks and doesn't read as clearly in a `when` block. This is a tiny, explicit type
 * built for exactly this app's needs.
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Exception) : Result<Nothing>()
}

/** Convenience so repository call-sites can chain instead of `when`-ing everywhere. */
inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <T> Result<T>.onError(action: (Exception) -> Unit): Result<T> {
    if (this is Result.Error) action(exception)
    return this
}