package com.legion.orion.core.common

/** Result type used across layers; keeps failures typed without exceptions at boundaries. */
sealed interface OrionResult<out T> {
    data class Success<T>(val data: T) : OrionResult<T>
    data class Failure(val error: Throwable, val message: String = error.message ?: "Unknown error") :
        OrionResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.data

    companion object {
        inline fun <T> runCatching(block: () -> T): OrionResult<T> = try {
            Success(block())
        } catch (t: Throwable) {
            Failure(t)
        }
    }
}
